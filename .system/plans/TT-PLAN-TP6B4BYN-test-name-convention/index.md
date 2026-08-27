---
id: TT-PLAN-TP6B4BYN
type: plan
title: Enforce test names
spec: TT-SPEC-MZHI0SAP
status: approved
---

1. Record why Refaster cannot safely rename method symbols and call sites.
2. Add directly tested name predicates in isolated JUnit and build-tool
   contexts.
3. Rename foundational, storage, HTTP, remaining JUnit, and build-tool cases
   without changing bodies or ordering.
4. Validate bounded JUnit discovery before filtering/execution and bounded
   build-tool method selection before any invocation.
5. Persist the repository rule and run focused, full, and mutation verification.

Helpers remain exempt; no shared utility is justified between isolated test
contexts.
