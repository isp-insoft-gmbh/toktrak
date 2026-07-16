# TokTrak design

Date: 2026-07-10 Status: approved

## Goal

TokTrak tracks projected AI coding harness usage cost for an internal team.

It is for cost awareness and exploration, not billing, performance review, or
prompt/code surveillance.

## Product stance

- Users are developers.
- All logged-in company users can see all user usage data.
- Data scope is `ccusage` output only: no prompts, no code, no file contents.
- The footer links to a subtle data-scope page explaining this.
- The dashboard supports many visualizations so users can find their own use
  cases.
- The UI is fixed, not customizable.

## High-level architecture

TokTrak has two parts:

1. Server/dashboard
   - Java 26 application.
   - JDK `HttpServer`, HTTP/1.1.
   - Server-side rendered HTML.
   - Datastar JS for reactive dashboard updates.
   - Datastar SSE events sent through the Java SDK only if jlink-safe; current
     decision is to hand-write the tiny patch-elements/signals SSE subset
     because `datastar-java-sdk-core` is an automatic module and jlink rejects
     it.
   - Jackson is an explicit dependency for JSON.
   - Nimbus JOSE JWT is used for JWT/JWK validation because it is jlink-safe.
   - No framework, no DB, no classpath runtime.

2. Workstation tracker
   - One cross-platform Node `.mjs` installer/tracker.
   - Requires Node on developer machines.
   - User-scoped scheduled job only.
   - No root/admin installation.
   - Runs `ccusage` pinned to a known version.
   - Uploads `ccusage` JSON to the server with a bearer tracker token.
   - Self-updates after successful uploads.

## Server dependencies

Allowed production dependencies:

- Jackson core/databind/annotations 2.22.1, explicit JPMS modules.
- Nimbus JOSE JWT 10.9.1, explicit JPMS module.
- Datastar browser JS, vendored/pinned.
- JUnit/test deps only for tests.

Dependency updates must pass the production jlink build; automatic modules are
rejected.

Rejected dependencies:

- Spring, Netty, servlet/JAX-RS stacks.
- SQLite or any DB.
- Full OIDC SDKs that introduce automatic modules incompatible with jlink.
- Datastar Java SDK core 1.0.0 unless it becomes a real JPMS module.

## Runtime model

- Requests run on virtual threads.
- A single writer queue serializes event-log appends.
- Long-lived writer worker uses one platform thread.
- Background async work, such as FX fetches, uses virtual threads.
- Request context is bound with Java scoped values.
- Request context contains request id, method, path, user id, token id, mode.

## HTTP server

Environment:

- `TOKTRAK_PORT`, default `8080`.
- App serves plain HTTP only.
- TLS, HTTP/2, HTTP/3, and compression are proxy/deployment concerns.
- HTTP/2/3 are out of scope until measured need appears.

Public routes:

- `/`
- `/login`
- `/oauth/callback`
- `/health`
- Static CSS/JS/assets required for public pages.

Protected routes:

- Dashboard pages.
- Tracker token management.
- Tracker installer download after token creation.
- Dashboard SSE endpoints.

API error shape:

```json
{"error":{"code":"string","message":"string","requestId":"uuid"}}
```

Browser errors render a brutal HTML error page.

## Health and failure modes

Startup config/log corruption failures fail hard before binding the port.

Runtime degraded mode starts only after successful startup when event appending
fails.

Degraded mode:

- Dashboard remains read-only if projections are already in memory.
- Warning is visible.
- Uploads and mutations return 503.
- Login may still work if projections exist.
- Token creation/revocation is disabled.

Projection corruption at runtime hard-stops because displayed data cannot be
trusted.

`/health` returns `ok` only when service is writable and healthy. In
degraded/failure states it returns failure with a terse reason category and no
secrets.

## Logging

Use `java.util.logging` with a custom JSON `Formatter` writing compact JSON
lines to stdout.

Log fields:

- timestamp
- level
- message
- requestId when present
- method/path/status/duration when present
- userId/tokenId when known
- mode

Do not log emails, token plaintext, or token hashes.

## Security headers and browser security

- One opaque session cookie plus one short-lived OIDC transaction cookie during
  login.
- Both cookies are signed, `HttpOnly`, and `SameSite=Lax`.
- Cookie `Secure` is derived from `TOKTRAK_BASE_URL`: HTTPS means secure, HTTP
  means non-secure.
