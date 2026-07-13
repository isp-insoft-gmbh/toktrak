# TokTrak Roadmap

> **For agentic workers:** Use /skill:writing-plans to create one detailed implementation plan per phase. Start with Phase 1 and proceed sequentially unless the user explicitly changes the order.

**Goal:** Deliver a durable TokTrak server, verifiable dashboard, cross-platform tracker installer, and production release pipeline.

**Design Spec:** [`docs/super/specs/2026-07-10-toktrak-design.md`](../specs/2026-07-10-toktrak-design.md)

**Planning Strategy:** Split the oversized server slice into durable-core, identity, and usage-API phases, then preserve the requested human verification boundaries for server, dashboard, and client installer before final packaging.

---

## Phase 1: Durable Server Core

**Outcome:** A headless Java 26 service starts under dev auth, exposes health/error behavior, and durably records and rebuilds projections from its event log.

**Why now:** Every product capability depends on trustworthy persistence, serialization, request execution, and failure handling.

**Scope:**

- Java 26 JPMS build, pinned Jackson/Nimbus dependencies, JDK `HttpServer`, virtual-thread requests, scoped request context, and JSON logging.
- Environment validation, routing, API error envelopes, brutal fallback error HTML, and baseline browser security headers.
- Exclusive data lock, event envelopes, bounded writer queue, fsync acknowledgement, torn-tail recovery, snapshots, projection rebuild, and corruption handling.
- Healthy/degraded state machine, `/health`, dev auth, disposable dev data, pinned dev clock support, and immediate `--fail-writes` injection.
- Focused tests for persistence, concurrency, limits, restart behavior, jlink compatibility, and health transitions.

**Out of scope:**

- Production OIDC, users, tracker tokens, usage ingestion, FX, dashboard SSE, dashboard UI, and workstation tracker.
- Production container and release automation.

**Key files/areas likely affected:**

- `mise.toml`: Java toolchain and build/verify/dev tasks.
- `sources/`: JPMS server, configuration, HTTP, event-store, writer, projection, and health code.
- `tests/`: server-core and persistence tests.
- `vendored/` and `output/`: dependency resolution and linked runtimes.

**Dependencies:**

- Approved design spec.
- Java 26 and the validated explicit JPMS dependency versions.

**Verification:**

- Build and production-style jlink smoke test pass without automatic modules.
- Restart/replay, queue saturation, torn-tail, second-process lock, corruption, and fsync acknowledgement tests pass.
- Manual dev run shows healthy `/health`; `--fail-writes` shows degraded health after binding.

**Phase boundary health:** The repository produces a coherent, runnable infrastructure service with green tests; later phases add product behavior without replacing its persistence path.

**Risks:**

- Event-log durability mistakes can invalidate every later feature; keep one writer and prove crash/replay behavior before adding APIs.
- JDK `HttpServer` lifecycle details can leak threads or block shutdown; include lifecycle tests in this phase.

**Context notes:** Keep the core brutally small. Do not introduce framework abstractions or dashboard concerns.

## Phase 2: Identity and Tracker Tokens

**Outcome:** The headless server authenticates company users, maintains account lifecycle, and manages durable tracker tokens through protected HTTP routes.

**Why now:** Identity and authorization must be stable before usage ownership, dashboard access, or client credentials can be trusted.

**Scope:**

- Manual OIDC authorization-code flow with PKCE, transaction/session cookies, claim/domain validation, and CSRF.
- User profile events, colors, reactivation, account-wide deactivation, and active-user checks on protected requests.
- HMAC tracker-token creation, lookup, listing, labels, revocation, and last-used projection shape.
- Minimal login, callback, error, and token-management verification surfaces without dashboard styling.
- Fake-provider and security rejection-path tests.

**Out of scope:**

- Usage upload/normalization, FX, SSE, dashboard queries, visual UI, workstation tracker, and release packaging.

**Key files/areas likely affected:**

- `sources/`: OIDC/session security, user/token events and projections, authorization, and protected routes.
- `tests/`: fake OIDC provider, cookies, CSRF, claim/domain rejection, token lifecycle, and deactivation tests.

**Dependencies:**

- Phase 1 durable server core.
- Google-compatible OIDC behavior.

**Verification:**

- OIDC success/rejection, cookie, CSRF, reactivation/deactivation, HMAC lookup, revocation, and concurrent token mutation tests pass.
- Manual dev flow logs in as the fake viewer and exercises token creation/revocation across restart.

**Phase boundary health:** The service is a coherent authenticated credential-management backend with durable identity state; no usage route is exposed prematurely.

