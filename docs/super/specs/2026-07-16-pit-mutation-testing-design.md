# PIT Mutation Testing

## Goal

Add a fast local mutation-testing loop for code while it is still being edited:

```text
mise run pit [--xml] [production source files or directories...]
```

No source selector means all TokTrak production code. Selected source paths
limit mutation generation to those classes; PIT discovers and runs their
covering JUnit tests.

The command is standalone. `check`, `test`, `verify`, and `ci` must not invoke
it.

## Workflow

The intended loop follows PIT's “Don't let your code dry” guidance:

1. edit production code and unit tests;
2. run `mise run pit --xml` against the affected production source paths;
3. inspect surviving mutations;
4. simplify code or strengthen tests when the survivors reveal a real gap;
5. repeat before the change becomes expensive to revisit.

The selector targets production code, not tests. PIT first measures per-test
coverage, discards tests that do not execute each mutated line, and prioritizes
the remaining tests. A test selector alone would still generate mutations for
unrelated production classes and fill focused reports with `NO_COVERAGE` noise.

## Command contract

Supported forms are:

```text
mise run pit
mise run pit --xml
mise run pit sources/toktrak/toktrak/store/EventEnvelope.java
mise run pit --xml sources/toktrak/toktrak/store
mise run pit --help
```

`--xml` is an optional leading flag. It adds the XML report to the default HTML
report. Remaining arguments use the same repository-path conventions as the
existing `fmt` and `test` commands: each argument may name a file or directory,
relative or absolute, but must remain inside the repository. Directories are
expanded recursively in deterministic order.

Reject:

- unknown or misplaced options;
- duplicate `--xml`;
- paths outside the repository;
- symbolic paths or paths below a symbolic ancestor;
- nonexistent paths;
- files outside `sources/toktrak`;
- files other than production `.java` sources;
- `module-info.java` and `package-info.java`;
- selections containing no mutable production source.

`--help` must print the command syntax and exit without resolving dependencies,
compiling, deleting reports, or running PIT.

## Source-to-class mapping

For each selected source, derive its binary class name from its path beneath
`sources/toktrak`, strip `.java`, and replace path separators with dots.

For example:

```text
sources/toktrak/toktrak/store/EventEnvelope.java
```

becomes these PIT target globs:

```text
toktrak.store.EventEnvelope
toktrak.store.EventEnvelope$*
```

The exact name covers the top-level type. The `$*` glob covers nested and
anonymous classes generated from the same source without accidentally matching
another top-level type with the same prefix.

Sort and deduplicate all target globs. With no source arguments, use `toktrak.*`
directly.

Current production source files each contain one top-level type matching the
filename. Supporting additional top-level types in one file is out of scope; if
that source convention changes, replace path mapping with class-file source
metadata inspection.

Harden the shared repository-path resolver, not only PIT selection: walk every
existing component from the repository root with no-follow checks and reject a
symbolic component before reading a selected file tree. Existing formatter and
test selectors receive the same trust-boundary fix.

## Dependencies

Add `sources/pit-deps.txt` containing only the two direct tool dependencies:

```text
pkg:maven/org.pitest/pitest-command-line@1.25.7
pkg:maven/org.pitest/pitest-junit5-plugin@1.2.3
```

Resolve them and their transitives with the vendored `jresolve.jar` into:

```text
output/deps/pit
```

PIT is build tooling, not an application module. Resolve this dependency set
without `--use-module-names` and launch it on the classpath. Do not add PIT to
`module-info.java`, production/test dependency files, jlink images, IDE
metadata, or runtime containers.

Dependency fingerprints use the existing `ensureDependency` mechanism, so PIT
artifacts are downloaded only when `sources/pit-deps.txt` or `jresolve.jar`
changes. A cache hit additionally requires nonempty `pitest-command-line-*.jar`
and `pitest-junit5-plugin-*.jar` files; otherwise re-resolve instead of trusting
the stamp.

`mise run clean` must remove `output/deps/pit` and `output/mutations`.

## Build integration

Add `pit` to `tools/Build.java` and expose it through a raw-argument `pit` task
in `mise.toml`.

Execution order:

