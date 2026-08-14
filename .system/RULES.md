# RULES

## Commands

- Install tools with `mise install`; establish baseline health with
  `mise run check`.
- Format Markdown and Java with `mise run fmt [paths...]`.
- Run focused tests with `mise run test [test paths...]` while iterating.
- Run `mise run verify` before completion; it is the complete read-only local
  gate.
- Run `mise run prod` when production runtime behavior or packaging can change.
- Run `mise run clean` to remove generated output.
- Run the seeded server with `mise run dev`; use `mise run dev --fail-writes`
  for degraded-write behavior.
- Reproduce CI with `mise run ci`, then `mise run coverage`. `ci` applies
  Refaster and requires the resulting tree to remain clean.

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

- Keep interfaces small and define fault behavior.
- Isolate nondeterministic I/O behind deterministic logic; push control flow
  upward and data transformation downward.
- Prefer simple signatures and return types. Declare variables near first use.
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
- Prefer rootless Podman.
- Keep version numbers monotonically increasing integers starting at `0`.
- Update `CHANGELOG.md` before the explicit human release command
  `mise run release`.
