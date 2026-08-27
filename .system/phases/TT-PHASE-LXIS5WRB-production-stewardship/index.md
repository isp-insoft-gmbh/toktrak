---
id: TT-PHASE-LXIS5WRB
type: phase
title: Production stewardship
label: prod
---

## Sub-mission

Keep real-user TokTrak boring, durable, and explainable while still improving
projected-cost visibility.

## Priorities

1. Preserve production data and tracker continuity.
2. Keep UI changes rare, justified, and easy to verify.
3. Make every durable schema change cleanly migratable.
4. Keep `toktrak.mjs` auto-update safe and observable.
5. Prefer small reversible changes over broad redesign.

## Decision defaults

- Compatibility: existing production data remains readable and migratable.
- Migration: prove old and new shapes with corpus coverage before release.
- Stability: favor operational certainty over feature speed.
- Quality: run full local gates before any release candidate.
- UI: change only for clear user value, correctness, or safety.
- Tracker: preserve install, upload, uninstall, and auto-update behavior.
- Debt: fix only debt on the touched path unless explicitly scoped.

## Non-goals

- No dashboard redesign without a concrete user problem.
- No tracker behavior change without update-path verification.
- No schema shortcut that strands released data.
- No new data source beyond local `ccusage`.

## Exit criteria

- Production migration discipline is routine and tested.
- Tracker update safety is covered by release verification.
- UI changes have explicit reason and focused checks.
- Real-user operations stay predictable across releases.
