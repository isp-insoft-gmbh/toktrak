# Compact JUnit Summary

## Goal

Replace JUnit's verbose stock summary with output containing only test-workflow metadata useful to developers and agents.

## Output

After every run, print these lines in order:

```text
duration: <milliseconds> ms
junit containers found: <count>
tests found: <count>
tests passed: <count>
```

Derive duration from JUnit's finish and start timestamps. Append `tests skipped`, `tests aborted`, and `tests failed` lines, in that order, only when their counts are nonzero. Keep JUnit's detailed failure messages and stack traces on standard error and exit with status 1 on failure.

## Implementation

Build the summary from the existing `SummaryGeneratingListener` result in `TestLauncher`. Do not add dependencies or abstractions.

## Verification

Add a focused JUnit test beside `TestLauncher` that checks exact formatting with zero and nonzero exceptional counts. Run `java -ea tools/Build.java verify` after implementation.