- Non-localhost HTTP is rejected unless dev auth is explicitly enabled.
- CSRF token required for mutating browser POST forms.
- CSP is strict.
- No Datastar `executeScript` SSE.
- SSE uses patch elements/signals only.
- Inline SVG charts use CSS transitions, not script.
- `localStorage` is allowed only for UI preferences, such as currency choice.

## Auth

OIDC is implemented manually:

- Discovery fetch from `TOKTRAK_OIDC_DISCOVERY_URL`.
- Authorization-code flow with PKCE.
- Login generates cryptographically random state, nonce, and PKCE verifier.
- A short-lived signed transaction cookie binds state, nonce, and verifier to
  the callback.
- Token exchange uses JDK `HttpClient`.
- Nimbus JOSE JWT validates the provider JWKS signature, issuer, audience,
  authorized party when required, expiry, and nonce.
- State comparison is constant-time and consumes the transaction cookie.
- Session cookie is stateless and signed.

Required OIDC env vars in prod:

- `TOKTRAK_BASE_URL`
- `TOKTRAK_OIDC_DISCOVERY_URL`
- `TOKTRAK_OIDC_CLIENT_ID`
- `TOKTRAK_OIDC_CLIENT_SECRET`
- `TOKTRAK_ALLOWED_DOMAIN`
- `TOKTRAK_SESSION_SECRET`
- `TOKTRAK_TOKEN_PEPPER`
- `TOKTRAK_DATA_DIR`

Google Workspace discovery URL:

```text
https://accounts.google.com/.well-known/openid-configuration
```

Google Workspace is the primary OIDC provider for isp-insoft today, but the
project need not hard-code the URL; it only needs to work.

Scopes:

```text
openid email profile
```

User identity:

- Stable id is `issuer + subject`.
- Email and display name are mutable profile fields.
- Require `email_verified == true`.
- Parse the email address and compare its domain case-insensitively for exact
  equality with `TOKTRAK_ALLOWED_DOMAIN`; suffix matching is forbidden.
- When `hd` is present, it must also match the allowed domain exactly,
  case-insensitively.
- Non-company domains are rejected even if OAuth succeeds.

Dev auth:

- `TOKTRAK_DEV_AUTH=true` enables a fake local viewer.
- Dev auth relaxes OIDC requirements.
- Every page shows a minimal red dev-auth strip.
- Test corpus users remain visible; dev auth controls only current viewer.
- When a corpus is loaded, the fake viewer maps to its designated active user.

Sessions:

- Stateless signed cookie.
- 30-day lifetime, refreshed on activity while the user remains active.
- Every protected request checks the active-user projection.
- Self-deactivation immediately invalidates every session for that user; a
  rejected session cookie is cleared.
- Individual-session revocation is unavailable without session state.
- A successful OIDC re-login reactivates the user and issues a new cookie.

## Users

All logged-in users can view all data.

Users can:

- Create own tracker tokens.
- Revoke own tracker tokens.
- Self-deactivate.

No admin roles in v1.

Self-deactivation:

- Appends a user deactivation event.
- Revokes tracker tokens immediately.
- Projections tombstone identity as “Deleted user” while preserving historical
  aggregate usage.
- Re-login with the same provider subject reactivates the account.

## Avatar and color

Profile avatars:

- V1 uses initials avatars only.
- Provider avatar URLs are neither stored nor fetched.

User color:

- Assigned on first login and stored as an event.
- Derived from provider subject hash by walking a generated pastel OKLCH
  palette.
- Palette has about 256 candidates.
- Assignment avoids reserved theme/semantic colors and already-used user colors
  by distance.
- If users exceed palette capacity, reuse least-conflicting color.
- Colors aid scanning; they are not identity guarantees.

## Tracker tokens

Token model:

- Many active tokens per user.
- Each token has an immutable optional label set at creation.
- UI proposes default label: `Tracker token YYYY-MM-DD HH:mm`.
- Plaintext token is shown once, with button for easy copy and paste.
- Tokens contain 256 random bits and are shown as base64url.
- Only `HMAC-SHA-256(TOKTRAK_TOKEN_PEPPER, token)` is stored.
- Direct digest comparisons use `MessageDigest.isEqual`.
- Pepper is required and stable; changing it invalidates all tracker tokens.
- No pepper rotation in v1. Pepper can be rotated manually. That will invalidate
  all tokens, which is fine.

