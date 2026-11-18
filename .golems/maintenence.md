---
harness: pi
provider: openai-codex
model: gpt-6.1-sol
effort: high
owner: bugabinga
schedule: [sun]
branch: golem/maintenence
runner: ubuntu-26.04
timeout: "45m"
verify: mise run verify
---

# Project maintenance

Maintain overall project health.

- architecture
- jdk version
- testing strategy
- build system
- ci workflows

Pick one scope and improve it with a small vertical slice. If project health is
fine, report no change. Workflow or control-plane changes need human action; do
not merge dependency PRs.