**Risks:**

- Manual OIDC is security-sensitive; test every rejection path and avoid permissive claim handling.
- Stateless sessions still require projection checks; keep authorization centralized on the shared request route.

**Context notes:** Do not add dashboard presentation or usage semantics. Preserve minimal verification HTML only.

## Phase 3: Usage, Queries, and Server Verification

**Outcome:** TokTrak is a complete headless backend that ingests ccusage snapshots, rebuilds accurate analytics, emits safe SSE updates, and maintains FX state.

**Why now:** Identity ownership is available, so usage and query contracts can be completed and accepted before visual or workstation clients depend on them.

**Scope:**

- Bounded usage upload, ccusage 20.0.17 report parsing, raw event storage, partial-report health, snapshot upserts, stale/future handling, and no-double-count projection rules.
- Daily/session/blocks authority, monthly derivation, leaderboard/query projections, and ingestion-health data.
- FX refresh events, last-good fallback, and hand-written safe Datastar SSE events.
- Protected API routes for projected dashboard data and server-level integration verification.
- Generate and sanitize `tests/corpus/dev.jsonl` only after the real reader and projections exist, then validate it by loading the dev server.

**Out of scope:**

- Overview/visualization UI, My Tracker UI, workstation installation, scheduler integration, self-update, and production image publication.

**Key files/areas likely affected:**

- `sources/`: ingestion, ccusage normalization, analytics/query projections, FX, SSE, and protected APIs.
- `tests/`: upload integration, dedupe, schema, FX, SSE, degraded behavior, and corpus replay tests.
- `tests/corpus/dev.jsonl`: sanitized mixed-state manual-testing corpus generated after implementation.

**Dependencies:**

- Phase 2 identity and tracker-token contracts.
- Ccusage 20.0.17 schemas.

**Verification:**

- Concurrent/repeated/stale uploads, partial reports, schema preservation, FX fallback, SSE, and degraded-mode tests pass.
- Corpus replay produces five synthetic users, exact canonical totals, mixed lifecycle state, and a mapped dev viewer.
- Human server checkpoint: authenticate, create/revoke a token, upload reports, restart, inspect projected API data, and force degraded mode.

**Phase boundary health:** The backend is independently usable and fully tested through HTTP; dashboard and tracker can rely on accepted routes and data semantics.

**Risks:**

- Ccusage reports overlap and evolve; preserve raw payloads and keep normalization versioned and additive.
- Query projections can drift from canonical daily totals; assert corpus totals through every query surface.

**Context notes:** This is the server verification milestone. Do not begin visual dashboard work until its contracts are accepted.

## Phase 4: Dashboard and Visualizations

**Outcome:** Logged-in users can explore projected team usage through the complete Overview and Visualizations experience using the real server and corpus.

**Why now:** Stable API/projection contracts and realistic corpus data allow visual work to be verified without tracker-installation dependencies.

**Scope:**

- Server-rendered navigation, login/error/data-scope pages, Overview KPIs, period leaderboard, ingestion-health warning, and USD/EUR preference.
- Fixed narrative visualizations, including trends, token categories, heatmap, rhythm, model/source mix, cache efficiency, user streams, spikes, details, and final tables.
- Vendored Datastar browser JS, safe patch/signals SSE updates, SVG/canvas rendering, CSS transitions, and no chart library.
- Brutalist responsive theme, initials avatars, user colors, contrast, reduced motion, keyboard/accessibility behavior, and mobile/table overflow.
- Dev-corpus manual workflow for healthy and degraded dashboard states.

**Out of scope:**

- My Tracker token/installer workflow.
- Workstation script, native schedulers, self-update, and production release automation.

**Key files/areas likely affected:**

- `sources/`: SSR pages, dashboard queries/views, SSE fragments, static asset serving, and browser preference handling.
- `tests/`: HTML/security/accessibility assertions and dashboard route/SSE integration tests.
- `tests/corpus/dev.jsonl`: manual visual verification input, changed only when a real UI need is proven.

**Dependencies:**

- Phase 3 backend contracts and validated corpus.

**Verification:**

- Automated SSR, CSP, authorization, SSE, reduced-motion, and responsive-structure checks pass.
- Human dashboard checkpoint covers dark/light themes, desktop/mobile widths, currency toggle, every visualization, tables, healthy state, and degraded warning.

**Phase boundary health:** TokTrak is a coherent read-only analytics product; omission of My Tracker is explicit and does not compromise existing dashboard behavior.

**Risks:**