Token list shows:

- label
- createdAt
- lastUsedAt derived from upload events
- revoked status/action

Upload events store token id, not token hash.

In-memory token projection maps hash to token id/user id/status.

## Event store

TokTrak has one append-only event log:

```text
${TOKTRAK_DATA_DIR}/events.ndjson
```

Before reading the log or binding HTTP, the process exclusively locks
`${TOKTRAK_DATA_DIR}/toktrak.lock` and holds that file lock for its lifetime.
Lock failure aborts startup.

Format:

- UTF-8.
- One compact JSON object per line.
- Newline-terminated.
- One event envelope per line.
- Hard max line size: 10 MiB.

Envelope fields:

```json
{"id":"uuid-v4","at":"2026-07-10T00:00:00Z","type":"event-type","schemaVersion":1,"actor":"optional","data":{}}
```

Timestamps:

- `at` is server receipt time in UTC using `Instant.toString()`.
- ccusage usage periods remain inside payload data.
- Tracker passes `--timezone UTC`.
- Tracker includes client timezone metadata.

Event ids:

- Server-generated UUID v4.
- Sortability is not required because log order is source order.

Stored event families:

- user profile seen/updated
- user deactivated/reactivated
- token created/revoked
- usage uploaded
- projection snapshot
- FX rate updated

Raw upload payloads are source of truth.

Snapshots:

- Are cache events, not source of truth.
- Include `projectionVersion`.
- Startup reads latest compatible snapshot plus newer raw events.
- If projection version changes, recompute by appending new snapshot events.
- Never rewrite the event log in place except for torn-tail recovery.
- Snapshot event max is 5 MiB; if exceeded, skip snapshot and log.

Backups:

- Deployment is responsible for backing up the data volume.
- TokTrak provides no backup feature.

Corruption:

- Startup truncates only a final non-newline-terminated fragment to the last
  complete newline and logs the recovery.
- A malformed newline-terminated line causes loud startup failure.
- No complete event is silently skipped.

Future file I/O optimization:

- Out of v1.
- V1 uses simple append/fsync through the writer queue.
- Later research may evaluate memory-mapped windows or Java FFM.

## Writer queue and projections

Request flow:

1. Request enters virtual-thread handler.
2. Credential parsing, body parsing, and stateless validation happen outside the
   writer.
3. A parsed command is submitted to the bounded writer queue.
4. The writer performs all state-dependent authorization, normalization, and
   dedupe against the current projection.
5. The writer appends the resulting event, flushes, and fsyncs where required.
6. The projection consumes the appended event.
7. The request completes.
8. The SSE dirty flag is set.

Writer queue:

- Single consumer.
- Bounded to 1024 commands.
- Full queue returns 503.
- Tracker retries later.
- Mutation success is returned only after append, required fsync, and projection
  apply complete.
- Token mutations and uploads fsync every event.
- Snapshot writes may batch.
- Snapshot commands use the same queue.

SSE updates:

- Fired after write and projection apply.
- Dashboard updates are debounced about 3 seconds.
- Not every event needs immediate client update.

## Usage ingestion

Tracker upload endpoint:

```text
POST /api/usage
Authorization header carries the tracker bearer token.
```

Upload max body size: 5 MiB hard limit. No config and no splitting in v1.
Oversized upload returns 413 with a clear message.

Server derives user from token and ignores any claimed user.

Payload shape:

```json
{
  "trackerVersion": "...",
  "ccusageVersion": "20.0.17",
  "clientTimeZone": "Europe/Berlin",
  "full": true,
  "generatedAt": "2026-07-10T00:00:00Z",
  "reports": {
    "daily": {"ok": true, "json": {}},
    "session": {"ok": true, "json": {}},
    "blocks": {"ok": false, "error": "..."}
  }
}
```

ccusage version:

- Tracker pins ccusage version.
- Tracker sends ccusage version.
- Server stores raw data for all supported versions starting from current
  latest, 20.0.17.
- In 20.0.17 JSON, report array roots are `daily`, singular `session`, and
  `blocks`.
- New schema support must be additive.
- Unknown/new fields are accepted and stored.
- Normalization failures mark the payload; data can be ingested later.

Dedupe/idempotency:

