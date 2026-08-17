# TokTrak performance suite

`mise run perf` is the human entrypoint. It writes disposable artifacts under
`output/perf/**`.

`tools/perf/**` owns benchmark code and reporting. Perf golem maintains this
suite over time.

Build-owned performance dependencies are declared in `sources/perf-deps.txt` and
resolved by `tools/Build.java perf` before it launches `tools/perf/Perf.java`.

Use the simplest tool per workload:

- hyperfine for command and build timings
- JFR for Java hotspot evidence
- JMH for isolated stable Java CPU hot paths
- Node helpers for tracker `.mjs` workloads

New benchmarks start observational. Compare performance only for the same
`hostKey`. Use recorded environment fields as caveats, not automatic history
invalidators.
