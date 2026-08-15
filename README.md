# TokTrak

[![TokTrak — Internal Coding Harness Token Tracker][toktrak-logo]][toktrak]

[toktrak-logo]: sources/toktrak/assets/public/logo-lockup-dark.svg
[toktrak]: https://toktrak.isp-insoft.de

## Why does it exist

At the current point in time (2026) AI coding subscriptions (e.g. Claude Max or
ChatGPT Pro) are heavily subsidized by the providers (~20-40x) for reasons not
important here. However, as users of AI coding tools, we find it important to
gain an understanding of "actual" costs of using these. This project aims to
develop and provide a set of tools for teams, that allow tracking these
projected costs over time and visualize them via a dashboard.

## What is it

A small webservice, that allows tracking and visualizing coding harness usage
data. It provides a dashboard for visualizing usage data and allows generating
tracking tokens for individual users. Users can install the tracker component on
their machine to send usage data to the server.

### Workstation client tracker

The tracker component is installed on a developer machine as a recurring user
job that sends coding harness usage data to the server. This model assumes that
developers primarily interact with AI coding tools via a coding harness
installed on their machine, like Claude Code or Codex. We realize that this does
not track 100% of the usage in this space (Cloud, Web and CI usage are omitted),
but for the purposes of this project, it is enough.

The "tracker" is fairly primitive. It is one plain `.mjs` script that installs a
user-scoped OS scheduler entry and uploads usage data daily. It uses Node's
standard library directly: no `package.json`, npm dependencies, transpilation,
bundling, or JavaScript build system. Its sole external JavaScript execution is
`npx ccusage@<pinned-version>`. It depends on NodeJS and the native user
scheduler on each OS.

| OS  | scheduler      | install NodeJS               |
| --- | -------------- | ---------------------------- |
| win | schtasks       | winget install OpenJS.NodeJS |
| mac | LaunchAgent    | brew install node            |
| lnx | systemd --user | figure it out                |

### TokTrak server and dashboard UI

The server is a minimal, modern Java 26+ application with a datastar dashboard
server side rendered frontend, packaged up into a podman container. By default,
we deploy to our company-private registry at `registry.isp-insoft.de`.

Features:

- OAuth SSO support (we use Google Workspace)
- Tracking usage data per user
- Users can generate tracking token
- Visualise usage data:
  - leader board
  - graphs over time
  - sum totals
  - averages (daily, monthly, yearly)
- Toggle to estimate prices in EUR (data is in USD)
- system dark/light theme auto applies
- dashboard updates live when server sends fresh data

## Development

### How do I set up the project?

