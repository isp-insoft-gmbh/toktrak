---
id: TT-RESEARCH-OJEU5B_M
type: research
title: JaCoCo and Selfie integration
---

## Decisions

Use JaCoCo's runtime agent and nodeps CLI for on-the-fly coverage, with exact
compiled classes reused for reporting. Gate instruction and branch coverage; use
line coverage only for navigation. Current floors are 75% instruction and 60%
branch.

Use Selfie's JUnit runner only for stable, reviewable structured output such as
complete HTML, JSON, text, and protocols. Keep precise assertions for security
exclusions and invariants, and normalize nondeterministic values. CI must remain
read-only.

PIT invokes JUnit repeatedly with a different lifecycle. Exclude snapshot tests
and disable Selfie's listener inside PIT; precise tests still mutate the same
production paths.

## Sources

- <https://www.jacoco.org/jacoco/trunk/doc/agent.html>
- <https://www.jacoco.org/jacoco/trunk/doc/counters.html>
- <https://www.jacoco.org/jacoco/trunk/doc/classids.html>
- <https://selfie.dev/jvm/get-started>
- <https://selfie.dev/jvm/facets>

GitHub Actions artifacts can retain complete reports, but GitHub does not render
arbitrary artifact HTML. Pages or native coverage publication requires a
separate privacy and repository-admin decision.
