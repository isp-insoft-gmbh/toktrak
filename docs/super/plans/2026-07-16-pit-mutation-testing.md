# PIT Mutation Testing Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use /skill:executing-plans to
> implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for
> tracking.

**Goal:** Add `mise run pit [PIT options...] [-- production source paths...]`,
remove all PIT timeout/warning noise, and use focused mutation runs to improve
every applicable unit-test area.

**Architecture:** `tools/Build.java` resolves PIT with vendored jresolve, maps
selected production sources to exact/nested class globs, launches PIT on a
bounded classpath, validates XML statuses, and owns deterministic reports.
Shared path/process helpers are hardened because PIT selectors and child minions
expose existing trust and lifecycle gaps.

**Tech Stack:** Java 26, PIT 1.25.7, pitest-junit5-plugin 1.2.3, JUnit 6.0.0,
vendored jresolve, mise.

**Roadmap:** None

**Phase:** Single-plan implementation

---

## Pre-implementation snapshot

- [x] Saved `git status --short`, binary diffs, and complete copies of dirty
      `mise.toml` and `JlinkSmokeTest.java` under `/tmp/toktrak-pit-user-state`
      before implementation.

## Files

- Create: `sources/pit-deps.txt` — pinned PIT tool dependencies.
- Modify: `mise.toml` — standalone raw-argument `pit` task.
- Modify: `tools/Build.java` — command, selection, launch, report validation,
  cleanup, path/process hardening.
- Modify: `tests/tools/BuildTest.java` — build-command and shared-helper
  regression tests.
- Modify: `sources/toktrak/toktrak/App.java` — remove nested-finally bytecode
  ambiguity.
- Modify: `tests/toktrak.tests/toktrak/tests/HealthModeTest.java` — close
  clients.
- Modify: `tests/toktrak.tests/toktrak/tests/HttpServerTest.java` — close
  clients.
- Modify: `tests/toktrak.tests/toktrak/tests/HttpAdmissionTest.java` — close
  client.
- Modify: `docs/REFASTER_RULE_FAILS.md` — record why Refaster cannot wrap
  arbitrary statement tails.
- Modify as justified by focused PIT reports: affected production tests only.

### Task 1: Define PIT command behavior with failing build-tool tests

- [ ] **Step 1: Add tests for option parsing and source targets**

Add focused `BuildTest` cases that call new package-private test seams:

```java
assertEquals(List.of("toktrak.*"), Build.pitTargetsForTest(List.of()));
assertEquals(
    List.of("toktrak.store.EventEnvelope", "toktrak.store.EventEnvelope$*"),
    Build.pitTargetsForTest(
        List.of("sources/toktrak/toktrak/store/EventEnvelope.java")));
assertEquals(
    List.of("toktrak.*"),
    Build.pitSelectionForTest(List.of()).targetClasses());
```

Add failures for duplicate/late/unknown options, descriptors, external paths,
empty directories, direct symlinks, and a path below a symlinked ancestor. Add
positive cases for absolute in-repository files and recursively selected,
sorted, deduplicated directories. Assert `pit --help` performs no compilation,
dependency, or report writes.

- [ ] **Step 2: Add tests for PIT argument construction and XML status policy**

Assert fixed arguments include HTML/XML generation, four threads, 10,000 ms PIT
constant, `-ea`, and the five-second JUnit property. Add temporary XML fixtures:

```xml
<mutations>
  <mutation status="KILLED"/>
  <mutation status="SURVIVED"/>
  <mutation status="NO_COVERAGE"/>
</mutations>
```

and one fixture per forbidden status. Assert accepted statuses pass and each
forbidden status fails. Assert an XML document with zero `<mutation>` elements
fails. Test missing/oversized report files, bounded report-tree validation,
deterministic replacement, and `clean` ownership.

- [ ] **Step 3: Add shared path/process regression tests**

Test ancestor-symlink rejection where supported. Start a bounded Java child that
starts a descendant process; call the test termination seam and assert both
handles become dead within the grace bound.

- [ ] **Step 4: Run the build-tool tests and verify RED**

Run:

```text
mise run test tests/tools/BuildTest.java
```

Expected: compilation failures for missing PIT APIs/test seams.

### Task 2: Implement dependency resolution, selection, and reports

- [ ] **Step 1: Add pinned PIT dependencies**

Create `sources/pit-deps.txt`:

```text
pkg:maven/org.pitest/pitest-command-line@1.25.7
pkg:maven/org.pitest/pitest-junit5-plugin@1.2.3
pkg:maven/org.pitest/pitest-history-plugin@0.0.1
```

- [ ] **Step 2: Add `pit` command constants and lifecycle ownership**

