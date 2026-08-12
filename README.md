# TokTrak

Internal Coding Harness Token Tracker for isp-insoft GmbH.

<https://toktrak.isp-insoft.de>

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

## How to build

The only dependency after cloning this repo is: `mise`. It handles running
commands and installing and pinning dev dependencies.

Install `mise`:

win: `winget install jdx.mise`

mac: `brew install mise`

lnx: `lol lol lol`

Tasks resolve dependencies automatically.

Format Java sources: `mise run fmt [paths...]`

Quick formatting, compile, and lint check: `mise run check`

Run tests: `mise run test [test paths...]`

Generate JaCoCo coverage reports and enforce global 80% instruction / 65% branch
plus package-specific gates: `mise run coverage`; open
`output/coverage/report/index.html`. Package gates: health 80/75, HTTP 80/65,
JSON 70/50, logging 90/75, projection 80/70, storage 75/60.

Create or update a Selfie snapshot with `_TODO`, run its narrow test, then
inspect and commit both the Java rewrite and generated `.ss` file. CI runs
Selfie read-only.

Apply Refaster rules and format changed Java: `mise run refactor`

Full read-only check and test suite: `mise run verify`

CI refactor/clean-tree gate followed by verification: `mise run ci`

Mutation reports: `mise run pit`; open `output/mutations/index.html`

Hosted CI attaches downloadable JaCoCo and PIT HTML reports to their workflow
runs.

Start the seeded, auto-reloading dev server: `mise run dev`

Build the production runtime: `mise run prod`

Clean generated modules, dependencies, argument files, and runtimes:
`mise run clean`

We ship an anonymized corpus of test usage data for easy manual testing.

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

Version numbers are monotonically increasing integers starting from `0`.

1. Make sure `CHANGELOG.md` is up to date
2. Run `mise run release`

`release` bumps version, checks changelog, creates a tag, builds, tests, builds
container and pushes it to registry.

Runtime config is via env vars:

```sh
TOKTRAK_BASE_URL=https://toktrak.isp-insoft.de
TOKTRAK_PORT=8080
TOKTRAK_DATA_DIR=/data/toktrak
TOKTRAK_OIDC_DISCOVERY_URL=https://accounts.google.com/.well-known/openid-configuration
TOKTRAK_OIDC_CLIENT_ID=...
TOKTRAK_OIDC_CLIENT_SECRET=...
TOKTRAK_ALLOWED_DOMAIN=isp-insoft.de
TOKTRAK_SESSION_SECRET=...
TOKTRAK_TOKEN_PEPPER=...
```

Generate secrets with NodeJS:

```sh
node -e "console.log(require('node:crypto').randomBytes(32).toString('base64'))"
```

`TOKTRAK_SESSION_SECRET` signs login cookies. `TOKTRAK_TOKEN_PEPPER` hashes
tracker tokens and must stay stable; changing it invalidates all tracker tokens.

Deploy behind a reverse proxy for TLS/compression. Back up the mounted
`TOKTRAK_DATA_DIR` volume. Rootless Podman is recommended.

## How to use

Once the tracker is installed, no further interactions are needed, other than
checking the dashboard from time to time.

## Project setup

top level command runner and dev dependencies: `mise`

vendored java dependencies: `vendored`

java dependency resolver: `jresolve` downloads modular dependencies into
`output/deps/{main,test}`, compiler plugins into `output/deps/build`, and the
isolated shaded Refaster compiler into `output/deps/refaster`

source code: `sources`

tests: `tests` via `junit6`

build output: `output`

java modules: `output/modules`

generated Java tool argument files: `output/args`

Refaster rule source: `tools/refaster/Rules.java`; generated rules/classes:
`output/refaster`

runtimes via `jlink`: `output/runtimes/{test,dev,prod}`; test/dev runtimes
exclude `toktrak` and are reused until dependency/JDK inputs change

compiling: `javac` via collection of java @arg files

linting: `javac -Xlint:all` and 'errorprone'

formatting: `google-java-format`
