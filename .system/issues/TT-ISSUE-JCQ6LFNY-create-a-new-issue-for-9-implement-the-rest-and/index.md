---
id: TT-ISSUE-JCQ6LFNY
type: issue
title: Protect golem secrets with a GitHub Environment
specs:
  - TT-SPEC-TGXDSNMR
---

## Resolution

Superseded by migration to the organization's `isp-golem-xiv` App. TokTrak no
longer has a golem workflow, local runner, or authentication-cache consumer.
Provider seats and execution credentials belong to the central ops repository;
no TokTrak `golem` Environment or reseed mechanism is needed. This removes the
obsolete consumers, not stored repository secret values.
