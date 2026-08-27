---
id: TT-ISSUE-JCQ6LFNY
type: issue
title: Protect golem secrets with a GitHub Environment
specs:
  - TT-SPEC-TGXDSNMR
---

## Symptom

Golem secrets are repository-scoped. The default-branch guard is workflow code,
not a platform secret boundary.

## Impact

A trusted workflow edit can expose long-lived bootstrap and upload credentials
outside the intended `trunk` jobs.

## Evidence

GitHub cannot copy existing repository secrets into an Environment because
secret values are write-only. Migration requires re-entering the App private key
and Claude setup token.

## Done

- Create a `golem` Environment restricted to `trunk`, without required
  reviewers.
- Move golem secrets into it and delete their repository-scoped copies.
- Bind golem and reseed jobs to the Environment.
- Verify scheduled, manual, and reseed access.
