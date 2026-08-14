---
id: TT-ISSUE-K2YPYFD8
type: issue
title: Missing navigation between pages
specs:
  - TT-SPEC-IXJLYK_K
status: done
---

## Symptom

Navigation is inconsistent. Home, dashboard, tracker-token, created-token,
scope, and visualization pages do not all link to each other or provide a
reliable path back.

## Impact

Users reach dead ends or must edit the URL to move between product areas.

## Evidence

1. Sign in from `/`.
2. Visit `/tokens` or create a token.
3. Attempt to reach Overview, Visualizations, or Data scope through navigation.
4. Navigation is absent; dashboard navigation also omits My Tracker.

## Expected

Every authenticated page provides one consistent primary navigation with a clear
current-page state and routes to every product area.

## Resolution

Overview, Visualizations, My Tracker, and Data scope now share primary
navigation. Tracker-token and created-token pages mark My Tracker as current.
