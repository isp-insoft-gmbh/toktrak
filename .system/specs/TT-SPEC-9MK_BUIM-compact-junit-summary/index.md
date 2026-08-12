---
id: TT-SPEC-9MK_BUIM
type: spec
title: Compact JUnit summary
---

After every JUnit run, print duration, containers found, tests found, and tests
passed in that order. Print skipped, aborted, then failed only when nonzero.

Detailed failures and stack traces remain on stderr; any failure exits nonzero.
Use JUnit's existing summary result and add no dependency or abstraction.

Acceptance requires exact-format tests for ordinary and exceptional counts plus
full verification.