Add `PIT_DEPS`, `MUTATIONS`, `PIT_HISTORY`, `APP_SOURCES`, fixed worker/timeout
constants, and `pit` dispatch/help. Add generated PIT state to `clean()`.

- [ ] **Step 3: Implement PIT option/source selection**

Implement PIT option forwarding before `--`, path expansion after `--` through
the hardened shared selector, descriptor exclusion, exact plus `$*` patterns,
sorted deduplication, and `toktrak.*` for empty source arguments.

- [ ] **Step 4: Implement PIT dependency integrity checks**

Resolve without module names. A valid cache requires exactly one nonempty
`pitest-command-line-*.jar` and one nonempty `pitest-junit5-plugin-*.jar`; a
missing root artifact forces re-resolution.

- [ ] **Step 5: Implement PIT classpath and arguments**

Build a deterministic classpath from PIT, application/test outputs, and
main/test dependencies. Launch the PIT command-line main with:

```text
--targetClasses <derived globs>
--targetTests toktrak.tests.*
--mutableCodePaths output/modules/toktrak
--sourceDirs sources/toktrak
--reportDir output/mutations
--outputFormats HTML,XML
--timestampedReports=false
--threads 4
--timeoutConst 10000
--jvmArgs -ea,-Djunit.jupiter.execution.timeout.default=5s
```

Always request XML internally.

- [ ] **Step 6: Validate and finalize reports**

Require `index.html` and `mutations.xml`, securely parse at least one mutation,
reject all statuses except `KILLED`, `SURVIVED`, and `NO_COVERAGE`, validate
tree/file ceilings, and retain both HTML and XML.

- [ ] **Step 7: Implement the command flow explicitly**

Dispatch `pit` before `main` deletes `output/args`. `pitCommand` must parse
options first, return immediately for help, then delete stale argfiles, compile
the application/tests, resolve PIT, replace `output/mutations`, write a bounded
Java launcher argument file, invoke PIT's command-line main class, validate
reports, and finalize XML retention. No file side effect may occur before
successful PIT parsing.

- [ ] **Step 8: Expose the mise task**

Add:

```toml
[tasks.pit]
description = "Mutation-test all or selected production sources"
raw_args = true
run = "java -ea tools/Build.java pit"
```

- [ ] **Step 9: Reject symbolic ancestors in the shared resolver**

Walk from normalized repository root to the requested path component by
component. Reject any existing symbolic component before `Files.walk`; preserve
current lexical repository-boundary checks.

- [ ] **Step 10: Terminate descendant process trees**

Snapshot bounded descendants, destroy them child-first, destroy the parent, wait
within the existing grace period, then force remaining descendants and parent
child-first. Assert no captured process remains alive.

- [ ] **Step 11: Run focused tests and verify GREEN**

Run:

```text
mise run test tests/tools/BuildTest.java
```

Expected: every `BuildTest` case passes, including PIT, direct/ancestor symlink,
and descendant termination cases.

### Task 3: Remove warning root causes

- [ ] **Step 1: Record the failed Refaster rule attempt**

Append a dated entry to `docs/REFASTER_RULE_FAILS.md` stating that converting
four `HttpClient` locals requires wrapping arbitrary following statements in a
try-with-resources scope; Refaster expression/block templates cannot safely
capture and reparent that statement tail.

- [ ] **Step 2: Remove test HTTP-client thread owners**

Replace all four `HttpClient` sites with bounded `HttpURLConnection` requests.
Set `Connection: close`, close every response stream, and always call
`disconnect()` so PIT workers retain no client selector-manager threads.

- [ ] **Step 3: Split nested cleanup in `App.close()`**

Preserve cleanup order and strengthen failure guarantees with a four-level chain
where every compiled method has at most one `finally`: `close()` stops the
server and finally calls `closeExecutorAndStorage()`; that helper shuts down the
executor and finally calls `closeWriterAndStorage()`; that helper closes the
writer and finally calls `closeStorage()`; the final helper closes the event log
and finally closes the data lock.

- [ ] **Step 4: Run tests and focused App mutation analysis**

Run:

```text
mise run test tests/toktrak.tests/toktrak/tests/HealthModeTest.java tests/toktrak.tests/toktrak/tests/HttpServerTest.java tests/toktrak.tests/toktrak/tests/HttpAdmissionTest.java tests/toktrak.tests/toktrak/tests/ShutdownTest.java
mise run pit -- sources/toktrak/toktrak/App.java
```

Expected: tests pass; PIT completes with no warnings or exceptional statuses.

### Task 4: Verify focused mutation usefulness across every test area

- [ ] **Step 1: Execute the complete review matrix**

Run one `mise run pit -- <sources...>` command for each row and copy its XML to
`output/pit-review/<TestClass>.xml` before the next run replaces the report:

