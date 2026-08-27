---
id: TT-PLAN-N9DVHZDA
type: plan
title: Implement bounded Java runtime and build
spec: TT-SPEC-VIFHAULH
status: approved
---

Implemented in three independently verified batches.

1. **Durable data:** add regression tests; stream log recovery/replay; unify
   projection transitions; enforce JSON/event/log limits and exact arithmetic.
2. **Runtime control:** bound HTTP admission, paths, bodies, writer completion,
   forced abort, application shutdown, and state transitions.
3. **Development control:** stream hashing, bound traversal/arguments/processes,
   kill process trees, enable assertions everywhere, and audit names/invariants.

Each batch keeps trust-boundary validation separate from assertions, deletes
superseded unbounded APIs, and ends with check/verify. Final acceptance also
links and smokes the production runtime warning-free.
