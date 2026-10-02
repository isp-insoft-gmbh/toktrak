---
harness: claude
model: fable
effort: high
schedule: [wed]
branch: golem/qa
os: ubuntu-26.04
timeout: 45m
verify: mise run verify
---

# QA maintenance

Improve the weakest valuable part of the test suite or quality tooling. Change
production code only when a new test exposes a real defect. Keep tests focused
on behavior and strengthen mutation resistance without weakening quality floors.
