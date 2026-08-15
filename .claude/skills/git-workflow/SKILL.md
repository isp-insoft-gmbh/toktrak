---
name: git-workflow
description: "Use for Git repository workflows: committing changes, rebasing/pushing fast-forward, landing branches, or reviewing PRs. Load when a prompt says git workflow or when jj is not active."
---

# Git Workflow

Use Git only after `jj root` fails. Preserve unrelated dirty work. Never
force-push.

## Common rules

- Inspect before mutating: `git status --short --branch`.
- Stop on merge/rebase/cherry-pick conflicts unless the user explicitly asks to
  continue.
- Read untracked files before staging them.
- Exclude generated/cache/secret files. Ask only if ambiguous.
- Run obvious fast checks when available; skip expensive/unclear checks with
  reason.
- No AI attribution/trailers.

## Default branch

Detect dynamically:

```sh
git symbolic-ref refs/remotes/origin/HEAD 2>/dev/null | sed 's|refs/remotes/[^/]*/||'
```

If empty: `git remote set-head origin --auto`, retry. Fallback: first existing
local or remote ref from `trunk`, `main`, `master`, `develop`. If none: stop and
ask.

## Commit (`ci`)

1. Inspect: `git status --short --branch`, `git diff --stat && git diff`, and if
   staged `git diff --cached --stat && git diff --cached`.
2. Stop if clean.
3. Split unrelated changes into focused commits when safe; otherwise ask.
4. Stage relevant files only.
5. Commit. If hooks change files, inspect, restage, retry once.
6. Do not push.
7. Report hash, subject, checks.

### Commit message

- Short, concrete subject. Subject-only OK.
- Prefer `add`, `fix`, `update`, `remove`, `replace`, `track`, `show`, `rework`.
- Use `type(scope): summary` when natural: `feat`, `fix`, `docs`, `chore`,
  `refactor`, `test`, `perf`, `build`, `ci`.
- Use domain prefix when clearer: `ui: ...`, `db: ...`, `cli: ...`, `docs: ...`.
- Lowercase after prefix. No final period.
- Body only when useful: bullets, rationale, tests.
- Preserve issue IDs. If exactly one issue is referenced in conversation history
  or branch name, include `closes #<N>`. Multiple issues → ask which. None →
  omit.

## Fast-forward branch (`ff`)

Goal: rebase current branch onto target/default branch and push current branch.
Linear history only.

Args: `$1` may be target branch if it names an existing local/remote ref.
Remainder is test command.

1. Preflight: clean tree, no ongoing operation, not detached HEAD. Record branch
   and remote (`origin`).
2. Fetch: `git fetch --prune --tags <remote>`.
3. Rebase onto target: record `git rev-list --count <remote>/<target>..HEAD`;
   warn if >50; run `git rebase <remote>/<target>`; compare count after and warn
   if fewer commits.
4. Conflicts → stop; show files, status, next commands.
5. Test: run provided command or infer cheap checks.
6. Push: `git push <remote> <branch>`. If rejected: fetch, rebase once, retest,
   retry once. Never force-push.
7. Report branch, target, remote, rebase yes/no, tests, pushed SHA, follow-up.

## Land branch (`lm`)

Goal: fast-forward target/default branch to current branch tip and push target.

1. Preflight: clean tree, no ongoing operation, on a branch that is not target.
   Already on target → tell user to use `ff`.
2. Fetch: `git fetch --prune --tags <remote>`.
3. Verify ancestor: `git merge-base --is-ancestor <remote>/<target> HEAD`. Fail
   → target has commits not in current branch; tell user to run `ff` first.
4. Warn if branch commits lack `closes #<N>` but conversation mentions an issue.
5. Test: run provided command or infer cheap checks.
6. Fast-forward target:
   `git update-ref refs/heads/<target> HEAD <old-target-sha>`.
7. Push: `git push <remote> <target>`. If rejected: fetch, verify ancestor,
   retry once.
8. Offer to delete landed branch.
9. Report branch → target, old/new target SHA, tests, pushed SHA, follow-up.

## PR review (`pr`)

Do not merge until explicit user OK.

1. Resolve PR from argument or conversation. If none: ask.
2. Detect forge CLI from remote URL: `github.com` → `gh`; `codeberg.org` or
   Forgejo → `fj`; bare git server → generic Git. Missing CLI → generic Git.
3. Resolve metadata: title, author, base, head, URL, status, CI, labels.
4. Protect local work: dirty unrelated work → prefer isolated `git worktree`.
5. Fetch latest base/head.
6. Checkout via worktree when dirty or isolation needed; otherwise branch
   checkout when clean. Use forge CLI for metadata/merge only; use Git-native
   fetch/checkout for files.
7. Review diff: `git diff <base>...<head>`. Read relevant files, tests, docs,
   configs. Check correctness, security, perf, API compat, migrations, UX, error
   handling, issue match.
8. Test provided command or infer from project files.
9. Report summary, checkout location, base/head SHAs, blockers/warnings/notes
   with file:line refs, tests, readiness `READY|NOT READY`, exact merge plan.
10. Wait. On explicit OK: re-fetch, verify current/mergeable, run critical
    checks, merge via forge CLI or Git-native fast-forward preferred, report
    final status. Offer cleanup.
