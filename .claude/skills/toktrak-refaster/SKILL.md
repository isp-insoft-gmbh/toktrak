---
name: toktrak-refaster
description: "Use when a TokTrak Java change requires three or more identical code-pattern transformations, or when editing, applying, or diagnosing project Refaster rules."
---

# TokTrak Refaster

For fewer than three identical transformations, edit directly.

For three or more:

1. Inspect every site and confirm the transformation has identical syntax and
   semantics, including trust-boundary behavior.
2. Read `.system/research/TT-RESEARCH-RFASTR01-refaster-rule-failures/index.md`.
   If the pattern already failed, fetch current Refaster documentation and retry
   only when its upgrade trigger is satisfied.
3. Otherwise fetch current Refaster documentation and known limitations, then
   attempt one narrow rule in `tools/refaster/Rules.java` before manual edits.
4. Run `mise run refactor`, inspect every changed site, and reject any broadened
   or behavior-changing match. Run it again to prove idempotence.
5. If the rule is impossible or unsafe, append the date, pattern, attempt,
   reason, outcome, and upgrade trigger to
   `.system/research/TT-RESEARCH-RFASTR01-refaster-rule-failures/index.md`;
   abandon only the rule and continue the original task directly.
6. Run focused tests, then the task-required project gates.

Never suppress warnings, weaken validation, or force a rule across semantically
different sites.
