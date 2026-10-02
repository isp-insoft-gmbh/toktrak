# RULES

## Commands

- Install tools with `mise install`; establish baseline health with
  `mise run check`.
- Format Markdown and Java with `mise run fmt [paths...]`.
- Run focused tests with `mise run test --only [test paths...]` while iterating.
- Use the source-preserving ladder `mise run check`, `mise run test`, then
  `mise run verify` as needed; each rung includes the preceding guarantees.
  Tracker tests exercise temporary native user schedulers on the host.
- Run `mise run verify` before completion; it also verifies the linked
  production runtime.
- Run `mise run runtime-build` when production runtime behavior or packaging can
  change.
- Run `mise run clean` to remove generated output.
- Generate editor metadata with `mise run ide [eclipse|intellij]`; `Build.java`
  lint and `mise run fmt` remain authoritative over IDE diagnostics and
  formatting.
- Run the seeded server with `mise run dev`; use `mise run dev --fail-writes`
  for degraded-write behavior.
- Reproduce CI with `mise run ci` from a clean tree; it checks Refaster
  conformance without rewriting source and enforces coverage floors. Use
  `mise run refactor --check` while iterating and `mise run refactor` to apply
  required rewrites.

## Quality gates

- Fix every compiler, lint, and static-analysis warning. Never disable or
  suppress warnings without explicit human approval.
- Name every test case
  `given_<camelCaseContext>_when_<camelCaseBehavior>_then_<camelCaseExpectation>`;
  helpers are exempt.
- After editing unit tests, iterate with
  `mise run pit --history -- [production source paths...]`, inspect survivors,
  and strengthen useful tests. If history errors or looks inconsistent, delete
  `output/pit.history` and rerun without `--history`.
- Add meaningful coverage rather than weakening coverage floors.
- Use Selfie only for stable, reviewable HTML, JSON, text, or protocol output.
  Inspect every `.ss` diff and trace each changed fragment to production code.
  Commit the golden file and rewritten Java; never commit update markers.

## Safety

- Never put secrets in source control, logs, exception messages, or generated
  public output.
- Enable assertions (`-ea`) in every dev, test, and production launch.
- Assert internal arguments, returns, preconditions, postconditions, invariants,
  expected states, and forbidden states. Pair assertions across boundaries and
  fail fast on invariant breach.
- Never replace external-input validation or recoverable error handling with an
  assertion.
- Use `Objects.requireNonNull` only at public/trust boundaries or recoverable
  paths; assert internal null invariants.
- Use defense in depth and runtime self-checks.
- Explicitly bound bodies, files, lines, collections, queues, concurrency,
  loops, retries, timeouts, and batches. Avoid recursion and unbounded work.
- Schedule background work at fixed intervals and process bounded batches.
- Choose fixed-width Java primitives deliberately, validate ranges, and use
  exact arithmetic where overflow matters.

## Design

- Keep orchestration one-way: Mise invokes `tools/Build.java`; `Build.java` must
  never invoke Mise.
- Keep interfaces small and define fault behavior.
- Isolate nondeterministic I/O behind deterministic logic; push control flow
  upward and data transformation downward.
- Prefer simple signatures and return types. Declare variables near first use.
- Never use boolean view-model components; use semantic enums or value records.
- Separate control and data planes; batch I/O and computation.
- Follow Java naming conventions. Use precise nouns and verbs, suffix qualifiers
  such as `latencyMillisMax`, and avoid abbreviations.
- Write Javadoc only with `///` Markdown documentation comments; never use
  `/** ... */`.
- Keep the tracker one plain Node `.mjs` using the standard library and native
  user schedulers. Do not add a JavaScript build system.
- Keep production runtime configuration in environment variables.
- Never enable `TOKTRAK_DEV_AUTH` in production.

## Runtime assets

- Use SVG for vectors and optimized WebP or AVIF for runtime raster images.
- Reject every other runtime raster format during the build.
- Asset validation errors must identify the rejected asset, violated constraint,
  and exact remediation.

## Release and deployment

- Keep production secrets in environment configuration, never source control.
- Back up the mounted `TOKTRAK_DATA_DIR`; deployment owns backup, TLS, and
  compression.
- Keep local build, test, and `mise run release` independent of container
  runtimes.
- Build, verify, and publish container images only in CI using rootless Podman.
- Keep version numbers monotonically increasing integers starting at `0`.
- `mise run release` is the sole manual release intent: review an optional
  high-level annotated-tag message, then push the tag.
- Generate release history from that message, merged PRs, and unmatched commits;
  never require manual edits to `CHANGELOG.md`.
- CI must verify the tagged commit and preserve immutable versioned images
  before promoting the verified artifact to `:latest`.