| Test class             | Production source selection                                                                  |
| ---------------------- | -------------------------------------------------------------------------------------------- |
| `AssertionsTest`       | none: JVM assertion harness check                                                            |
| `BodyLimitTest`        | `toktrak/http/HttpSupport.java`                                                              |
| `ConfigTest`           | `toktrak/Config.java`, `toktrak/ClockSource.java`                                            |
| `DataLockTest`         | `toktrak/store/DataLock.java`                                                                |
| `DevDataTest`          | `toktrak/dev/DevData.java`, `toktrak/App.java`                                               |
| `EventEnvelopeTest`    | `toktrak/store/EventEnvelope.java`                                                           |
| `EventLogTest`         | `toktrak/store/EventLog.java`, `toktrak/store/EventEnvelope.java`                            |
| `HealthModeTest`       | `toktrak/App.java`, `toktrak/health/HealthState.java`, `toktrak/http/Router.java`            |
| `HttpAdmissionTest`    | `toktrak/http/Router.java`, `toktrak/http/RequestContext.java`, `toktrak/http/ApiError.java` |
| `HttpServerTest`       | `toktrak/App.java`, `toktrak/http/Router.java`, `toktrak/http/HttpSupport.java`              |
| `JlinkSmokeTest`       | none: conditional packaged-runtime integration check                                         |
| `JsonLogFormatterTest` | `toktrak/log/JsonLogFormatter.java`                                                          |
| `MainTest`             | `toktrak/Main.java`                                                                          |
| `ProjectionTest`       | `toktrak/projection/Projection.java`, `toktrak/json/Json.java`                               |
| `RestartAndLockTest`   | `toktrak/App.java`, `toktrak/store/DataLock.java`, `toktrak/store/EventLog.java`             |
| `ShutdownTest`         | `toktrak/App.java`, `toktrak/store/Writer.java`, `toktrak/store/EventLog.java`               |
| `TestLauncherTest`     | none: test-harness behavior                                                                  |
| `WriterTest`           | `toktrak/store/Writer.java`, `toktrak/store/WriteCommand.java`                               |
| `BuildTest`            | none: source-file build tool outside application PIT scope                                   |

Prefix every listed path with `sources/toktrak/`. Confirm each copied report is
nonempty and identify covering/killing test names before judging survivors.

- [ ] **Step 2: Record and address meaningful survivors**

Maintain `output/pit-review/review.md` with one row per considered survivor:
class, line, mutator, description/index, relevant covering/killing test,
disposition/rationale, focused unit-test command, and before/after status. For
each meaningful survivor, first add the smallest behavioral assertion, run the
focused unit test, rerun the same source selection, copy the new XML, and
require that exact mutant identity to become `KILLED`. If it exposes redundant
production code, delete or simplify that code instead. Do not chase equivalent
mutants or unrelated legacy gaps.

- [ ] **Step 3: Check harness-only tests normally**

Run:

```text
mise run test tests/toktrak.tests/toktrak/tests/TestLauncherTest.java tests/tools/BuildTest.java
```

Inspect `JlinkSmokeTest` against its user-authored TODO without silently
expanding PIT's production-only scope.

### Task 5: Full verification and cleanup

- [ ] **Step 1: Verify report replacement**

Create `output/mutations/sentinel`, run a focused PIT selection, and assert the
sentinel is gone while requested reports exist.

- [ ] **Step 2: Run full mutation analysis**

Run:

```text
mise run pit
```

Parse XML and require only `KILLED`, `SURVIVED`, and `NO_COVERAGE`. Capture the
score/counts as observations.

- [ ] **Step 3: Run a verbose diagnostic full pass**

Copy the exact validated full-run PIT arguments from the build-tool test seam,
change only `--reportDir` to `output/pit-verbose`, append `--verbose`, and
launch PIT's command-line main class with the same computed classpath. Redirect
merged stdout/stderr to `output/pit-verbose.log`, require process success,
require no inlined-finally, `HttpClient`, PIT-timeout, or application-thread
warning, and validate the isolated XML with the same status parser. Document
PIT's false-positive JUnit timeout-watcher and virtual-thread carrier count
warnings. Delete the isolated report and log after inspection.

- [ ] **Step 4: Run authoritative verification**

Run:

```text
mise run verify
```

Expected: formatting, `-Xlint:all -Werror`, Error Prone, build tests, unit
tests, and tagged tests pass with pristine output.

- [ ] **Step 5: Inspect final state**

Never reset or checkout `mise.toml` or `JlinkSmokeTest.java`. Compare the saved
`JlinkSmokeTest.java` byte-for-byte and confirm every pre-existing `mise.toml`
hunk remains unchanged around the newly added task. Confirm only intended new
changes, remove all experiment/review artifacts, and report remaining survivor
classes worth future work without adding a score gate.
