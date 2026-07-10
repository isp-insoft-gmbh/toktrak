# TokTrak design

Date: 2026-07-10
Status: draft for review

## Goal

TokTrak tracks projected AI coding harness usage cost for an internal team.

It is for cost awareness and exploration, not billing, performance review, or prompt/code surveillance.

## Product stance

- Users are developers.
- All logged-in company users can see all user usage data.
- Data scope is `ccusage` output only: no prompts, no code, no file contents.
- The footer links to a subtle data-scope page explaining this.
- The dashboard supports many visualizations so users can find their own use cases.
- The UI is fixed, not customizable.

## High-level architecture

TokTrak has two parts:

1. Server/dashboard
   - Java 26 application.
   - JDK `HttpServer`, HTTP/1.1.
   - Server-side rendered HTML.
   - Datastar JS for reactive dashboard updates.
   - Datastar SSE events sent through the Java SDK only if jlink-safe; current decision is to hand-write the tiny patch-elements/signals SSE subset because `datastar-java-sdk-core` is an automatic module and jlink rejects it.
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

- Jackson core/databind/annotations, explicit.
- Nimbus JOSE JWT, explicit.
- Datastar browser JS, vendored/pinned.
- JUnit/test deps only for tests.

Rejected dependencies:

- Spring, Netty, servlet/JAX-RS stacks.
- SQLite or any DB.
- Full OIDC SDKs that introduce automatic modules incompatible with jlink.
- Datastar Java SDK core unless it becomes a real JPMS module.

## Runtime model

- Requests run on virtual threads.
- A single writer queue serializes event-log appends.
- Long-lived writer worker uses one platform thread.
- Background async work, such as FX/avatar fetches, uses virtual threads.
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

Runtime degraded mode starts only after successful startup when event appending fails.

Degraded mode:

- Dashboard remains read-only if projections are already in memory.
- Warning is visible.
- Uploads and mutations return 503.
- Login may still work if projections exist.
- Token creation/revocation is disabled.

Projection corruption at runtime hard-stops because displayed data cannot be trusted.

`/health` returns `ok` only when service is writable and healthy. In degraded/failure states it returns failure with a terse reason category and no secrets.

## Logging

Use `java.util.logging` with a custom JSON `Formatter` writing compact JSON lines to stdout.

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

- One opaque auth cookie.
- Cookie is `HttpOnly` and `SameSite=Lax`.
- Cookie `Secure` is derived from `TOKTRAK_BASE_URL`: HTTPS means secure, HTTP means non-secure.
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
- Authorization redirect.
- Token exchange using JDK `HttpClient`.
- ID-token signature and claims validation with Nimbus JOSE JWT and provider JWKS.
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

Scopes:

```text
openid email profile
```

User identity:

- Stable id is `issuer + subject`.
- Email, display name, and avatar URL are mutable profile fields.
- Validate `hd == TOKTRAK_ALLOWED_DOMAIN` when present.
- Also validate email suffix as fallback.
- Non-company domains are rejected even if OAuth succeeds.

Dev auth:

- `TOKTRAK_DEV_AUTH=true` enables a fake local viewer.
- Dev auth relaxes OIDC requirements.
- Every page shows a minimal red dev-auth strip.
- Test corpus users remain visible; dev auth controls only current viewer.

Sessions:

- Stateless signed cookie.
- 30-day lifetime, refreshed on activity.
- No global instant logout without a session DB.
- Self-deactivation clears current browser cookie and lets other sessions expire naturally.

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
- Projections tombstone identity as “Deleted user” while preserving historical aggregate usage.
- Re-login with the same provider subject reactivates the account.

## Avatar and color

Profile avatars:

- Provider avatar URL is read from OIDC claims.
- Server fetches avatar asynchronously.
- Cached avatar files live under `${TOKTRAK_DATA_DIR}/cache/avatars`.
- Avatar cache is non-essential and safe to delete.
- Login/dashboard never blocks on avatar fetch.
- Initials avatar is fallback.

User color:

- Assigned on first login and stored as an event.
- Derived from provider subject hash by walking a generated pastel OKLCH palette.
- Palette has about 256 candidates.
- Assignment avoids reserved theme/semantic colors and already-used user colors by distance.
- If users exceed palette capacity, reuse least-conflicting color.
- Colors aid scanning; they are not identity guarantees.

## Tracker tokens

Token model:

- Many active tokens per user.
- Each token has an immutable optional label set at creation.
- UI proposes default label: `Tracker token YYYY-MM-DD HH:mm`.
- Plaintext token is shown once.
- Token hash only is stored.
- Token hash is SHA-256 over random 256-bit token plus `TOKTRAK_TOKEN_PEPPER`.
- Pepper is required and stable; changing it invalidates all tracker tokens.
- No pepper rotation in v1.

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

Format:

- UTF-8.
- One compact JSON object per line.
- Newline-terminated.
- One event envelope per line.
- Hard max line size: 5 MiB.

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
- Never rewrite the event log in place.
- Snapshot event max is 5 MiB; if exceeded, skip snapshot and log.

