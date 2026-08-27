---
id: TT-PLAN-O4MCTI2H
type: plan
title: Generate JDT LS project metadata
spec: TT-SPEC-Y_XMD0D1
status: approved
---

1. Test deterministic Eclipse project/classpath/preferences for production,
   tests, and build tooling.
2. Generate nested projects under `output/ide/eclipse` with linked real sources,
   Java 26, module-path/test semantics, qualified exports, UTF-8, and isolated
   compiler output.
3. Expose `mise run ide eclipse`, preserve clean ownership, and reject malformed
   selection before writes.
4. Verify generation from clean state, full build success, and manual repository
   import in JDT LS.

Eclipse-specific convenience beyond JDT LS needs is excluded.
