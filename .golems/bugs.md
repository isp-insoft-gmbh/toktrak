---
harness: pi
model: openai-codex/gpt-6.1-sol
effort: max
schedule: [mon]
branch: golem/bugs
os: ubuntu-26.04
timeout: 45m
verify: mise run verify
---

# Bug maintenance

Find the highest-value reproducible defect and fix only that defect. First add a
focused test that fails for the demonstrated bug.