1. parse options and production source selectors;
2. compile the existing production and test modules through `compile()`;
3. resolve the dedicated PIT dependency set;
4. delete `output/mutations`;
5. launch PIT with a bounded argument file and process timeout;
6. require the requested report files to exist after a successful process.

The PIT launch classpath contains, in deterministic order:

1. `output/deps/pit` JARs;
2. `output/modules/toktrak`;
3. `output/modules/toktrak.tests`;
4. `output/deps/main` JARs;
5. `output/deps/test` JARs.

PIT runs the compiled JPMS outputs as ordinary classpath entries only for
mutation analysis. Authoritative compilation, tests, development, and production
remain module-path based.

Configure PIT with:

- mutable code path: `output/modules/toktrak`;
- target classes: selected source-derived globs, or `toktrak.*`;
- target tests: `toktrak.tests.*`;
- source directory: `sources/toktrak`;
- report directory: `output/mutations`;
- output format: HTML, plus XML when `--xml` is present;
- timestamped reports: disabled;
- workers: exactly four;
- assertions: enabled in the parent and PIT child JVMs;
- JUnit default timeout: 5 seconds in PIT child JVMs;
- PIT timeout constant: 10,000 milliseconds;
- mutation, coverage, and test-strength thresholds: zero/default;
- no excluded classes, methods, mutators, tests, or annotations.

The enclosing build process retains its existing ten-minute maximum. PIT child
processes remain bounded by JUnit and PIT. Harden the shared process termination
route so timeout/interruption terminates the complete `ProcessHandle` descendant
tree child-first, then forcibly after the existing bounded grace period. This
prevents PIT minions from surviving a killed parent and applies the same safety
to every build subprocess.

## Timeout behavior

PIT allows a mutated test to run for:

```text
normal duration × timeout factor + timeout constant
```

Its default is `normal × 1.25 + 4000 ms`. The FAQ recommends increasing the
constant when test-order, classloading, or machine load creates false timeout
detections.

TokTrak's previous full probe had a slowest baseline test of about 0.5 seconds,
but mutations that removed waits, notifications, server startup, HTTP responses,
and shutdown calls produced 16 PIT `TIMED_OUT` results and warning lines.

For mutation runs, JUnit gets a 5-second default timeout while PIT gets a
10-second constant. Genuine hangs therefore fail as ordinary JUnit test failures
before PIT's outer detector kills the worker. The five-second JUnit bound is ten
times the measured slowest baseline test; the ten-second PIT bound leaves
additional scheduling and teardown margin.

A successful full run must contain zero `TIMED_OUT` mutations in XML and no PIT
timeout warnings. Do not hide warnings, disable timeout detection, exclude the
mutants, or classify them manually.

## Warning fixes

### Inlined `finally` bytecode

PIT 1.25.7's `InlinedFinallyBlockFilter` groups same-line mutations produced by
javac's duplicated `finally` bytecode. It warns when more than one similar
mutation appears inside a handler because it cannot safely distinguish compiler
copies from genuine duplicate operations.

A focused dry run confirmed both existing warnings come from `toktrak.App`.
`App.close()` has nested `finally` blocks for server, executor, writer, event
log, and data-lock cleanup. Preserve the cleanup order and failure guarantees,
but split the nested cleanup into small private methods so each compiled method
contains only one `finally` layer. Do not disable inlined-code detection.

The mutation run is the regression check: before the refactor, mutating
`App.java` emits exactly two inlining warnings; after it, the same focused run
must emit none.

### Test `HttpClient` resources

Verbose PIT diagnostics exposed a separate test defect: four test call sites
create `HttpClient`, which is `AutoCloseable` on Java 26, without closing it.
Each client owns background selector threads. PIT reuses a worker JVM across
tests, observes the thread count increasing, and warns.

Refaster cannot safely perform this transformation: it would need to wrap an
arbitrary remainder of each test method in a new try-with-resources scope, which
is outside expression/template replacement. Record the dated failed-rule reason
in `docs/REFASTER_RULE_FAILS.md` before applying the manual edits.

Close every test client with try-with-resources in:

- `HealthModeTest`;
- `HttpServerTest`;
- `HttpAdmissionTest`.

This changes no production behavior. A verbose full PIT run must report no “More
threads at end of test” warnings.