Install [mise](https://mise.jdx.dev/), clone the repository, then run
`mise install` and `mise run check`. Mise pins the toolchain; tasks download
Java dependencies automatically.

### How do I format, build, and test changes?

- `mise run fmt [paths...]` formats Markdown and Java.
- `mise run check` compiles and lints everything.
- `mise run test [test paths...]` runs all or selected tests.
- `mise run verify` runs the complete read-only local gate.
- `mise run clean` removes generated output.

### How do I run TokTrak locally?

Run `mise run dev` for the seeded, auto-reloading development server. Use
`mise run dev --fail-writes` to exercise degraded health and failed writes. Stop
the server before running other build tasks; `fmt` remains available while it
runs.

### How do I build the production runtime?

Run `mise run prod`. It builds and verifies the self-contained runtime under
`output/runtimes/prod`.

### How do I reproduce CI locally?

Run `mise run ci`, then `mise run coverage`. `ci` applies Refaster, requires its
result to leave the tree clean, and runs verification.

GitHub runs CI and coverage for pull requests, production-runtime verification
on `trunk`, and PIT weekly or on demand. Open a workflow run's summary first;
download `coverage-report` or `mutation-report` for the full HTML report.

### How do I add a unit test?

Add a JUnit test under `tests/toktrak.tests/toktrak/tests`. Name each test
`given_<camelCaseContext>_when_<camelCaseBehavior>_then_<camelCaseExpectation>`.
Run the narrow file while iterating, then `mise run verify`.

### How do I inspect coverage?

Run `mise run coverage`, then open `output/coverage/report/index.html`. The task
enforces global and package-specific instruction/branch floors; add meaningful
tests rather than weakening them.

### How do I run mutation tests?

After changing tests, run
`mise run pit --history -- <changed production source paths>` and inspect
`output/mutations/index.html`. If history is inconsistent, delete
`output/pit.history` and rerun without `--history`.

### How do I create or update a Selfie snapshot?

Use Selfie only for stable, reviewable HTML, JSON, text, or protocol output.
Create one with `toMatchDisk_TODO()` or update one by changing `toMatchDisk()`
to `toMatchDisk_TODO()`, then run its narrow test. Inspect the exact `.ss` diff
and map every changed fragment to the producing code before approval. Commit the
golden file and Selfie's rewritten Java; never commit update markers.

### Which environment variables does the current server read?

| Variable                     | Purpose                                                         |
| ---------------------------- | --------------------------------------------------------------- |
| `TOKTRAK_BASE_URL`           | Public server URL; required outside development.                |
| `TOKTRAK_DATA_DIR`           | Persistent event-log directory; required outside development.   |
| `TOKTRAK_DEV_AUTH`           | Enables local development behavior; never use in production.    |
| `TOKTRAK_PORT`               | HTTP listen port; defaults to `8080`, while `0` picks any port. |
| `TOKTRAK_OIDC_DISCOVERY_URL` | Production OIDC discovery endpoint.                             |
| `TOKTRAK_OIDC_CLIENT_ID`     | Production OIDC client ID.                                      |
| `TOKTRAK_OIDC_CLIENT_SECRET` | Production OIDC client secret.                                  |
| `TOKTRAK_ALLOWED_DOMAIN`     | Exact verified company email/hosted domain.                     |
| `TOKTRAK_SESSION_SECRET`     | Base64 cookie-signing secret, at least 32 bytes.                |
| `TOKTRAK_TOKEN_PEPPER`       | Stable Base64 tracker-token HMAC pepper, at least 32 bytes.     |

`mise run dev` sets development auth and uses the bundled anonymized corpus.
Production secrets belong in environment configuration, never source control.

### Where does code and generated output live?

Production code is under `sources`, tests under `tests`, build logic under
`tools`, and disposable generated files under `output`.

## How to install client tracker

Make sure NodeJS is installed on your machine.

Install flow:

1. Login to the TokTrak dashboard: <https://toktrak.isp-insoft.de>
2. Create a tracker token.
3. Download the generated installer script shown after token creation.
4. Run the shown command for your OS.

The installer contains your token. Do not share it. If you miss the one-time
download, revoke the token and create a new one.

The installer sets up a user-scoped daily job and immediately uploads existing
local usage data once.

## How to deploy server + dashboard

Use rootless Podman. The image runs as container UID `0`; rootless
user-namespace mapping keeps that user unprivileged on the host. Create a
user-owned volume and keep `/data` writable by the mapped container root.

```sh
podman volume create toktrak-data
node -e "console.log(require('node:crypto').randomBytes(32).toString('base64'))"
podman run -d --name toktrak --replace --restart=always \
  --env-file=$HOME/.config/toktrak/server.env \
  --volume=toktrak-data:/data \
  --publish=127.0.0.1:8080:8080 \
  registry.isp-insoft.de/toktrak:v0
```

Protect the env file as owner-readable only. It must contain:

```sh
TOKTRAK_BASE_URL=https://toktrak.isp-insoft.de
TOKTRAK_PORT=8080
TOKTRAK_DATA_DIR=/data
TOKTRAK_OIDC_DISCOVERY_URL=https://accounts.google.com/.well-known/openid-configuration
TOKTRAK_OIDC_CLIENT_ID=...
TOKTRAK_OIDC_CLIENT_SECRET=...
TOKTRAK_ALLOWED_DOMAIN=isp-insoft.de
TOKTRAK_SESSION_SECRET=...
TOKTRAK_TOKEN_PEPPER=...
```

Generate `TOKTRAK_SESSION_SECRET` and `TOKTRAK_TOKEN_PEPPER` separately.
Preserve the pepper: changing it invalidates every tracker token. Never set
`TOKTRAK_DEV_AUTH` in production.

The reverse proxy owns public TLS and compression; TokTrak serves plain HTTP on
loopback port `8080`. Back up the complete volume while TokTrak is stopped, and
test restoration. Deployment owns backup retention and monitoring.

### Temporary JVM diagnostics

The linked runtime includes JMX, JFR, `jcmd`, and `jfr`; remote management stays
disabled unless explicitly configured. For a short diagnostic run, add this
single line to the protected env file and publish the same fixed port on host
loopback:

```sh
JDK_JAVA_OPTIONS=-Dcom.sun.management.jmxremote -Dcom.sun.management.jmxremote.port=9010 -Dcom.sun.management.jmxremote.rmi.port=9010 -Djava.rmi.server.hostname=127.0.0.1 -Dcom.sun.management.jmxremote.authenticate=false -Dcom.sun.management.jmxremote.ssl=false
podman run ... --publish=127.0.0.1:9010:9010 ...
```

Connect VisualVM or JMC to
`service:jmx:rmi:///jndi/rmi://127.0.0.1:9010/jmxrmi`. For a remote host, keep
Podman bound to loopback and first run `ssh -L 9010:127.0.0.1:9010 HOST`.
Unauthenticated JMX permits code execution: never publish it beyond loopback,
and remove the options immediately after diagnosis.

Shell-free diagnostics remain available directly:

```sh
podman exec toktrak /opt/toktrak/bin/jcmd 1 VM.version
podman exec toktrak /opt/toktrak/bin/jcmd 1 JFR.start name=toktrak settings=profile duration=60s filename=/data/toktrak.jfr
podman cp toktrak:/data/toktrak.jfr .
```

### Release

Versions are consecutive integers with matching Git/image tags: `v0`, `v1`, and
so on. Add the exact next `## vN` section to `CHANGELOG.md`, then use a clean
`trunk` synchronized with `origin/trunk`:

```sh
mise run release --dry-run
mise run release
```

Windows Podman defaults to its rootful connection. Use
`mise run release-windows --dry-run` and `mise run release-windows` instead.

The dry run performs verification, runtime/image builds, and a rootless
restart/persistence check without tags or remote writes. A release pushes only
`registry.isp-insoft.de/toktrak:vN`; no `latest` tag exists.

Upgrade by pulling the new immutable tag, stopping the old container, backing up
the volume, and replacing the container with the new tag. Roll back by replacing
it with the previous `vN` tag against the same restored compatible volume.

If image or Git-tag push fails, retain the local candidate tag and image, fix
auth/networking, and rerun `mise run release`; recovery republishes that exact
candidate. If either local artifact was removed, stop and inspect registry/Git
state before retrying.

## How to use

Once the tracker is installed, no further interactions are needed, other than
checking the dashboard from time to time.
