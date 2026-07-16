# Test Name Convention Design

## Goal

Name every test case so its precondition, behavior, and expectation are
explicit:

```text
given_<camelCaseContext>_when_<camelCaseBehavior>_then_<camelCaseExpectation>
```

Rename all existing JUnit test methods and manually invoked `BuildTest` cases.
Test helpers are outside the convention.

## Rule

Add this project instruction to `CLAUDE.md`:

```text
Name every test case `given_<camelCaseContext>_when_<camelCaseBehavior>_then_<camelCaseExpectation>`; helper methods are exempt.
```

Each segment begins with a lowercase ASCII letter and continues with ASCII
letters or digits. Underscores separate only `given`, `when`, and `then`
sections. Enforce this regular expression:

```text
given_[a-z][A-Za-z0-9]*_when_[a-z][A-Za-z0-9]*_then_[a-z][A-Za-z0-9]*
```

## JUnit Enforcement

`TestLauncher` validates all discovered tests before group filtering or
execution. Each test must expose JUnit `MethodSource` metadata and its method
name must match the convention. Failure identifies the invalid method and
prevents any tests from running.

Validation reuses JUnit's completed discovery; it performs no classpath or
source scan. Reject discovery plans above 20,000 total descriptors before
traversing them; the existing 10,000-test limit remains narrower for executable
tests.

## Build Tool Test Enforcement

`BuildTest` validates its declared methods before invoking cases. A build-tool
test case is structurally defined as a non-synthetic private static,
no-argument, `void` method. Synthetic lambda implementation methods are compiler
artifacts, not test cases. Existing helpers either accept arguments or return
values; change `requireAssertions` to accept its owning class so every
structurally selected method is a test case.

Reject more than 1,000 declared methods. An invalid name fails before the first
build-tool case runs.

The JUnit and build-tool suites keep separate regular-expression constants. They
compile in isolated test contexts; no shared production utility is justified.

## Refactor

Rename test cases only. Preserve test bodies, annotations, ordering, helper
names, and behavior. Update direct invocations in `BuildTest.main` with their
renamed methods.

Names describe:

- `given`: relevant initial world state;
- `when`: behavior under specification;
- `then`: expected result or state change.

## Verification

Add focused positive and negative naming-validator checks in both test contexts.
Run formatting, build-tool tests, `mise run verify`, and history-backed full
mutation testing because test methods across the suite changed.