- Hand-built charts can consume excessive scope; implement only the fixed narratives and prefer SVG/simple tables over custom engines.
- Dense data may expose projection mistakes; compare visible totals against corpus assertions.

**Context notes:** Keep My Tracker absent from navigation until Phase 5 rather than shipping a dead or placeholder workflow.

## Phase 5: My Tracker and Workstation Installer

**Outcome:** A user can create a token, download one personalized Node installer, install a user-scoped daily tracker on Windows/macOS/Linux, upload usage, self-update, and uninstall.

**Why now:** The client now targets accepted server contracts and can be verified end-to-end through the finished dashboard.

**Scope:**

- My Tracker navigation/page, token creation/revocation, one-time plaintext display, personalized download, OS detection, instructions, and warnings.
- Single Node `.mjs` installer/tracker with full/daily/uninstall modes, pinned ccusage, unified reports, Pi path resolution, partial-report upload, retry, logs, and authenticated self-update.
- User-scoped systemd timer/service, LaunchAgent, and `schtasks` integration without root or elevation.
- POSIX permissions, Windows LocalAppData behavior, jitter, atomic replacement, update hash verification, and token-only authorization headers.
- Fake ccusage/server tests and native scheduler command-generation tests without requiring Podman.

**Out of scope:**

- Root/system installation, cron fallback, configurable upload limits, additional tracker files, or new data sources beyond ccusage.
- Registry release publication.

**Key files/areas likely affected:**

- `sources/`: My Tracker UI, personalized script rendering, tracker download/update routes, and upload response metadata.
- `tests/`: Node tracker harness, fake ccusage/server, installer modes, permissions, update, and scheduler behavior.
- `README.md`: final end-user installation instructions.

**Dependencies:**

- Phase 3 server API and token/update contracts.
- Phase 4 dashboard shell and navigation.
- Node and native user scheduler behavior on each supported OS.

**Verification:**

- Automated install/full upload/daily upload/partial report/retry/update/revoke/uninstall flows pass against fake services.
- Human client checkpoint runs the shown command on each available OS, verifies scheduled execution and logs, confirms dashboard updates, then uninstalls cleanly.

**Phase boundary health:** The full product workflow is usable end-to-end; each OS path is user-scoped and failures leave server data and prior client versions intact.

**Risks:**

- Native scheduler quoting and environment inheritance differ sharply; generate argv/config structurally and test exact output per OS.
- Self-update touches executing code containing credentials; preserve permissions and replace atomically only after verified download.

**Context notes:** One script remains the ceiling. Do not introduce a separate config/state file unless implementation proves it unavoidable and the spec is revised.

## Phase 6: Production Packaging and Release

**Outcome:** The accepted product builds reproducibly into a minimal runtime/container and releases through one verified mise task with complete deployment documentation.

**Why now:** Packaging the final application avoids freezing incomplete module/runtime layouts and provides the final deployment verification slice.

**Scope:**

- Dev/test/prod jlink runtimes, production app module inclusion, smart container layers, minimal image, and mounted data directory.
- Rootless Podman guidance, required environment documentation, secret generation, backup ownership, TLS/proxy boundaries, and corpus exclusion.
- Integer versioning from `0`, changelog enforcement, tests/build/container checks, tagging, and private-registry push through `mise run release`.
- Final README consistency and end-to-end release smoke verification.

**Out of scope:**

- Docker-specific support, application TLS/compression, backups, database migration, public APIs, or protocol upgrades.

**Key files/areas likely affected:**

- `mise.toml`: production build, verify, image, and release orchestration.
- Container definition and runtime-link inputs under project build areas.
- `README.md` and changelog/release metadata.
- `output/`: generated runtimes and images only.

**Dependencies:**

- Phases 1–5 complete and accepted.
- Private registry credentials available outside the repository.

**Verification:**

- Clean checkout can install tools, verify, build all runtimes, and build the container without corpus inclusion.
- Rootless container starts against a mounted temporary data directory and passes health/login smoke checks behind a test proxy boundary.
- Release dry-run proves ordering without mutating tags or registry state; real release remains an explicit human action.

**Phase boundary health:** The repository is production-ready, documented, reproducible, and expected-green; release mutation remains deliberate and auditable.

**Risks:**

- Release automation can partially mutate external state; validate everything before tagging or pushing.
- Runtime-module drift can break jlink late; retain the Phase 1 automatic-module rejection in every build.

**Context notes:** Keep deployment concerns outside application code and preserve the rootless-Podman recommendation without adding Docker support.