## Reports

Every run replaces `output/mutations`; stale reports must never survive a new
selection.

Default output:

```text
output/mutations/index.html
```

With `--xml`:

```text
output/mutations/index.html
output/mutations/mutations.xml
```

PIT always generates XML internally so `Build.java` can validate statuses. A run
fails if XML contains `TIMED_OUT`, `RUN_ERROR`, `MEMORY_ERROR`, `NON_VIABLE`,
`STARTED`, or `NOT_STARTED`. When `--xml` is absent, delete the validated XML
before success; when present, retain it for agents.

PIT process failure, a missing requested report, no mutable source, no
mutations, a non-green baseline suite, or an exceptional mutation status fails
the command. `SURVIVED` and `NO_COVERAGE` do not fail it because no score gate
is configured.

Validate the completed report tree using the existing 100,000-entry and
512-MiB-per-file ceilings before success. The selected source count, ten-minute
outer process timeout, finite compiled bytecode, and report validation bound the
work without reducing PIT's mutation set.

Agent runs use `--xml` so mutation statuses are machine-readable. Human runs may
omit it when only the HTML report is useful.

## Testing

Extend `tests/tools/BuildTest.java` before implementation to cover:

1. empty selection maps to `toktrak.*`;
2. a source file maps to its exact and `$*` target globs;
3. a directory expands recursively, sorted and deduplicated;
4. invalid, external, symbolic, descriptor, and empty selections fail;
5. `--xml` adds XML while preserving HTML;
6. PIT arguments contain the fixed classpath, paths, workers, assertion,
   JUnit-timeout, and PIT-timeout settings;
7. report replacement and required-report validation are bounded to
   `output/mutations`;
8. `clean` owns the PIT dependency and report directories;
9. help, unknown/misplaced/duplicate options, absolute paths, and symbolic
   ancestors obey the command contract;
10. interrupted or timed-out subprocesses leave no live descendants;
11. corrupt stamped PIT dependency directories re-resolve;
12. exceptional XML mutation statuses fail, while `SURVIVED` and `NO_COVERAGE`
    remain successful.

Integration verification:

1. run the existing test suite;
2. run focused mutation analysis for `App.java` and confirm zero warnings, zero
   `TIMED_OUT`, and both report formats;
3. place a sentinel in `output/mutations`, run a different focused selection,
   and confirm the sentinel is removed;
4. run full `mise run pit --xml` and parse `mutations.xml` to require only
   `KILLED`, `SURVIVED`, and `NO_COVERAGE` statuses;
5. run the full PIT command once with verbose diagnostics and require zero
   warning lines, including inlined-finally and leaked-thread warnings;
6. run `mise run verify` and require pristine output.

Mutation counts and scores are observations, not fixed assertions; PIT upgrades,
source changes, and stronger tests legitimately change them. The focused and
full executions are also the explicit JUnit 6 compatibility gate: any future
PIT/JUnit plugin incompatibility fails with no fallback to an older test stack.

## Non-goals

Do not add:

- Maven, Gradle, Coursier, or another dependency resolver;
- Arcmutate or any commercial plugin;
- git-diff or changed-line targeting;
- test-class or test-method selectors;
- score thresholds;
- CI/`verify` integration;
- mutation history/incremental analysis;
- mutation exclusions or warning suppression;
- a generic build-tool abstraction.

## Research basis

- PIT command-line guide: <https://pitest.org/quickstart/commandline/>
- PIT FAQ and troubleshooting: <https://pitest.org/faq/>
- Local workflow guidance: <https://blog.pitest.org/dont-let-your-code-dry/>
- JUnit Platform plugin: <https://github.com/pitest/pitest-junit5-plugin>
- PIT 1.25.7 inlined-finally filter:
  <https://github.com/hcoles/pitest/blob/1.25.7/pitest-entry/src/main/java/org/pitest/mutationtest/build/intercept/javafeatures/InlinedFinallyBlockFilter.java>
- PIT 1.25.7 timeout strategy:
  <https://github.com/hcoles/pitest/blob/1.25.7/pitest/src/main/java/org/pitest/mutationtest/build/PercentAndConstantTimeoutStrategy.java>
