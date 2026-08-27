---
id: TT-ISSUE-RQSHIZT2
type: issue
title: Dashboard live updates blocked by CSP
specs:
  - TT-SPEC-IXJLYK_K
---

## Symptom

Overview and Visualizations throw Datastar `GenerateExpression` during page
initialization. No request reaches `/api/stream`.

## Impact

Dashboard live updates never start. Displayed usage remains stale until manual
reload.

## Evidence

1. Run `mise run dev` and sign in.
2. Open `/` or `/visualizations` in Chrome.
3. Observe CSP rejecting Datastar expression evaluation because `unsafe-eval` is
   forbidden by `default-src 'self'`.
4. Confirm the page makes no `/api/stream` request.

The regression began in `c5f0999`, which added Datastar expressions under the
pre-existing strict CSP.

## Expected

Dashboard live updates work with the spec-approved Datastar `unsafe-eval`
exception while every other CSP restriction remains enforced.

## Resolution

HTML responses allow pinned same-origin Datastar to evaluate expressions. Other
response types retain strict CSP. The stream endpoint accepts Datastar's empty
signal payload, so Overview and Visualizations start live requests without
browser errors.
