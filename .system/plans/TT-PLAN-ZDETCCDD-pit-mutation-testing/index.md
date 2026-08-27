---
id: TT-PLAN-ZDETCCDD
type: plan
title: Implement PIT mutation testing
spec: TT-SPEC-IMJTN1JO
status: approved
---

1. Test source-to-class selection, dependency integrity, fixed arguments,
   reports, cleanup, paths, and process termination.
2. Resolve PIT tooling separately; compile authoritative modules; replace the
   report tree; run bounded mutation workers; validate HTML/XML.
3. Remove application/test resource warnings at their root without suppression.
4. Exercise focused mutations across each production area and strengthen tests
   where survivors expose meaningful gaps.
5. Run full mutation analysis and verification; retain only reports and optional
   history under owned output.

PIT stays standalone and does not gate normal check/test/verify/CI by score.
