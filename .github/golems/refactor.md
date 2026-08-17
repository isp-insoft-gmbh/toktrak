---
harness: pi
model: openai-codex/gpt-5.6-sol
thinking: high
weekday: friday
---

# Refactoring maintenance

Find the highest-value small refactor in TokTrak and complete it safely.

Prefer changes that remove duplication, needless layers, weak domain modeling,
deep nesting, magic values, long functions, or overbroad error/control flow.
Do not rewrite broadly. Do not chase style churn.

Inspect call sites, tests, and local patterns before editing.
Preserve behavior, public APIs, persisted data, and tracker install behavior
unless a real defect is proven. Change one coherent area only.

Prefer deletion and simpler concrete code over new abstractions. Add an
abstraction only when repeated logic already exists and drift risk is clear.
For Java, keep APIs narrow, types explicit, assertions internal, and validation
at trust boundaries. For tracker JavaScript, keep the single `.mjs` stdlib-only
shape.

Prove safety with focused tests. Run the repository verification required by
`.system/RULES.md`.
If no useful low-risk refactor exists, leave the repository unchanged.
