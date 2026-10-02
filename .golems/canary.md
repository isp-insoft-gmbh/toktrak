---
branch: golem/java-canary
harness: pi
os: ubuntu-26.04
model: openai-codex/gpt-6.1-sol
effort: low
timeout: 10m
verify: mise run check
---

# Java Golem canary

This manual-only task tests the Java Golem publication path without changing
product behavior.

README.md names `.github/golems` and `_golem.md` as the current task policy.
Current tasks live in `.golems/`, with shared policy in `_golems.md`. Verify the
paths, then correct only that README.md sentence. Run the declared check and
commit the change. Write `$GOLEM_PR_FILE` identifying this as a canary requiring
human review. If README.md is already correct, make no change and explain why.
Never merge a PR, release, change runtime code, or edit CI or control-plane
files.
