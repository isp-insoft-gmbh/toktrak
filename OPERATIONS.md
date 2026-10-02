# Operations

TokTrak serves plain HTTP behind a reverse proxy. This document states the
TokTrak runtime contract and recommends one deployment shape. Site operators own
TLS, compression, authentication secrets, persistent-volume backups, monitoring,
and rollback policy.

![TokTrak production shape](.system/operations.svg)

## Requirements

- An OCI-compatible runtime on the deployment host; the example below uses
  rootless Podman.
- Node.js for the shown secret-generation command.
- Credentials for the configured OCI image repository.
- A reverse proxy with public TLS.
- Outbound HTTPS to `api.frankfurter.dev` for EUR exchange-rate refresh; USD
  dashboards continue without it.
- A durable volume writable by mapped container root and covered by the
  operator's backup policy.

The image runs as container UID `0`; rootless user-namespace mapping keeps that
user unprivileged on the host.

## Image repository

Set the GitHub Actions variable `TOKTRAK_IMAGE_REPOSITORY` to the private OCI
repository. Set the GitHub Actions secrets `TOKTRAK_REGISTRY_USERNAME` and
`TOKTRAK_REGISTRY_PASSWORD` to registry credentials scoped to that repository.
The CI runner must reach the private registry; this is not assumed for
GitHub-hosted runners. The `ubuntu-26.04` runner provides Podman, but its
package version is not pinned by Mise; validate runner behavior before trusting
release publication. Keep `:vN` immutable and permit `:latest` to move for
nightly deployments and rollback. Protect Git `v*` tags against update/deletion
and restrict their creation to authorized releasers. Protect release workflow
changes on `trunk`; the tagged commit supplies CI workflow code and must be
trusted.

## Configuration

Store production values in an owner-readable env file:

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

Generate the two Base64 secrets separately:

```sh
node -e "console.log(require('node:crypto').randomBytes(32).toString('base64'))"
```

`TOKTRAK_BASE_URL` is the public external URL used for redirects and tracker
downloads; production accepts HTTPS, plus local HTTP for verification only.
`TOKTRAK_SESSION_SECRET` signs login cookies. `TOKTRAK_TOKEN_PEPPER` hashes
tracker tokens and must remain stable; changing it invalidates every tracker
token. Never set `TOKTRAK_DEV_AUTH` in production.

## Deploy

A simple recommended deployment uses the current immutable release tag:

```sh
podman volume create toktrak-data
podman run -d --name toktrak --replace --restart=always \
  --env-file=$HOME/.config/toktrak/server.env \
  --volume=toktrak-data:/data \
  --publish=127.0.0.1:8080:8080 \
  registry.example.com/team/toktrak:v2
```

Proxy public HTTPS to host loopback port `8080`. Do not expose the container
port publicly.

## Recommended backup and recovery posture

TokTrak cannot own or verify production backup policy. A safe operator procedure
should stop TokTrak, back up the complete data volume, restart it, and test
restoration regularly. Keep the env file and stable token pepper in the
protected deployment secret store, separate from volume backups.

Before upgrades, operators should pull the next immutable tag, stop TokTrak,
back up the volume, and replace the container with the new tag.

Nightly automation may poll `:latest`, but must compare its registry digest with
the running image and deploy the resolved digest only when it changes.

Pulling an image alone does not replace a running container.

Keep the previous image tag or digest and its matching data backup for rollback;
rolling back the image alone cannot undo a data migration.

Nightly deployments may skip intermediate releases, so migrations must accept
older persisted data.

## Diagnostics

The linked runtime includes JMX, JFR, `jcmd`, and `jfr`. Remote management is
disabled by default.

For a short diagnostic run, add one line to the protected env file and publish
the same fixed port on host loopback:

```sh
JDK_JAVA_OPTIONS=-Dcom.sun.management.jmxremote -Dcom.sun.management.jmxremote.port=9010 -Dcom.sun.management.jmxremote.rmi.port=9010 -Djava.rmi.server.hostname=127.0.0.1 -Dcom.sun.management.jmxremote.authenticate=false -Dcom.sun.management.jmxremote.ssl=false
podman run ... --publish=127.0.0.1:9010:9010 ...
```

Connect VisualVM or JMC to
`service:jmx:rmi:///jndi/rmi://127.0.0.1:9010/jmxrmi`. For a remote host, keep
Podman bound to loopback and tunnel with `ssh -L 9010:127.0.0.1:9010 HOST`.

Unauthenticated JMX permits code execution. Never publish it beyond loopback;
remove the options immediately after diagnosis.

Shell-free diagnostics:

```sh
podman exec toktrak /opt/toktrak/bin/jcmd 1 VM.version
podman exec toktrak /opt/toktrak/bin/jcmd 1 JFR.start name=toktrak settings=profile duration=60s filename=/data/toktrak.jfr
podman cp toktrak:/data/toktrak.jfr .
```

## Release

Versions are consecutive integers `v0`, `v1`, and so on. From a clean `trunk`
synchronized with `origin/trunk`, `mise run release` opens a Git editor with a
prefilled annotated-tag message, then pushes that tag as the sole manual release
intent. The tag message may contain an optional high-level human highlight; CI
generates the rest of the changelog from merged PRs and unmatched commits. The
tracked `CHANGELOG.md` is a frozen historical baseline through `v2`, not a draft
to edit before release.

The tag-triggered workflow checks the exact tagged commit, generates the
changelog, builds and verifies the image with rootless Podman, publishes
immutable `$TOKTRAK_IMAGE_REPOSITORY:vN`, then promotes the same image to
mutable `:latest`. Production verification on `trunk` builds and checks a
development image but never publishes. An intent tag can exist even if CI fails;
a tag is not proof of a published image.

If tag push reports failure, inspect the remote tag before retrying the same
`mise run release`; never move or delete a published intent tag. If CI fails,
repair the runner/registry issue and rerun the existing tag workflow rather than
minting another version. If the versioned image already exists, CI refuses to
overwrite a different image; investigate partial publication before proceeding.
Only one publisher may run at a time, and a stale run must never move `:latest`
backwards. Nightly deployment may poll `:latest` independently of the
release-intent step.
