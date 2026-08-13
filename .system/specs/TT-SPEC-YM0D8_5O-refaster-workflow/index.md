---
id: TT-SPEC-YM0D8_5O
type: spec
title: Refaster workflow
research:
  - TT-RESEARCH-RFASTR01
---

For three or more identical Java transformations, try one current documented
Refaster rule before manual repetition. Compile rules through the existing Java
build and cache deterministic output. `mise run refactor` applies rules then
formats; normal verification remains read-only. CI applies Refaster, requires a
clean tree, then verifies.

If expression/template replacement cannot safely represent the transformation,
record date, pattern, and reason in
[TT-RESEARCH-RFASTR01](../../research/TT-RESEARCH-RFASTR01-refaster-rule-failures/index.md),
abandon only that rule, and continue manually. Never force unsafe
null/assertion, symbol rename, arbitrary control-flow, or import
transformations.

No standalone build system, generic refactoring framework, warning suppression,
or compatibility route is allowed.

Acceptance covers rule resolution/compilation/application, cache invalidation,
idempotence, source deletion/rename, clean/dirty CI behavior, failure logging,
and a cold warning-free build.
