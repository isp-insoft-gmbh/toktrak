---
id: TT-PLAN-6U7ODUXE
type: plan
title: Implement Refaster workflow
spec: TT-SPEC-YM0D8_5O
status: done
---

1. Test dependency resolution, rule compilation/cache behavior, source
   application, deletion/rename handling, and CI clean-tree enforcement.
2. Resolve Refaster separately, compile the repository rule source, serialize
   the rule, and invalidate cache from source/tool/dependency changes.
3. Expose explicit mutating `refactor`; keep verify read-only; make CI refactor,
   reject dirt, then verify.
4. Persist failed-rule evidence and apply the first safe repeated
   transformation.
5. Prove cold-build success, idempotence, and warning-free verification.

Do not add a new build system or force transformations outside Refaster's safe
expression/template model.
