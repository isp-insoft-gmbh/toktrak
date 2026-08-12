---
name: toktrak-quality
description: "Use when adding or updating TokTrak Java tests, Selfie snapshots, JaCoCo coverage, PIT mutation tests, or their CI reports."
---

# TokTrak quality

- Prefer precise assertions for invariants; use Selfie for stable, reviewable
  structured output such as HTML, JSON, and protocols.
- Create one snapshot with `toMatchDisk_TODO()`, run the narrow test via
  `mise run test <path>`, then inspect and commit the generated `.ss` file and
  rewritten Java.
- Update one snapshot with `_TODO`; update a file with `//selfieonce`. Never
  commit `_TODO`, `//selfieonce`, or `//SELFIEWRITE`.
- Keep timestamps, random IDs, ports, secrets, and machine paths out of
  snapshots; normalize at the test boundary.
- Run `mise run coverage` after changing tests; open
  `output/coverage/report/index.html`. Do not weaken coverage floors.
- When editing tests, run `mise run pit --history -- <production paths>` and
  inspect `output/mutations/index.html`. Delete `output/pit.history` and rerun
  without history if results are inconsistent.
- Before pushing, run `mise run ci`. CI keeps Selfie readonly and publishes
  coverage/PIT summaries plus downloadable HTML reports.
