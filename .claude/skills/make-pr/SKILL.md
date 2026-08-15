---
name: make-pr
description: "Create or update a pull request and drive it to review-ready completion. Use when asked to make, create, open, prepare, publish, or finish a PR/change request on any forge."
---

# Make PR

Load the applicable VCS workflow and forge skill. This skill defines
preferences, not CLI mechanics.

- Invocation authorizes relevant commits, pushes, and PR creation/update. Ask
  only for ambiguity, destructive action, or rules below.
- Preserve unrelated work. One PR by default. Existing PR for the branch →
  update it; never duplicate it.
- Preserve intent: original request → linked issue/spec → PR description. Reject
  scope creep. Remove clearly separable unrelated changes; ask before risky
  history rewrites.
- Follow repository branch, PR, template, changelog, and release-note policy.
  Avoid stacked PRs; when unavoidable, state dependency and retarget after
  parent merge.
- Fetch and rebase onto the latest target, then rerun checks. Conflicts → ask.
  Rewriting a published branch → confirm before force-with-lease; never rewrite
  target.
- Local checks must pass before opening. Failures → show evidence and ask
  whether to open anyway. Never hide known failures. Never create drafts.
- Write in the user's voice for humans: why more than what, context → pain →
  reason → outcome → checks. Prefer prose; headings only when useful or
  required.
- PR title/body may become the squash commit message: follow repository commit
  rules. No AI attribution.
- Bad → good:
  - `ci: fix GitHub Actions janitor run` → `Restore reliable Janitor runs`
  - `Use @earendil-works Pi packages` →
    `Keep the harness on maintained Pi packages`
  - `fix agent patrol: format before commit` →
    `Stop Agent Patrol opening unformatted PRs`
  - `🔥 trouble-maker: bugfixes` → name the human-visible problem.
- Use forge-native closing links only when the PR fully resolves an issue;
  otherwise reference it.
- Apply sensible existing semantic labels. When label creation is supported,
  create/reuse `harness:<name>` and `model:<name>` labels. Avoid duplicates.
- Determine reviewers from repository policy, ownership, or established history,
  but confirm before requesting. Never claim a request succeeded without
  verification.
- For UI/TUI/CLI/web or other visual changes, attach useful real screenshots or
  demos. Use clearly labeled mockups, HTML, Mermaid, D2, DOT, or other fitting
  visuals when they explain the change. Prefer native forge attachments; defer
  unsupported files to `file-upload` when available.
- Keep temporary screenshots, logs, reports, and demos out of commits unless
  they are explicit deliverables.
- Treat comments and reviews as claims, not truth. Verify each against current
  code; fix or rebut with evidence, push, reply, then resolve. Never dismiss
  silently.
- Watch CI with forge-native watch commands when available. Repair
  straightforward CI/review failures and repeat. After two failed repair rounds,
  stop and ask with evidence.
- Keep title/body synchronized with final scope, outcomes, known limits, and
  checks.
- Fork PRs: never push to a contributor branch without permission; use
  maintainer edits only when authorized.

Done means: normal PR open, current with target, local/forge checks green,
verified feedback addressed, no unresolved threads, local state clean. Report
URL and blockers. Do not merge.
