# TokTrak

[![TokTrak — Internal Coding Harness Token Tracker][toktrak-logo]][toktrak]

[toktrak-logo]: sources/toktrak/assets/public/logo-lockup-dark.svg
[toktrak]: https://toktrak.isp-insoft.de

## Why

AI coding subscriptions hide effective consumption behind subsidized flat
pricing. TokTrak gives teams useful projected-cost visibility without pretending
to provide complete billing data or monitoring work content.

TokTrak observes only local coding-harness usage reported by `ccusage`. Cloud,
web, and CI usage are outside its view.

## What

TokTrak is one small web service and one workstation tracker.

The Java 26+ service authenticates company users through OAuth SSO, issues
personal tracker tokens, and presents team totals, averages, leaderboards,
trends, and USD/EUR estimates through a live server-rendered dashboard.

The tracker is one plain Node `.mjs` using the standard library, a pinned
`ccusage`, and the native user scheduler:

| OS      | Scheduler        |
| ------- | ---------------- |
| Windows | `schtasks`       |
| macOS   | LaunchAgent      |
| Linux   | `systemd --user` |

It installs without root or administrator access and uploads daily.

## Contribute

See [CONTRIBUTING.md](CONTRIBUTING.md) for platform requirements, repository
layout, generated-code boundaries, and verification commands.

## Install the tracker

1. Install Node.js.
2. Sign in at <https://toktrak.isp-insoft.de>.
3. Create a tracker token.
4. Download and run the generated installer for your OS.

The installer contains the one-time token. Do not share it. If it is lost,
revoke it and create another.

## Maintain with golems

Local golems run one bounded maintenance task through Pi, Claude Code, or Codex
CLI. Task definitions live in `.github/golems`; `_golem.md` supplies shared
instructions. Each task explicitly declares its harness, model, thinking level,
and weekday. `mise run check` validates their strict structure offline.

Install and authenticate all three CLIs with their provider subscriptions:

- Pi and Codex CLI use ChatGPT Pro authentication. Pi models must use the
  `openai-codex/` provider; metered API fallback is forbidden.
- Claude Code uses Claude Max authentication.
- `gh` uses an account authorized for this repository.

Then validate every configured combination and run one task:

```sh
mise run golem-check
mise run golem bugs
```

A run uses the exact branch `golem/<task-id>`. It resumes one matching open pull
request or removes a stale dedicated branch before fresh work. No useful change
creates no remote state. Useful work is committed, checked for protected paths,
published with guarded branch updates, labeled, and accepted only after the
final `CI / ci` succeeds. Harness sessions are always ephemeral.

Run from the clean, current default branch. On failure, inspect the printed
error, the ignored `output/golems/<task-id>` worktree, its dedicated remote
branch, and any open pull request. Resolve concurrent branch changes or review
threads, then rerun the same task. Never repair failure by pushing `trunk`,
merging the pull request, or editing `.system`, `.github/golems`, `.claude`,
`.agents`, `.codex`, or `.pi` from a golem branch.

Optional public review evidence uses only the seeded development corpus. Set
`GATEBRIDGE_R2_ACCESS_KEY_ID`, `GATEBRIDGE_R2_SECRET_ACCESS_KEY`, and
`GATEBRIDGE_R2_ENDPOINT` in the parent environment. The harness cannot read
these credentials; the parent uploads files from `output/golem-evidence` after
it exits.

To change or add a task, edit one lowercase kebab-case `.md` file, run
`mise run check`, then `mise run golem-check`. Tracked `.claude/skills` are
canonical; Pi points to them through `.pi/settings.json`, Claude discovers them
directly, and Codex follows the tracked `.agents/skills` bridge.

## Operate

See [OPERATIONS.md](OPERATIONS.md) for production configuration, rootless Podman
deployment, backups, diagnostics, upgrades, rollback, and release.

## License

[MIT](LICENSE)
