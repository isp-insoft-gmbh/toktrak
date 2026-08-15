---
id: TT-PHASE-QMSEORWV
type: phase
title: CI golem automation
spec: TT-SPEC-TGXDSNMR
status: draft
depends_on:
  - TT-PHASE-QOEO5T6G
---

## Intent

Run the proven local golem lifecycle periodically in GitHub Actions with thin,
secure CI glue.

## Boundary

This is phase 2 of 2.

Included:

- Daily and manual workflow dispatch.
- Globally serialized, 45-minute golem jobs on Blacksmith.
- Pinned harness installation and runner-tool validation.
- GitHub App authentication and bot identity.
- Subscription-auth restoration, refresh persistence, encryption, and manual
  reseeding.
- Step-scoped secret isolation.
- Job summaries, pull-request metadata, checks, and Gatebridge evidence.
- CI maintainer documentation and live workflow proof.

Excluded:

- Reimplementing behavior already owned by local orchestration.
- Provider API billing.
- Branch-protection upgrades or external credential brokers.

## Steps

1. Add one thin workflow that delegates task discovery, validation, and
   execution to repository-owned orchestration.
2. Dispatch due tasks daily at `09:17 UTC`, support explicit manual task runs,
   and serialize every golem job.
3. Install pinned Pi, Claude Code, and Codex CLI distributions; validate the
   runner-provided `gh` version.
4. Mint the short-lived repository-scoped GitHub App token and configure bot Git
   identity.
5. Restore and decrypt Pi or Codex auth state, or provide the Claude setup
   token, without exposing bootstrap secrets to the harness.
6. Add explicit manual reseeding that verifies credentials and stores a fresh
   encrypted baseline without running a golem.
7. Run the local lifecycle with the selected task, 45-minute timeout, full
   history, runtime provider auth, and App token.
8. Persist rotated auth even after later failure; verify target immutability,
   protected paths, final PR head, and `CI / ci`.
9. Upload publicly safe evidence through Gatebridge after the harness exits and
   maintain deterministic labels, metadata, and job summaries.
10. Document CI setup, secrets, variables, dispatch, reseeding, and recovery in
    `README.md`.
11. Prove scheduled, manual, changed, no-change, failure, and recovery paths.

## Dependencies

- TT-PHASE-QOEO5T6G is approved and done.
- The TokTrak GitHub App is installed with the specified repository permissions.
- Subscription seeds, Claude setup token, cache-encryption key, and Gatebridge
  R2 credentials are configured in repository secrets and variables.
- Blacksmith provides the expected Ubuntu runner image and `gh`.

## Risks

- The unprotected target branch remains writable and mergeable by the App token;
  explicit rules and postflight detection do not prevent misuse.
- Cache eviction or token rotation can require manual reseeding.
- Runtime provider auth and the App token remain readable by permissive harness
  tools.
- Public evidence can leak data if development-only sanitization fails.
- Harness, model, runner-image, or provider changes can break unattended runs.
- A 45-minute timeout can interrupt work or final CI verification.

## Done criteria

- Scheduled and manual dispatch select only complete, valid task definitions.
- All jobs are globally serialized and stop within 45 minutes.
- Pi, Claude Code, and Codex CLI each complete a real CI invocation with the
  configured subscription, model, and thinking level.
- A no-change run succeeds without creating repository noise.
- A changed run creates or updates exactly one task PR with correct labels,
  metadata, review handling, optional evidence, and passing `CI / ci` on its
  final head.
- Rotated Pi and Codex auth survives successful and failed runs; missing or bad
  cache state fails closed; manual reseeding restores operation.
- Claude token expiry produces a clear renewal failure.
- Bootstrap, encryption, App-private-key, and Gatebridge secrets are absent from
  the harness environment.
- Protected-path and target-branch violations fail loudly and block later agent
  execution.
- README instructions are sufficient for another maintainer to operate and
  recover CI.
- `mise run ci`, `mise run coverage`, and `mise run prod` pass with a clean
  tree.
- Human approval marks this phase done.
