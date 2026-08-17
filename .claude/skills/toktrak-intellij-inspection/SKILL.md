---
name: toktrak-intellij-inspection
description: "Use when running TokTrak IntelliJ IDEA inspections, regenerating the triaged IntelliJ inspection report, or comparing IntelliJ findings against the accepted triage list."
---

# TokTrak IntelliJ inspection

- Use this only when IntelliJ inspections are explicitly requested. Prefer PMD
  and Error Prone for normal checks.
- Run from the repository root:
  `node .claude/skills/toktrak-intellij-inspection/scripts/run.mjs`.
- The runner is cross-platform and fails if IntelliJ IDEA's `inspect` executable
  is not installed or cannot be found. Set `TOKTRAK_INTELLIJ_INSPECT` to an
  explicit `inspect`, `inspect.sh`, or `inspect.bat` path when autodetection is
  insufficient.
- The runner enables the 98 triaged IDs in
  `references/triaged-inspection-ids.txt` and disables all known IDs from
  `references/intellij-inspection-ids.txt`.
- The runner first regenerates IntelliJ metadata with `mise run ide intellij`,
  temporarily moves `.idea/workspace.xml` aside because stale workspace state
  can make CLI inspections return only descriptions, then restores it after the
  run. It runs offline inspections over `sources/toktrak`,
  `tests/toktrak.tests`, `tests/tools`, and `tools`, and writes:
  - `output/intellij-inspections/run-*-triaged/report.md`
  - `output/intellij-inspections/run-*-triaged/findings.tsv`
  - `output/intellij-inspections/latest-triaged-run.txt`
- After the runner finishes, read and show the generated `report.md` path plus a
  short count summary. Then ask whether findings should be handled with the
  human **one by one**. Do not edit source code until Oliver explicitly says to
  handle findings.
- If IntelliJ reports `Only one instance of IDEA can be run at a time`, stop and
  ask Oliver to close IntelliJ before retrying.
