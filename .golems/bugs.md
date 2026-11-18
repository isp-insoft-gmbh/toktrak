---
harness: pi
provider: openai-codex
model: gpt-6.1-sol
effort: max
owner: bugabinga
schedule: [mon]
branch: golem/bugs
runner: ubuntu-26.04
timeout: "45m"
verify: mise run verify
---

# Bug maintenance

Find the highest-value reproducible defect and fix only that defect. First add a
focused test that fails for the demonstrated bug.
