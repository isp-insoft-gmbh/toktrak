---
harness: pi
model: openai-codex/gpt-6.1-sol
thinking: high
weekday: saturday
---

# Performance maintenance

Own TokTrak performance evidence and the `mise run perf` entrypoint.

Scope includes runtime and developer-time performance: Java server code, tracker
`.mjs`, templates and assets when they affect runtime behavior,
`tools/Build.java`, `tools/perf/**`, build and test commands, production runtime
creation, container verification, and relevant workflows.

Perf suite code lives under `tools/perf/**`. Perf artifacts are generated under
`output/perf/**` and are never committed. `mise run perf` must remain the single
human entrypoint.

When no useful benchmark exists, create the smallest useful suite and one
benchmark after inspecting current product and build risks. Do not add more than
one benchmark in the bootstrap pull request.

On later runs, make exactly one coherent performance change: add, remove, or
repair one benchmark; improve fixture determinism; reduce benchmark noise;
optimize one measured hotspot; or improve performance reporting and comparison.

Choose tools per workload: hyperfine, JFR, JMH, Node, curl, or Java helpers. Use
the simplest tool that measures the intended workload. Prefer benchmarks that
are product-critical or build-critical, deterministic from repository fixtures,
actionable when slower, cheap enough for regular CI, and comparable on the same
host class.

New benchmarks start observational. A workflow may fail on broken perf tooling,
missing artifacts, invalid benchmark definitions, or benchmark command failure.
PR CI also fails on a confirmed ≥2× slowdown in a comparable workload measured
on the same runner against the merge commit's first parent. Changed or new
benchmarks remain observational until comparable; update source and fixture
identity checks in `tools/perf/compare.mjs` when adding benchmarks. Trunk runs
remain observational. Do not introduce narrower slowdown thresholds without
stable evidence.

Compare only the same `hostKey`: runner label, OS image, CPU model, and core
count. Record full environment separately: Java, Node, benchmark tool versions,
and git SHA. Treat environment differences as caveats unless they explain a
measured change.

Do not optimize without correctness tests, before/after benchmark evidence, and
a clear measured slow path. Do not trade correctness, security, durability,
accessibility, or debuggability for speed.
