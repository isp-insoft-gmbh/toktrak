# Contributing

## Requirements

- Git.
- [mise](https://mise.jdx.dev/).
- Windows, macOS, or Linux.

Run `mise install`; mise installs the pinned Java, Node.js, formatter, and
watcher versions. No Maven, Gradle, npm install, or JavaScript build is used.

Rootless Podman is required only for image, container, and release work. Start a
Podman machine first on Windows or macOS. `mise run release` selects the
rootless `podman-machine-default` connection automatically on Windows.

Image and release tasks read the repository from ignored local config:

```toml
# mise.local.toml
[env]
TOKTRAK_IMAGE_REPOSITORY = "registry.example.com/team/toktrak"
```

Never commit `mise.local.toml`. CI uses `localhost/toktrak` and never pushes it.

## Setup

```sh
mise install
mise run check
mise run dev
```

Development uses local dev authentication and the anonymized corpus. Stop the
dev server before running build tasks; `mise run fmt` remains available.

## Repository

- `sources`: production Java, templates, and runtime assets.
- `tests`: Java tests, snapshots, and the development corpus.
- `tools/Build.java`: build authority.
- `tools`: Refaster rules and build support.
- `vendored`: pinned build bootstrap artifacts.
- `output`: disposable generated output.
- `.system`: product intent, rules, specs, phases, plans, and issues.

Read `.system/SYSTEM.md`, `.system/MISSION.md`, and `.system/RULES.md` before
changing product behavior.

## Generated code

JStachio generates renderers from `sources/toktrak/templates` during
compilation. Edit the template or Java model, never generated Java under
`output`.

Runtime asset indexes, linked runtimes, dependency downloads, reports, and IDE
metadata are also generated under `output`; do not commit them. Regenerate IDE
metadata with `mise run ide`.

Selfie `.ss` files are reviewed golden files, not disposable output. Commit an
intentional snapshot change with its rewritten Java test; never commit update
markers.

`mise run refactor` and `mise run ci` may rewrite authored Java through
Refaster. Inspect the diff. CI requires the rewrite to leave the tree clean.

## Verify

During iteration:

```sh
mise run fmt [paths...]
mise run test [test paths...]
mise run verify
```

Additional gates:

| Change                  | Run                                               |
| ----------------------- | ------------------------------------------------- |
| Unit tests              | `mise run pit --history -- [production paths...]` |
| Coverage-sensitive code | `mise run coverage`                               |
| Runtime or packaging    | `mise run prod`                                   |
| Container behavior      | `mise run container-verify`                       |
| Before pushing          | `mise run ci`, then `mise run coverage`           |

On Windows, run container verification through the rootless connection:

```powershell
$env:CONTAINER_CONNECTION="podman-machine-default"
mise run container-verify
```

Every test name follows
`given_<camelCaseContext>_when_<camelCaseBehavior>_then_<camelCaseExpectation>`.
Fix every warning; do not suppress checks without approval.
