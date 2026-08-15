---
name: gh-cli
description: "GitHub API interaction via gh CLI — issues, PRs, releases, comments, workflows. Use when git alone cannot reach GitHub APIs or UI."
---

# GitHub CLI

Use `gh` for GitHub API/UI state that plain `git` cannot reach.

## Source of truth

Command shapes change. Check installed help first:

```bash
gh --version
gh auth status
gh issue --help
gh pr --help
gh run --help
gh api --help
gh search --help
```

Official docs: <https://cli.github.com/manual/>

## Safety

Default to read-only: list, view, status, search, logs. Ask before mutations:

- issue/PR create, edit, close, reopen, comment, label, assign
- PR merge, checkout, ready/draft changes
- release create/delete/upload
- workflow rerun/cancel/dispatch
- GitHub Pages enable/rebuild/workflow creation
- raw `gh api` calls with non-GET methods

Never print tokens. Do not script destructive bulk operations through generated
shell pipelines.

## Local helpers

Run from this skill directory or resolve paths relative to `SKILL.md`:

```bash
node scripts/gh_code_search.mjs "query" --language javascript --exclude-forks
node scripts/gh_failed_run.mjs --repo owner/repo --pretty
node scripts/gh_pages_deploy.mjs status owner/repo --json
```

Helpers are cross-platform Node `.mjs`; no other runtime required. Use
`npm test` for helper tests.

## Patterns

Read-only examples:

```bash
gh issue list --repo owner/repo --state open --limit 20
gh issue view 123 --repo owner/repo --json comments
gh pr view 456 --repo owner/repo --json commits,reviews,statusCheckRollup
gh run view --repo owner/repo --log-failed
gh release list --repo owner/repo --limit 10
gh search code "TODO" --owner org --limit 50
```

For JSON processing, prefer `gh --jq`/`--template` or Node helpers over piping
to jq.
