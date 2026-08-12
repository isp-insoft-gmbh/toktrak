---
id: TT-SPEC-IXJLYK_K
type: spec
title: TokTrak v1
---

## Intent

Provide internal developers shared visibility into projected AI coding-harness
usage costs. TokTrak is for awareness and exploration, never billing,
performance review, or prompt/code surveillance.

All authenticated company users may see all users' usage. Data is limited to
`ccusage` output; prompts, code, and file contents are forbidden. The dashboard
is fixed rather than user-configurable and links to a data-scope explanation.

## Product

TokTrak consists of:

1. A small server-rendered dashboard and authenticated API.
2. One personalized cross-platform Node `.mjs` installer/tracker.

The server remains a minimal JPMS Java service built into a linked runtime. It
uses JDK HTTP/concurrency facilities, explicit jlink-safe JSON/OIDC
dependencies, strict browser security, and an append-only event log. No
application framework, database, or classpath runtime is allowed.

The tracker uses Node's standard library, a pinned `ccusage`, and native
user-scoped schedulers. It never requires root/admin access.

## Durable behavior

- Acquire an exclusive data lock before replay or HTTP binding.
- Keep one newline-delimited UTF-8 event log as source of truth.
- Stream replay into versioned projections; snapshots are disposable caches.
- Recover only a final torn fragment; reject malformed complete events.
- Serialize mutations through one bounded writer.
- A mutation succeeds only after append, required fsync, and projection apply.
- Preserve raw uploads and unknown schema fields.
- Startup integrity failures fail before binding.
- Runtime write failure enters read-only degraded mode; projection corruption
  hard-stops.
- `/health` is healthy only while writable.

API errors carry stable code, public message, and request ID. Browser errors are
safe HTML. Production responses and logs never disclose secrets, token material,
emails, or exception diagnostics.

## Identity

Production auth uses OIDC authorization code with PKCE, exact verified company
domain checks, signed transaction/session cookies, CSRF protection, and an
active-user check on every protected request. Stable identity is issuer plus
subject.

Users may create/revoke their own labeled tracker tokens and deactivate
self-service accounts. Deactivation immediately invalidates sessions and tokens
while preserving historical aggregate usage; re-login reactivates identity. V1
has no admins or individual-session revocation.

Tokens contain 256 random bits and are shown once. Store only a peppered HMAC;
compare digests in constant time. Credentials travel only in authorization
headers.

Local dev auth uses a visible environment banner and may map to a designated
corpus viewer. It is forbidden in production.

## Usage

`POST /api/usage` derives ownership from its bearer tracker token. Uploads carry
tracker/ccusage versions, client timezone, generation time, full/partial state,
and daily/session/blocks report results.

Reports are snapshots, never additive deltas:

- daily controls costs, tokens, trends, model/source mix;
- session controls session/project detail only;
- blocks controls rhythm and block detail only.

Newest generation time replaces matching rows. Older uploads may fill missing
keys but never overwrite newer rows; equal times resolve by event order.
Missing/failed reports preserve prior rows. Repeated overlapping history must
not double-count. Reject generation times more than 24 hours ahead.

USD is canonical. EUR is an optional UI projection using a persisted last-good
Frankfurter/ECB rate; failed refresh retains last-good state.

## Tracker

Install locations and schedulers are user-scoped:

- Windows LocalAppData with `schtasks`;
- macOS Application Support with a LaunchAgent;
- Linux XDG data with `systemd --user`.

Install performs one full upload. Daily runs cover about seven days after
bounded jitter and retry once. Partial `ccusage` success is uploaded with error
metadata. Pi session discovery honors explicit environment/configured paths
without changing the user's environment.

After a successful upload, self-update may fetch a personalized script using the
bearer header, verify SHA-256, and atomically replace the installed file. Update
failure never reverses upload success. Uninstall removes only the installing
user's scheduler entry and script.

## Dashboard

Navigation is Overview, Visualizations, and My Tracker. Overview contains
monthly cost/tokens, active users, FX, and period leaderboards. Visualizations
progress from totals/trends through token types, heatmap/rhythm, models,
sources, cache, user streams, spikes, session/project details, then tables.

Use responsive, accessible, brutalist light/dark presentation with reduced
motion, initials avatars, and semantic/per-user pastel colors. Use simple SVG or
canvas, not a chart library. Dashboard updates use safe patch/signals SSE only.

## Deployment

Serve plain HTTP behind a TLS/compression proxy. Package a minimal linked
runtime and app; mount and back up the data directory. Recommend rootless
Podman. Production assets are verified module resources with exact fingerprinted
URLs. Release versions are increasing integers from `0` and external mutation
remains explicit.

## Acceptance

- Restart/replay, locking, torn-tail, corruption, fsync, queue saturation, and
  degraded-mode tests pass.
- OIDC rejection paths, cookie/CSRF behavior, deactivation/reactivation, token
  lifecycle, and authorization tests pass.
- Repeated/concurrent/stale/partial uploads preserve canonical totals.
- Dashboard routes, SSE, CSP, accessibility, responsive structure, and corpus
  totals pass automated and human checks.
- Installer full/daily/retry/update/revoke/uninstall flows pass against fake
  services and native scheduler command fixtures.
- A clean checkout verifies and builds the assertion-enabled linked production
  runtime without automatic modules.

## Exclusions

No billing/performance workflow, admin role, public import/export API, raw-data
browser, dashboard customization, cross-user project matching, database, log
compaction, configurable upload ceiling, protocol upgrade, root/system install,
cron fallback, or tracking beyond `ccusage`.
