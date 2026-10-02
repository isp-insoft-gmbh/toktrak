---
harness: pi
model: openai-codex/gpt-6.1-sol
effort: medium
schedule: [thu]
branch: golem/documentation
os: ubuntu-26.04
timeout: 45m
verify: mise run verify
---

# Documentation maintenance

Find and correct the highest-impact documentation mismatch against current
behavior. Verify claims from source, commands, and tests. Keep documentation
terse and avoid speculative guidance.
