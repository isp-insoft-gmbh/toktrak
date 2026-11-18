---
branch: golem/java-canary
harness: pi
runner: ubuntu-26.04
provider: openai-codex
model: gpt-6.1-sol
effort: low
timeout: "10m"
verify: mise run check
---

# Runner canary

Manual-only, read-only check of the external runner's repository snapshot and
reporting path. Do not edit files, start services, install tools, or publish
anything. Read `CONTRIBUTING.md`, `mise.toml`, and `tools/Build.java`. Report
the repository's purpose, the check/test/verify ladder, and whether those files
agree about build ownership. Cite the relevant paths and identify any missing
prerequisites without exposing credentials or raw provider logs. Finish through
the runner's reporting protocol; a useful answer with no changes is success.
