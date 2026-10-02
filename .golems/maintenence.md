---
harness: pi
model: openai-codex/gpt-6.1-sol
effort: high
schedule: [sun]
branch: golem/maintenence
os: ubuntu-26.04
timeout: 45m
verify: mise run verify
---

# Project maintenance

Maintain overall project health.

- architecture
- jdk version
- testing strategy
- build system
- ci workflows

For each task, pick one scope, vertically sliced and improve. If project health
is fine, review and merge dependabot PRs.
