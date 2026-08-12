---
id: TT-PHASE-DJF1AFW7
type: phase
title: My Tracker and workstation installer
spec: TT-SPEC-IXJLYK_K
status: done
---

Approved; depends on accepted server APIs and dashboard shell.

## Outcome

A user creates a token, downloads one personalized Node installer, installs a
daily user-scoped tracker on Windows/macOS/Linux, uploads usage, self-updates,
and uninstalls.

## Scope

- My Tracker token lifecycle, one-time plaintext/download, OS detection,
  instructions, and warnings.
- One Node `.mjs` with full/daily/uninstall modes, pinned `ccusage`, Pi path
  discovery, partial uploads, bounded retry/logging, and authenticated update.
- Native `systemd --user`, LaunchAgent, and `schtasks` integration without
  elevation.
- Unix permissions, Windows LocalAppData behavior, jitter, hash verification,
  and atomic replacement.
- Fake command/server and scheduler-generation tests.

No root install, cron, extra tracker files, or additional data sources.

## Done criteria

Install/full/daily/partial/retry/update/revoke/uninstall flows pass against fake
services; available native OS checks verify scheduled execution, logs, dashboard
updates, and clean uninstall.
