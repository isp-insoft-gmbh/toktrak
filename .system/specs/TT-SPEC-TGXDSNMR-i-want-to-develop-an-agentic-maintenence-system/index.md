---
id: TT-SPEC-TGXDSNMR
type: spec
title: Agentic maintenance system
---

## Intent

Maintain TokTrak through small, reviewable changes produced by the
organization's
[isp-golem-xiv App](https://github.com/isp-insoft-gmbh/isp-golem-xiv).

## Integration

- Task definitions live in `.golems/<name>.md`; `.golems/_golems.md` contains
  shared instructions, not a task.
- The installed organization App and these Markdown files are sufficient.
  TokTrak has no local golem engine, workflow, authentication cache, or
  activation variable.
- Definitions use the App's frontmatter contract: harness, provider, model,
  effort, runner, timeout, verification command, optional branch, schedule, and
  paying owner.
- Scheduled tasks use `owner: bugabinga` and Oliver's centrally registered
  provider seats. Tasks without a schedule or event trigger are manual-only.
- Native project harness configuration remains in use. Do not install or store
  provider authentication in TokTrak.
- The App's
  [specification](https://github.com/isp-insoft-gmbh/isp-golem-xiv/blob/trunk/SPEC.md)
  owns execution, seat selection, queueing, stop behavior, and reporting.

## Review

Agents edit the supplied repository snapshot and report actual results. The
external runner verifies changes, creates commits, and publishes a pull request
on the task branch; it never merges or repairs `trunk` directly. TokTrak CI and
human review decide whether to merge. Preserve product schemas, runtime
behavior, quality gates, and the anonymized corpus requirements in
`.system/RULES.md`.
