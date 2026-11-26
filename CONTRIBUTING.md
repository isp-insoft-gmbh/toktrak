# Contributing

## Requirements

- Git.
- [mise](https://mise.jdx.dev/).
- Windows, Apple Silicon macOS, or Linux.

Run `mise install`; mise installs the versions committed in `mise.lock` for
Java, Node.js, hyperfine, and formatters. Corretto matches the container build
JDK. No Maven, Gradle, npm install, or JavaScript build is used.

Local checks and `mise run release` require no container runtime or registry
credentials. CI alone builds, verifies, and publishes OCI images using rootless
Podman. `mise run release` reviews an optional annotated-tag message and pushes
the next tag; the CI release workflow then handles publication.

## Setup

```sh
mise install
mise run check
mise run dev
```

Development uses local dev authentication and the anonymized corpus. Restart
`mise run dev` manually after edits. Stop the dev server before running build
tasks; `mise run fmt` remains available.

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

`.claude/skills` is canonical; `.agents/skills` is its committed, byte-identical
mirror for native Codex discovery. Keep both trees in sync when editing skills.
Use regular files, not a directory symlink, so repository snapshots retain all
native skills. `mise run check` rejects mirror drift, extra files, and links.

## Persisted schema compatibility

Assume every TokTrak deployment already contains durable production data. Every
released event schema remains readable and migratable indefinitely. A schema
change must document its transition, fail safely on unknown future versions, and
add anonymized historical shapes to `tests/corpus/dev.jsonl`. Existing corpus
tests must prove the complete development corpus still replays without loss,
corruption, or reinterpretation. Never add production data or separate release
fixtures.

New usage uploads accept numbers with at most 256 integer digits and 256
fractional digits after exponent expansion. This admission limit also covers
nested report fields. It applies only to incoming uploads; replay preserves
previously stored numbers without changing the v1 event schema or rounding data.

## Production-shaped local data

Realistic data matters more than full anonymity when designing dashboards or
checking migrations. `mise run corpus-import` turns a deployment's
`events.ndjson` into pseudonymous development data:

1. Stop TokTrak, copy `TOKTRAK_DATA_DIR/events.ndjson` to a private location
   outside this repository, then restart it. A live copy can end in a partial
   event, which the importer rejects rather than skipping.
2. Run `mise run corpus-import <events.ndjson>`.
3. Run `mise run dev --corpus output/corpus/production-anonymized.jsonl`.

The import replaces identities, tracker tokens, projects, sessions, and errors
with keyed pseudonyms. It fails on any string field without an explicit rule. It
keeps exact numbers, timestamps, models, tools, colors, and time zones. One
uploading person, chosen by the key, becomes the development login `viewer`, so
personal pages show real-shaped data.

The output is pseudonymous: anyone who knows the team can recognize people from
their usage pattern. Keep snapshots and output private and out of Git. Never
copy output lines into `tests/corpus/dev.jsonl`.

### Committed corpus shapes

The import lists field shapes the committed corpus lacks. A shape is a field
path plus its JSON type. `mise run corpus-shapes` closes that gap without
committing production data. It picks the fewest pseudonymized events covering
every missing shape and keeps only the array rows that carry them. Their values
become synthetic:

- One new `synthetic-subject-N` owns all events. Identifiers, token labels and
  digests, projects, and sessions become fresh synthetic values.
- Timestamps and dates are regenerated before the hand-written history. Only
  their order, equality, and text format survive; time of day, weekday, and
  spacing do not.
- Numbers keep sign, zero, fraction length, and exponent, but get random digits
  and at most two integer digits. Related numbers, such as totals and their
  parts, therefore no longer add up.
- Model, tool, version, and time-zone names, booleans, and nulls stay as they
  are.

The command appends to `tests/corpus/dev.jsonl` and fails if the result still
misses a shape of the input. Review the diff, then update `CorpusUsageTest` and
other corpus expectations deliberately; changed totals are expected.

### Pseudonymization key

The key keeps pseudonyms stable across imports and developers. Treat it as a
team secret: with it, anyone can test guessed emails or account IDs against
pseudonyms. Keep one key per deployment in your team's secret manager.

- First import for a deployment: run `mise run corpus-import <events.ndjson>
  --new-key`, then store the created key file's content in your secret manager.

- New developer: copy the key from the secret manager into a private file
  outside the repository. Either use the default path or point
  `TOKTRAK_CORPUS_KEY_FILE` at it, for example in the Git-ignored
  `mise.local.toml`:

  ```toml
  [env]
  TOKTRAK_CORPUS_KEY_FILE = "/path/to/toktrak-corpus.key"
  ```

The default path is `%LOCALAPPDATA%\toktrak\corpus-anonymization.key` on Windows
and `$XDG_CONFIG_HOME/toktrak/corpus-anonymization.key` (falling back to
`~/.config`) elsewhere. `--key <path>` overrides both. Without a key the import
stops and explains these steps instead of silently creating a new one.

## Visualization correctness

Every money figure on the visualizations page carries its exact canonical USD
value in a `data-usd` attribute next to the rounded, possibly converted display.

- `VisualizationsConsistencyTest` checks, for many ranges of the development
  corpus and of seeded random uploads, that figures agree with each other. Tool
  totals, period totals, and bars each sum to the range total. Adjacent ranges
  add up to their union, and months add up to their year. Each display is its
  exact value rounded half-up to cents. Every new chart adds its own identities
  to this test.
- `mise run viz-oracle <events.ndjson> [server-url] [random-ranges]` recomputes
  every figure independently from raw events and compares it with a running
  `mise run dev --corpus` server. Run it against pseudonymized production data
  before changing how figures are computed.

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

`test` includes checks, Java tests, build/workflow JavaScript tests, and tracker
tests; use `mise run test --only [test paths...]` for focused tests without
repository-wide checks. `verify` also links and checks the production runtime.
`ci` also checks Refaster conformance and coverage floors, and requires a clean
Git tree. `check` already includes Markdown lint and tracker JavaScript lint.
The external `isp-golem-xiv` App validates `.golems/` definitions and owns
harness authentication. Tracker tests exercise the host's native scheduler on
Linux, macOS, and Windows; all three matrix jobs must pass before the required
`CI / ci` gate succeeds. Format changes explicitly with `mise run fmt
[paths...]`.

Dprint plugins in `dprint.json` are versioned and SHA-256 pinned. The weekly
`.github/workflows/dprint.yml` workflow (also manually dispatchable) checks for
new releases, verifies their hashes, formats changed files, and opens a PR. Do
not use `dprint config update` for routine updates: the locked dprint version
converts HTTPS plugins to npm references and drops Wasm checksum pins. D2 and
SVG are excluded; Mustache templates are also excluded because markup_fmt
changes JStachio standalone-tag whitespace and rendered HTML snapshots.

Additional gates:

| Change                   | Run                                     |
| ------------------------ | --------------------------------------- |
| Unit tests               | `mise run pit -- [production paths...]` |
| Coverage-sensitive code  | `mise run coverage`                     |
| Tracker scheduler        | `mise run tracker-test`                 |
| Runtime or packaging     | `mise run runtime-build`                |
| Performance evidence     | `mise run perf`                         |
| Container build/behavior | GitHub Actions production verification  |
| Before pushing           | `mise run ci`                           |

The Linux scheduler test needs a running systemd user manager. When `systemctl
--user` fails for any reason, it is skipped unless
`TOKTRAK_REQUIRE_NATIVE_SCHEDULER=1`. The `core`, tracker and release workflows
set that variable, so CI still fails without a user manager.

Every test name follows
`given_<camelCaseContext>_when_<camelCaseBehavior>_then_<camelCaseExpectation>`.
Fix every warning; do not suppress checks without approval.
