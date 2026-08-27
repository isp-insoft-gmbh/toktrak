---
id: TT-PLAN-TEDN2X4R
type: plan
title: Implement durable server core
spec: TT-SPEC-IXJLYK_K
status: approved
---

1. Establish Java/JPMS dependency resolution, compilation, tests, and linked
   runtime gates.
2. Add bounded config, clock, JSON, and compact structured logging.
3. Add request context, bounded HTTP admission/routing/body handling,
   browser/API errors, security headers, and health.
4. Add data locking, validated event envelopes, streaming replay, torn-tail
   recovery, and corruption failure.
5. Add one-pass projection state, snapshots, bounded single writer, fsync
   acknowledgement, and completion semantics.
6. Add degraded health, disposable corpus dev mode, pinned clock, and write
   failure injection.
7. Prove restart, lock exclusivity, queue saturation, shutdown, linked runtime,
   and healthy/degraded manual behavior.

Keep later identity, usage, dashboard, tracker, and packaging behavior absent.
Each slice is test-first, assertion-enabled, bounded, warning-free, and leaves
no compatibility API.
