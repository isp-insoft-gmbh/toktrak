---
harness: codex
model: gpt-6-astra
effort: max
schedule: [tue]
branch: golem/security
os: ubuntu-26.04
timeout: 45m
verify: mise run verify
---

# Security maintenance

Perform authorized defensive maintenance of TokTrak's own source. Harden the
highest-risk reachable trust boundary and prove the need with one minimal local
regression test using only disposable data and loopback services. Do not scan
external systems or produce reusable attack instructions. Exclude credentials
and sensitive audit details.

Explain the risk in pull-request prose before naming technical terms. State who
can act, how their input reaches TokTrak, which safeguard it bypasses, and the
concrete consequence. Name attack patterns only after that plain-language
explanation. State affected modes and unaffected production paths. Keep enough
detail for review without providing reusable exploit steps.
