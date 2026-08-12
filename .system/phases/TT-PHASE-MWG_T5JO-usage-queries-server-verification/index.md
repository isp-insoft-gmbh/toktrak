---
id: TT-PHASE-MWG_T5JO
type: phase
title: Usage queries and server verification
spec: TT-SPEC-IXJLYK_K
status: done
---

Approved; depends on identity and tracker tokens.

## Outcome

The headless backend ingests raw `ccusage` snapshots, rebuilds accurate
analytics, exposes protected queries, emits safe SSE updates, and retains
last-good FX state.

## Scope

- Bounded authenticated uploads with partial-report health and schema
  preservation.
- Timestamped snapshot upserts, stale/future handling, and canonical
  daily/session/blocks authority.
- Analytics/query projections, FX events/fallback, and safe patch/signals SSE.
- Sanitized mixed-state corpus generated only through implemented readers and
  verified against exact totals.
- HTTP integration and human server checkpoint.

Visual dashboard, My Tracker, workstation installation, and release packaging
remain out.

## Done criteria

Concurrent/repeated/stale/partial uploads, unknown fields, canonical totals, FX
fallback, SSE, degraded mode, corpus replay, restart, and protected API checks
pass.
