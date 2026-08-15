---
id: TT-PHASE-QOEO5T6G
type: phase
title: Local golem foundation
spec: TT-SPEC-TGXDSNMR
status: done
---

## Intent

Deliver and prove the complete local golem workflow before adding CI execution.

## Boundary

This is phase 1 of 2.

Included:

- Production-schema compatibility rule and development-corpus expectations.
- Shared golem instructions, task definitions, promoted skills, and strict
  validation.
- Repository-owned orchestration and harness adapters.
- Authenticated local model validation.
- Local branch, pull-request, review, check, metadata, and evidence lifecycle.
- Local maintainer documentation.

Deferred to phase 2:

- GitHub Actions scheduling and manual dispatch.
- GitHub App token setup in workflows.
- Encrypted authentication caches and reseeding.
- CI secret isolation, summaries, timeout, and evidence-upload steps.

## Steps

1. Establish the schema-compatibility rule and development-corpus contract.
2. Add `_golem.md` and the four complete initial task definitions.
3. Promote the required Git, GitHub, pull-request, and file-upload skills.
4. Add strict structural validation to the normal build check.
5. Add authenticated validation for every configured harness, model, and
   thinking combination.
6. Add ephemeral, permissive adapters for Pi, Claude Code, and Codex CLI.
7. Add the deterministic local task lifecycle: identify, prepare, invoke,
   verify, summarize, and recover.
8. Cover branch reuse, fresh branches, rebasing with force-with-lease, protected
   paths, reviews, final-head checks, labels, metadata, and Gatebridge evidence.
9. Document local setup, validation, execution, and recovery in `README.md`.
10. Verify every local path before approving phase 2.

## Dependencies

- Existing TokTrak build and quality gates remain authoritative.
- Local Pi, Claude Code, Codex CLI, GitHub, subscription, and optional
  Gatebridge authentication are available for live validation.

## Risks

- Harness CLI or model-catalog drift breaks adapters.
- Permissive local agents can access credentials and mutate the unprotected
  target branch despite explicit prohibitions.
- Real GitHub lifecycle tests can leave branches, pull requests, comments, or
  uploaded evidence behind.
- Semantic prompt rules remain weaker than enforced orchestration checks.

## Done criteria

- `mise run check` rejects every invalid task-definition shape with precise
  context.
- Authenticated local validation proves every configured harness, model, and
  thinking combination.
- Each harness completes ephemeral no-change and changed-task paths without
  persisted sessions.
- Local orchestration proves fresh and existing pull-request paths, review
  resolution, final-head CI verification, protected-path blocking, target
  mutation detection, and recoverable failure.
- Gatebridge evidence is publicly safe, embedded without repository artifacts,
  and uploaded without exposing credentials to the harness.
- Schema changes are tested against the anonymized development corpus as
  production-compatible changes.
- `README.md` is sufficient for local maintainers.
- `mise run verify` passes.
- Human approval marks this phase done before CI phase implementation begins.
