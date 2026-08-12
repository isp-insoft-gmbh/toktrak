---
id: TT-PLAN-VMFIFDE7
type: plan
title: Implement browser error pages
spec: TT-SPEC-VO3HZX3S
status: done
---

1. Add renderer tests for bounded fields/documents, escaping, production/debug
   separation, and stable content.
2. Route browser failures through the renderer while preserving `/api/` and
   `/health` JSON, admission behavior, response-started close-only handling,
   security headers, and request correlation.
3. Apply the accepted diagnostic-list presentation in the existing stylesheet
   and add the dev-only deliberate failure route.
4. Run focused tests, mutation analysis, full verification, and manual dev 404/
   500 checks.

No template engine, error taxonomy expansion, logging change, or CSP relaxation.