Backups:

- Deployment is responsible for backing up the data volume.
- TokTrak provides no backup feature.

Corruption:

- Bad log line causes loud startup failure.
- No silent skipping.

Future file I/O optimization:

- Out of v1.
- V1 uses simple append/fsync through the writer queue.
- Later research may evaluate memory-mapped windows or Java FFM.

## Writer queue and projections

Request flow:

1. Request enters virtual-thread handler.
2. Auth/token/request parsing happens outside the write lock.
3. Ingestion normalizes payload against immutable in-memory projection state.
4. Event is submitted to bounded writer queue.
5. Writer appends, flushes, fsyncs where needed.
6. Projection consumes appended event.
7. SSE dirty flag is set.

Writer queue:

- Single consumer.
- Bounded to 1024 events.
- Full queue returns 503.
- Tracker retries later.
- Token mutations and uploads fsync every event.
- Snapshot writes may batch.
- Snapshot events use the same queue.

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

Upload max body size: 5 MiB hard limit.
No config and no splitting in v1.
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
- Server stores raw data for all supported versions starting from current latest, 20.0.17.
- New schema support must be additive.
- Unknown/new fields are accepted and stored.
- Normalization failures mark the payload; data can be ingested later.

Dedupe/idempotency:

- Server dedupes and normalizes.
- Initial install may upload full history more than once.
- Scheduled uploads overlap recent days.
- Repeated data is harmless.

Partial reports:

- If one ccusage command fails, tracker uploads successful reports plus per-report error metadata.
- Server stores partial payloads.
- Dashboard shows subtle ingestion health.

Tracker report commands:

- Run daily report with as much detail as ccusage supports, including breakdown.
- Run session report.
- Run blocks report where supported.
- Monthly is derived server-side unless future ccusage data makes direct upload clearly better.

## Tracker install and scheduling

The only supported installation path:

1. User logs into dashboard.
2. User creates a tracker token with optional label.
3. Server shows plaintext token once.
4. Server offers a one-time personalized `.mjs` installer download.
5. Installer embeds base URL, token, tracker version, and pinned ccusage version.
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

No marker/state file is needed for full-history tracking.

Modes:

- Installer mode installs/updates scheduled job and immediately runs full upload.
- `--once --full` sends full history.
- Scheduled `--daily` sends recent window.
- `--uninstall` removes scheduled job and installed script.

Scheduling:

- Linux: `systemd --user` only.
- macOS: user LaunchAgent only.
- Windows: user-scoped `schtasks`, only when user logged in.
- No cron fallback.
- No root/admin.
- Installer refuses root/admin execution.
- Uninstall also refuses root/admin and only works for same user.

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
- Update URL requires bearer tracker token.
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
- Responsive/mobile-ready: stacked cards and charts; tables may scroll horizontally.
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
- Lazy refresh on dashboard request when in-memory projection says rate is older than 24h.
- Use one atomic in-flight guard to avoid duplicate fetches.
- Fetch result appends rate event through writer queue.

## Dev/test corpus

- Test corpus is an event log file, not a separate import format.
- Use plain `.ndjson`, not gzip/zstd.
- Dev server can use a corpus event log as its data file.
- Release container image does not include the test corpus.

## Container and build

Build tooling:

- `mise` pins latest Java, currently 26.
- Project tracks latest Java over time.

Runtime images:

- Dev jlink runtime excludes the app module for fast recompiles.
- Prod jlink runtime includes the app module in the image.
- No classpath runtime.
- Smart layer caching: build/link runtime separately from frequently changing app code.

Container:

- Minimal jlink runtime plus app.
- Image may run as root.
- Docs recommend rootless Podman deployment; root in rootless Podman maps to current user.
- Docker users are on their own.
- Data dir is mounted as volume.

Release:

- Version numbers monotonically increase from `0`.
- `mise run release` checks changelog, tags, builds, tests, builds container, pushes registry image.

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

- Event parsing and envelope validation.
- Projection rebuild from raw events and snapshots.
- Snapshot version invalidation/recompute by append.
- Token hash lookup and revocation.
- Upload dedupe/idempotency.
- Unknown ccusage schema fields are preserved.
- Oversize upload returns 413.
- Writer queue full returns 503.
- `/health` success/failure.
- Fake OIDC provider with JDK `HttpServer`, no mock library.
- Dev auth path.
- Tracker script with fake ccusage command and fake server, Node stdlib only.
- No podman required for tests.

## Explicit v1 exclusions

- Billing or performance-review workflow.
- Admin roles.
- Public import/export APIs.
- Raw-data browser UI.
- Dashboard customization.
- Cross-user project/session matching.
- Database.
- Event log rewrite/compaction.
- Configurable upload max.
- Request protocol upgrades beyond HTTP/1.1.
- Automatic root/system install.
- Any tracking beyond ccusage output.
