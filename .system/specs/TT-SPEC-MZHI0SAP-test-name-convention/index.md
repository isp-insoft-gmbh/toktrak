---
id: TT-SPEC-MZHI0SAP
type: spec
title: Test name convention
---

Every JUnit and manually invoked build-tool test case is named:

```text
given_<camelCaseContext>_when_<camelCaseBehavior>_then_<camelCaseExpectation>
```

Each segment starts with a lowercase ASCII letter and continues with ASCII
letters/digits. Helpers are exempt.

JUnit validates discovered method metadata before filtering or execution.
Build-tool tests validate structurally selected private static no-argument void
methods before invoking any case. Both traversals are bounded and fail with the
invalid method identified.

Acceptance covers positive/negative validators and complete renamed suites
without behavior changes.
