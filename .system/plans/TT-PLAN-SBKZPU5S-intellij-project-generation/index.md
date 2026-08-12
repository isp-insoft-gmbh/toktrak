---
id: TT-PLAN-SBKZPU5S
type: plan
title: Generate IntelliJ project metadata
spec: TT-SPEC-Y_XMD0D1
status: done
---

1. Add build-tool tests for exact modules, source/test roots, dependencies,
   qualified exports, Java version, output isolation, deterministic XML, and
   user-file preservation.
2. Generate owned project/module XML for production, tests, and build tooling
   from authoritative build constants and resolved JARs.
3. Extend selective/bare IDE command routing and owned cleanup.
4. Verify clean generation, byte-identical regeneration, sentinel preservation,
   full build success, and manual IntelliJ import.

No project graph, run configuration, build delegation, or broad source root.
