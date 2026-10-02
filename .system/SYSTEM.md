# SYSTEM

## Identity

TokTrak is the internal coding-harness token tracker for isp-insoft GmbH. It
helps teams understand projected AI coding subscription costs over time; it is
not an accounting system.

TokTrak intentionally observes only local coding-harness usage reported by
`ccusage`. Cloud, web, and CI usage are outside its view, so totals are useful
estimates rather than complete bills.

## Product

TokTrak is one small web service plus one workstation tracker.

The service authenticates company users through OAuth SSO, issues personal
tracker tokens, attributes usage per user, and presents team totals, averages,
leaderboards, trends, and live dashboard updates. USD is canonical; users may
view estimated EUR values. The UI follows the system light/dark theme.

The workstation tracker is one plain `.mjs` installer and uploader. It uses
Node's standard library directly: no package manifest, application dependencies,
transpilation, bundling, or JavaScript build system. Its only external
JavaScript execution is a pinned `npx ccusage@<version>`. Installation is
user-scoped and uses the native scheduler: `schtasks` on Windows, LaunchAgent on
macOS, and `systemd --user` on Linux.

## Server architecture

The server is a minimal modern Java 26+ JPMS application using JDK facilities
and server-rendered Datastar HTML. It is packaged as a self-contained linked
runtime and OCI container built, verified, and published only by CI. Production
deploys from the private company registry.

Runtime configuration comes from environment variables. Persistent data lives
under `TOKTRAK_DATA_DIR`. Production secrets never belong in source control.
`TOKTRAK_SESSION_SECRET` signs login cookies; `TOKTRAK_TOKEN_PEPPER` hashes
tracker tokens and must remain stable because changing it invalidates every
tracker token.

The application serves plain HTTP behind a reverse proxy. Deployment owns TLS,
compression, and data-volume backups. Rootless Podman is preferred.

## Repository

Production code lives under `sources`, tests under `tests`, build logic under
`tools`, and disposable generated artifacts under `output`. Mise is the central
version manager for development and CI; `tools/Build.java` remains the build
authority.
