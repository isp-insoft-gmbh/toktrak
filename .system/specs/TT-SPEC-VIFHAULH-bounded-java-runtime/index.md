---
id: TT-SPEC-VIFHAULH
type: spec
title: Bounded Java runtime and build
---

## Intent

Every Java execution path must obey explicit limits, exact arithmetic, bounded
shutdown, and meaningful internal assertions without changing normal TokTrak
behavior.

## Requirements

- Stream event recovery/replay; never load the complete log.
- Bound log bytes, event count, line/document shape, JSON complexity, event
  fields, HTTP paths/bodies/admission, writer work, build traversal/files,
  generated arguments, subprocesses, and test discovery.
- Validate external input with runtime errors; assertions cover only internal
  pre/postconditions, invariants, ownership, and state transitions.
- Pair assertions across internal boundaries and enable `-ea` in build, test,
  dev, and production launches.
- Use `long` and exact arithmetic where sizes/counts can overflow.
- Distinguish writer-closed from writer-full behavior and complete every
  accepted future exactly once.
- Bound normal shutdown, then abort and fail uncertain writes without applying
  their in-memory projection; replay determines durable truth.
- Terminate timed-out subprocess trees, including descendants.
- Keep limits beside their owners; do not add runtime configuration without
  operational evidence.
- Delete superseded unbounded APIs; retain no compatibility layer.

## Acceptance

Streaming replay, sparse oversized input, count/shape limits, full-buffer
writes, HTTP saturation, writer abort, process timeout, assertion-enabled
launches, and warning-free production linking are directly tested.
`mise run check`, `mise run verify`, and `mise run prod` pass.
