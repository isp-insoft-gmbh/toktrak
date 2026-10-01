# Contributing

## Requirements

- Git.
- [mise](https://mise.jdx.dev/).
- Windows, Apple Silicon macOS, or Linux.

Run `mise install`; mise installs the versions committed in `mise.lock` for
Java, Node.js, hyperfine, formatters, and watchers. Temurin 27 no longer ships
macOS Intel binaries. No Maven, Gradle, npm install, or JavaScript build is
used.

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
- `tools/perf`: observational performance suite behind `mise run perf`.
- `tools`: Refaster rules and build support.
- `vendored`: pinned build bootstrap artifacts.
- `output`: disposable generated output.
- `.system`: product intent, rules, specs, phases, plans, and issues.

Read `.system/SYSTEM.md`, `.system/MISSION.md`, and `.system/RULES.md` before
changing product behavior.

## Persisted schema compatibility

Assume every TokTrak deployment already contains durable production data. Every
released event schema remains readable and migratable indefinitely. A schema
change must document its transition, fail safely on unknown future versions, and
add anonymized historical shapes to `tests/corpus/dev.jsonl`. Existing corpus
tests must prove the complete development corpus still replays without loss,
corruption, or reinterpretation. Never add production data or separate release
fixtures.

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

`mise run refactor` rewrites authored Java through Refaster; inspect the diff.
`mise run refactor --check` checks conformance without editing. `mise run ci`
includes that check and requires a clean tree.

## Verify

The source-preserving ladder adds guarantees at each rung:

```sh
mise run check
mise run test
mise run verify
mise run ci
```

`test` includes checks, Java tests, golem tests, and tracker tests; use
`mise run test --only [test paths...]` for focused tests without repository-wide
checks. `verify` also links and checks the production runtime. `ci` also checks
Refaster conformance and coverage floors, and requires a clean Git tree. `check`
already includes Markdown lint, tracker JavaScript lint, and offline golem
definition validation. Live golem authentication remains separate from the
ladder. Tracker tests exercise the host's native scheduler on Linux, macOS, and
Windows; all three matrix jobs must pass before the required `CI / ci` gate
succeeds. Format changes explicitly with `mise run fmt [paths...]`.

Dprint plugins in `dprint.json` are versioned and SHA-256 pinned. The weekly
`.github/workflows/dprint.yml` workflow (also manually dispatchable) checks for
new releases, verifies their hashes, formats changed files, and opens a PR. Do
not use `dprint config update` for routine updates: the locked dprint version
converts HTTPS plugins to npm references and drops Wasm checksum pins. D2 and
SVG are excluded; Mustache templates are also excluded because markup_fmt
changes JStachio standalone-tag whitespace and rendered HTML snapshots.

Additional gates:

| Change                  | Run                                               |
| ----------------------- | ------------------------------------------------- |
| Unit tests              | `mise run pit --history -- [production paths...]` |
| Coverage-sensitive code | `mise run coverage`                               |
| Tracker scheduler       | `mise run tracker-test`                           |
| Golem authentication    | `mise run golem-auth-check`                       |
| Runtime or packaging    | `mise run runtime-build`                          |
| Performance evidence    | `mise run perf`                                   |
| Container image build   | `mise run container-build`                        |
| Container behavior      | `mise run container-verify`                       |
| Before pushing          | `mise run ci`                                     |

On Windows, run container verification through the rootless connection:

```powershell
$env:CONTAINER_CONNECTION="podman-machine-default"
mise run container-verify
```

Every test name follows
`given_<camelCaseContext>_when_<camelCaseBehavior>_then_<camelCaseExpectation>`.
Fix every warning; do not suppress checks without approval.
