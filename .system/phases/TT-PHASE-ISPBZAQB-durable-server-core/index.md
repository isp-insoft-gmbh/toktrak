---
id: TT-PHASE-ISPBZAQB
type: phase
title: Durable server core
spec: TT-SPEC-IXJLYK_K
status: done
---

Implemented 2026-08-12.

## Outcome

An assertion-enabled headless Java service starts under dev auth, exposes
health/error behavior, and durably records/rebuilds projections from its event
log.

## Scope

- JPMS build and linked runtimes.
- Bounded JDK HTTP execution, request context, logging, errors, and security
  headers.
- Exclusive data lock, event envelopes, streaming recovery/replay, snapshots,
  one writer, fsync acknowledgement, and corruption handling.
- Healthy/degraded state, disposable corpus dev data, pinned dev clock, and
  write-failure injection.
- Focused persistence, concurrency, limit, restart, shutdown, and jlink tests.

Identity, usage semantics, dashboard, tracker, and release packaging remain in
later phases.

## Done

Build, verification, linked-runtime smoke, restart/replay, queue saturation,
torn-tail, lock, corruption, fsync, health, and manual healthy/degraded checks
pass.
