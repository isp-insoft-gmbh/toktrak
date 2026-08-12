---
id: TT-PHASE-IA2KNJE_
type: phase
title: Production packaging and release
spec: TT-SPEC-IXJLYK_K
status: done
---

Approved; follows the complete product workflow.

## Outcome

TokTrak builds reproducibly into a minimal linked runtime/container and releases
through one verified mise task with complete deployment guidance.

## Scope

- Dev/test/prod linked runtimes, app inclusion, deterministic layers, minimal
  image, and mounted data directory.
- Rootless Podman guidance, required environment, secret generation, backup
  ownership, and proxy/TLS boundaries.
- Integer versioning, changelog gate, tests/build/container checks, tagging, and
  private-registry push.
- Final documentation and release smoke verification.

No Docker-specific support, application TLS/compression, backup feature,
database migration, or public APIs.

## Done criteria

A clean checkout verifies, links, and builds the container without corpus data.
A rootless mounted-data smoke passes. Dry release proves ordering without
external mutation; real release remains explicit and validates everything before
tagging or pushing.