- Every accepted upload is stored as an event; dedupe happens in projections.
- Report rows are snapshots, never additive deltas.
- `generatedAt` must be a valid instant no more than 24 hours after server
  receipt time.
- The newest `generatedAt` upserts daily rows by `(userId, period)`, session
  rows by `(userId, agent, period)`, and blocks by `(userId, id)`.
- Older uploads may fill absent keys but never replace newer rows.
- Equal `generatedAt` values resolve by event-log order.
- Missing rows never delete prior projected rows.
- A failed report leaves that report's prior projection unchanged.
- Initial installs and scheduled uploads may overlap or repeat full history
  without double counting.

Partial reports:

- If one ccusage command fails, tracker uploads successful reports plus
  per-report error metadata.
- Server stores partial payloads.
- Dashboard shows subtle ingestion health.

Report authority:

- Daily is canonical for costs, tokens, trends, model mix, and source mix.
- Session is canonical only for session/project detail.
- Blocks is canonical only for usage rhythm and block detail.
- Ccusage 20.0.17 blocks are aggregate and have no agent or per-model token
  attribution.
- Session and blocks data never contribute again to daily-derived totals.

Tracker report commands:

- Run unified daily report with as much detail as ccusage supports, including
  breakdown.
- Run unified session report.
- Run unified blocks report where supported.
- Monthly is derived server-side unless future ccusage data makes direct upload
  clearly better.
- Resolve the Pi sessions directory in this order: `PI_AGENT_DIR`,
  `PI_CODING_AGENT_SESSION_DIR`, `sessionDir` in
  `${PI_CODING_AGENT_DIR:-~/.pi/agent}/settings.json`, then
  `~/.pi/agent/sessions`.
- Set the resolved path as `PI_AGENT_DIR` only in the ccusage child process so
  unified reports include non-standard Pi storage.
- Never modify the user's system or shell environment.

## Tracker install and scheduling

The only supported installation path:

1. User logs into dashboard.
2. User creates a tracker token with optional label.
3. Server shows plaintext token once.
4. Server offers a one-time personalized `.mjs` installer download.
5. Installer embeds base URL, token, tracker version, and pinned ccusage
   version.
6. Dashboard shows OS-specific run instructions.

UX:

- After token creation, show three boxes:
  - Download installer.
  - Run command for detected OS expanded.
  - Token/installer warning.
- Other OS instructions are collapsed but available.
- Node install command is shown per OS.
- Browser OS detection selects the expanded instruction section.

Install paths:

- Windows: `%LocalAppData%\TokTrak`
- macOS: `~/Library/Application Support/TokTrak`
- Linux: `$XDG_DATA_HOME/toktrak` or `~/.local/share/toktrak`
- macOS/Linux create the directory as `0700` and installed script as `0600`;
  installation fails if those modes cannot be established.
- Windows relies on the inherited `%LocalAppData%` ACL and does not rewrite
  custom ACLs.

No marker/state file is needed for full-history tracking.

Modes:

- Installer mode installs/updates scheduled job and immediately runs full
  upload.
- `--once --full` sends full history.
- Scheduled `--daily` sends recent window.
- `--uninstall` removes scheduled job and installed script.

Scheduling:

- Linux: `systemd --user` only.
- macOS: user LaunchAgent only.
- Windows: user-scoped `schtasks`, only when user logged in.
- No cron fallback.
- No root or elevated installation.
- Installer refuses Unix root and elevated Windows execution; membership in the
  Windows Administrators group alone is allowed.
- Uninstall enforces the same rule and only works for the installing user.

Scheduled run behavior:

- Daily job runs at fixed scheduler time.
- Script sleeps random 0–6h before upload to spread load.
- One immediate retry with jitter on upload failure.
- Otherwise retry waits until next scheduled run.
- 401 logs and does not uninstall the task.

Initial vs scheduled uploads:

- Install immediately runs `--once --full`.
- Scheduled runs upload about last 7 days.
- Reinstall may repeat full upload; server dedupes.

Logs:

- Linux/macOS rely on native scheduler logs.
- Windows task redirects stdout/stderr to an app-data log file.

Self-update:

- Upload response includes latest tracker version and personalized update URL.
- Tracker sends its bearer token only in the update request `Authorization`
  header; tokens are forbidden in URLs and query parameters.
