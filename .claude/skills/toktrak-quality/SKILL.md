---
name: toktrak-quality
description: "Use when adding or updating TokTrak Java tests, Selfie snapshots, JaCoCo coverage, PIT mutation tests, or their CI reports."
---

# TokTrak quality

- Prefer precise assertions for invariants; use Selfie for stable, reviewable
  structured output such as HTML, JSON, and protocols.
- A mismatch proves only that output changed. Map its exact diff to the code
  producing it: approve only when an intentional renderer change explains every
  changed fragment. An unchanged renderer, unexplained effect, nondeterminism,
  or broken security/accessibility invariant is a potential bug—investigate it.
- For one expected change, replace `toMatchDisk()` with `toMatchDisk_TODO()`,
  run `mise run test <path>`, inspect the generated `.ss` diff against the
  renderer change, and commit the golden file plus Selfie's rewritten Java. Use
  `//selfieonce` for a file; never commit update markers.
- Keep timestamps, random IDs, ports, secrets, and machine paths out of
  snapshots; normalize at the test boundary.
- Run `mise run coverage` after changing tests; open
  `output/coverage/report/index.html`. Do not weaken coverage floors.
- When editing tests, run `mise run pit --history -- <production paths>` and
  inspect `output/mutations/index.html`. Delete `output/pit.history` and rerun
  without history if results are inconsistent.
- Before pushing, run `mise run ci`. CI keeps Selfie readonly and publishes
  coverage/PIT summaries plus downloadable HTML reports.
