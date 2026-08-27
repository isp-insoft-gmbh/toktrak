---
id: TT-ISSUE-4XHR8BXK
type: issue
title: Indicate signed-in state on home page
specs: []
---

## Symptom

`/` always shows “Sign in,” even when the browser has a valid session.

## Impact

Returning from an error page appears to sign the user out.

## Evidence

1. Sign in during development.
2. Visit `/debug/error`.
3. Follow “Return to TokTrak.”
4. `/` shows “Sign in,” while `/tokens` still authenticates.

## Expected

Minimally indicate the active session on `/`, preferably with a “My Tracker”
link. Do not change authentication or session behavior.

## Resolution

`/` now validates any existing session and conditionally shows either “My
Tracker” or “Sign in.” Invalid or absent sessions remain signed out.