- Tracker downloads updated personalized script after successful upload.
- Update verifies SHA-256 from authenticated server response.
- Update writes temp file and atomic-renames over installed script.
- Update failure never blocks upload.
- Upload happens before update.

## Dashboard information architecture

Navigation:

- Overview
- Visualizations
- My Tracker

No raw data UI in v1.

Overview:

- Big monthly cost KPI.
- Big monthly token KPI.
- Active users KPI.
- Current USD→EUR rate KPI.
- Leaderboard sorted by current-month cost by default.
- Leaderboard period toggle: week, month, all-time.
- Leaderboard shows avatar/color/name/email.

Visualizations:

Fixed narrative scroll from simple to detailed:

1. Big totals and pulse.
2. Cost trend.
3. Token trend split by input/output/cache creation/cache read.
4. Calendar heatmap.
5. Usage rhythm by weekday/hour when data supports it.
6. Model mix.
7. Source split.
8. Cache efficiency.
9. User color streams over time.
10. Top models by cost/tokens.
11. Largest spike day/session/model shift callouts.
12. Session/project detail views.
13. Detailed tables last.

Tables are last, not first.

Session/project identifiers:

- Display raw/full identifiers to logged-in users.
- No cross-user project matching in v1.
- Future v2 may map different user paths/session labels to shared projects.

Chart tech:

- SVG for labeled/simple charts.
- Canvas for dense 2D fields when it best communicates the data.
- WebGL only if 3D has real meaning.
- No chart library v1.
- SVG/CSS transitions are preferred default.

Visual style:

- Simple, beautiful, brutalist.
- System dark/light auto theme.
- Two pastel accent colors.
- Semantic pastel colors for info/warn/error.
- Per-user pastel colors.
- Other colors derive from base variables.
- Respect reduced motion.
- Maintain contrast despite pastels.
- Responsive/mobile-ready: stacked cards and charts; tables may scroll
  horizontally.
- Modern Chromium/Firefox/Safari only.

## Currency conversion

- USD is source currency.
- Dashboard default is USD.
- User may toggle to EUR.
- Currency preference is stored in localStorage.
- FX rate source: Frankfurter API powered by ECB.
- Example current result on 2026-07-10: 1 USD = 0.87489 EUR.
- Validation source returned 0.874818, close enough.

FX behavior:

- FX is always on.
- Last-good rate is stored as `fx-rate-updated` event.
- Failures are logged, not stored as events.
- If no rate exists, EUR toggle is absent.
- If last-good exists and refresh fails, keep last-good.
- Lazy refresh on dashboard request when in-memory projection says rate is older
  than 24h.
- Use one atomic in-flight guard to avoid duplicate fetches.
- Fetch result appends rate event through writer queue.

## Dev/test corpus

The committed manual-testing corpus is `tests/corpus/dev.jsonl`.

Corpus content:

- It is a plain event log, not a separate import format or compressed file.
- It contains complete ccusage history from all detected agents.
- Daily data remains canonical; session and blocks data provide only their
  designated detail views.
- Five realistic synthetic users with `.invalid` emails receive randomly
  assigned whole sessions.
- Session-derived weights partition daily and blocks values while preserving
  canonical totals exactly.
- Names, emails, user ids, token ids, session ids, project names, paths, and
  other personal identifiers are replaced.
- Agent names, model names, timestamps, token counts, and costs are preserved.
- One active synthetic user's provider subject is the fixed dev-auth viewer
  subject.
- Mixed state includes healthy uploads, one partial upload, one revoked token,
  and one deleted user.

Corpus creation:

- Run pinned unified ccusage daily, session, and blocks reports once.
- Resolve non-standard Pi storage and set `PI_AGENT_DIR` only for those child
  processes.
- A temporary Node transformer outside the repository reads only whitelisted
  report fields, sanitizes identifiers, partitions data, and writes the event
  log.
- Scan the result for local usernames, emails, home/workspace paths, original
  project/session identifiers, and unexpected fields before copying it into the
  repository.
- Delete all temporary raw reports and transformer files.
- Commit only `tests/corpus/dev.jsonl`; future additions directly edit it.

Dev behavior:

```text
mise run dev --corpus tests/corpus/dev.jsonl
mise run dev --corpus tests/corpus/dev.jsonl --fail-writes
```

