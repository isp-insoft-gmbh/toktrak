# Compact JUnit Summary Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use /skill:executing-plans to
> implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for
> tracking.

**Goal:** Replace JUnit's verbose stock summary with the approved compact
metadata.

**Architecture:** Keep `SummaryGeneratingListener` as the data source. Add one
package-private deterministic formatter to `TestLauncher`, test it directly, and
retain JUnit's failure-detail printer.

**Tech Stack:** Java, JUnit Platform, JUnit Jupiter

**Roadmap:** None

**Phase:** Single-plan implementation

---

## Tasks

### Task 1: Compact summary

**Files:**

- Modify: `tests/toktrak.tests/toktrak/tests/TestLauncher.java`
- Create: `tests/toktrak.tests/toktrak/tests/TestLauncherTest.java`

- [x] **Step 1: Write failing formatter tests**

Create `TestLauncherTest.java`. Initially call the missing formatter through
reflection so the test compiles and fails at runtime with
`compact summary formatter missing`. Cover zero and nonzero exceptional counts:

```java
package toktrak.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;

final class TestLauncherTest {
  @Test
  void omitsZeroExceptionalCounts() {
    assertEquals(
        String.join(
            System.lineSeparator(),
            "duration: 1153 ms",
            "junit containers found: 17",
            "tests found: 55",
            "tests passed: 55"),
        invokeFormatter(1153, 17, 55, 55, 0, 0, 0));
  }

  @Test
  void includesNonzeroExceptionalCounts() {
    assertEquals(
        String.join(
            System.lineSeparator(),
            "duration: 1200 ms",
            "junit containers found: 18",
            "tests found: 60",
            "tests passed: 54",
            "tests skipped: 2",
            "tests aborted: 1",
            "tests failed: 3"),
        invokeFormatter(1200, 18, 60, 54, 2, 1, 3));
  }

  private static String invokeFormatter(
      long durationMillis,
      long containersFound,
      long testsFound,
      long testsPassed,
      long testsSkipped,
      long testsAborted,
      long testsFailed) {
    try {
      Method formatter =
          TestLauncher.class.getDeclaredMethod(
              "formatSummary",
              long.class,
              long.class,
              long.class,
              long.class,
              long.class,
              long.class,
              long.class);
      return (String)
          formatter.invoke(
              null,
              durationMillis,
              containersFound,
              testsFound,
              testsPassed,
              testsSkipped,
              testsAborted,
              testsFailed);
    } catch (ReflectiveOperationException exception) {
      throw new AssertionError("compact summary formatter missing", exception);
    }
  }
}
```

- [x] **Step 2: Verify RED**

Run `java -ea tools/Build.java verify`. Expect both new tests to fail at runtime
with `compact summary formatter missing`; compilation must succeed.

- [x] **Step 3: Implement minimal formatter and output routing**

In `TestLauncher.java`, import `java.util.StringJoiner`. Replace the block
beginning with `long testsFound` through the final `summary.printTo(...)` with:

```java
long testsFound = summary.getTestsFoundCount();
boolean failed =
    testsFound == 0 || testsFound > TEST_COUNT_MAX || !summary.getFailures().isEmpty();
if (!summary.getFailures().isEmpty())
  summary.printFailuresTo(new PrintWriter(System.err, true));
long durationMillis =
    Math.subtractExact(summary.getTimeFinished(), summary.getTimeStarted());
System.out.println(
    formatSummary(
        durationMillis,
        summary.getContainersFoundCount(),
        testsFound,
        summary.getTestsSucceededCount(),
        summary.getTestsSkippedCount(),
        summary.getTestsAbortedCount(),
        summary.getTestsFailedCount()));
if (failed) System.exit(1);
assert summary.getTestsSucceededCount() == testsFound;
```

Add this package-private formatter:

```java
static String formatSummary(
    long durationMillis,
    long containersFound,
    long testsFound,
    long testsPassed,
    long testsSkipped,
    long testsAborted,
    long testsFailed) {
  assert durationMillis >= 0;
  assert containersFound >= 0;
  assert testsFound >= 0;
  assert testsPassed >= 0;
  assert testsSkipped >= 0;
  assert testsAborted >= 0;
  assert testsFailed >= 0;
  var lines = new StringJoiner(System.lineSeparator());
  lines.add("duration: " + durationMillis + " ms");
  lines.add("junit containers found: " + containersFound);
  lines.add("tests found: " + testsFound);
  lines.add("tests passed: " + testsPassed);
  if (testsSkipped != 0) lines.add("tests skipped: " + testsSkipped);
  if (testsAborted != 0) lines.add("tests aborted: " + testsAborted);
  if (testsFailed != 0) lines.add("tests failed: " + testsFailed);
  return lines.toString();
}
```

- [x] **Step 4: Verify GREEN**

Run `java -ea tools/Build.java verify`. Expect zero warnings, 57 successful
tests, and compact summary output containing four lines when all tests pass.
This full launcher run verifies the formatter is wired into `main`;
failure-detail printing and status 1 remain on the existing failure branch
rather than adding a recursive subprocess test of the test launcher.

- [x] **Step 5: Replace reflection with direct calls**

Remove `java.lang.reflect.Method` and `invokeFormatter` from
`TestLauncherTest.java`, then replace both `invokeFormatter(...)` calls with
`TestLauncher.formatSummary(...)`.

- [x] **Step 6: Verify the refactor**

Run `java -ea tools/Build.java verify` again. Expect the same zero-warning,
57-test successful result and compact four-line summary.

- [x] **Step 7: Commit**

Commit `TestLauncher.java`, `TestLauncherTest.java`, and this plan with subject
`test: compact JUnit summary`.
