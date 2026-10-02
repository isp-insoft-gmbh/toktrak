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

Inspect README.md for one small, factual clarification about the tracker's
local-only usage scope or estimated cost totals. If no clarification is
justified by the current documentation, make no change and explain why.
Otherwise, edit README.md only, verify the exact claim against the current code
and documentation, run the declared check, and commit the change. Write a
concise pull-request description to `$GOLEM_PR_FILE` that explicitly identifies
the change as a Java Golem canary requiring human review. Never merge the PR,
create a release, change runtime code, or edit CI and control-plane files.