- Dev startup copies the corpus into disposable data under `output/`; it never
  opens the committed fixture for append.
- The dev `Clock` is pinned to the corpus's latest usage timestamp so current
  period views remain populated without changing event timestamps.
- `--fail-writes` injects writer failure and enters degraded mode immediately
  after HTTP binding.
- Corpus loading and fault injection are accepted only in dev mode.
- The same corpus serves healthy and degraded manual testing.
- Integration tests may reuse the corpus when runtime remains fast.
- Release container images exclude the corpus.

## Container and build

Build tooling:

- `mise` pins latest Java, currently 26.
- Project tracks latest Java over time.

Runtime images:

- Dev jlink runtime excludes the app module for fast recompiles.
- Prod jlink runtime includes the app module in the image.
- No classpath runtime.
- Smart layer caching: build/link runtime separately from frequently changing
  app code.

Container:

- Minimal jlink runtime plus app.
- Image may run as root.
- Docs recommend rootless Podman deployment; root in rootless Podman maps to
  current user.
- Docker users are on their own.
- Data dir is mounted as volume.

Release:

- Version numbers monotonically increase from `0`.
- `mise run release` checks changelog, tags, builds, tests, builds container,
  pushes registry image.

## README updates required

Replace “cron” wording with “user-scoped OS scheduler”.

Deployment section documents all required env vars with short examples:

- `TOKTRAK_BASE_URL=https://toktrak.isp-insoft.de`
- `TOKTRAK_PORT=8080`
- `TOKTRAK_DATA_DIR=/data/toktrak`
- `TOKTRAK_OIDC_DISCOVERY_URL=https://accounts.google.com/.well-known/openid-configuration`
- `TOKTRAK_OIDC_CLIENT_ID=...`
- `TOKTRAK_OIDC_CLIENT_SECRET=...`
- `TOKTRAK_ALLOWED_DOMAIN=isp-insoft.de`
- `TOKTRAK_SESSION_SECRET=...`
- `TOKTRAK_TOKEN_PEPPER=...`
- `TOKTRAK_DEV_AUTH=true` only for local dev

Cross-platform secret generation example:

```sh
node -e "console.log(require('node:crypto').randomBytes(32).toString('base64'))"
```

Docs must state:

- Session secret signs cookies.
- Token pepper hashes tracker tokens and must remain stable.
- Changing token pepper invalidates all tracker tokens.
- Deployment owns volume backup.
- TLS/compression/proxy configuration is out of app scope.

## Tests

Test layers:

- Event parsing, envelope validation, and 10 MiB line limit.
- Data-directory lock rejects a second process.
- Torn-tail truncation and hard failure for malformed complete lines.
- Projection rebuild from raw events and snapshots.
- Snapshot version invalidation/recompute by append.
- HMAC token lookup, constant-time digest comparison, and revocation.
- Production jlink build rejects automatic modules.
- Upload dedupe/idempotency, including concurrent duplicates, stale uploads, and
  future `generatedAt` rejection.
- Mutation responses wait for append, required fsync, and projection apply.
- Unknown ccusage schema fields are preserved.
- Daily/session/blocks authority prevents double counting.
- Pi path resolution and child-only `PI_AGENT_DIR` override.
- Corpus parsing, dev-viewer mapping, pinned dev clock, and immediate
  `--fail-writes` degradation.
- Oversize upload returns 413.
- Writer queue full returns 503.
- `/health` success/failure.
- Fake OIDC provider with JDK `HttpServer`, no mock library.
- OIDC state, nonce, PKCE, signature, issuer, audience, authorized-party,
  expiry, verified email, and exact domain validation.
- Deactivation invalidates all sessions; OIDC re-login reactivates.
- Dev auth path.
- Tracker script with fake ccusage command and fake server, Node stdlib only.
- Tracker update authentication uses headers and never URLs.
- Tracker install permissions on macOS/Linux.
- No podman required for tests.

## Explicit v1 exclusions

- Billing or performance-review workflow.
- Admin roles.
- Public import/export APIs.
- Raw-data browser UI.
- Dashboard customization.
- Cross-user project/session matching.
- Database.
- Event log rewrite/compaction beyond torn-tail recovery.
- Configurable upload max.
- Request protocol upgrades beyond HTTP/1.1.
- Automatic root/system install.
- Any tracking beyond ccusage output.
