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

The observational `corpus-replay` JMH benchmark rebuilds the production
projection from `tests/corpus/dev.jsonl`. It measures across two fresh JVM
forks, reports its score and 99.9% error in the command output and `summary.md`,
retains the complete JMH data in `corpus-replay/result.json`, and fails if the
fixture identity or event count changes unexpectedly. In GitHub Actions, the
same report is also shown in the job summary so the measurement remains visible
without downloading the artifact.

PR CI runs the suite on the merge commit and its first parent, sequentially on
one runner. `tools/perf/compare.mjs` requires matching `hostKey` values before
comparing JMH measurements by benchmark ID, configuration, source and fixture
identity. Missing or invalid host metadata fails the tooling; differing host
keys make the workloads not comparable, including on confirmation runs. A
slowdown of at least 2× whose 99.9% error ranges do not overlap is measured
again in reverse order; only a confirmed slowdown fails CI. Changed or missing
workloads are reported as not comparable. The benchmark registry and comparison
belong to `tools/perf/**`: adding a benchmark does not require editing the
workflow. Include its fixture and benchmark source in comparison identity checks
when adding a new workload.

PR comparisons write versioned `comparison.json` alongside raw results and
include both host keys in the JSON and summary. Trunk runs remain observational;
their raw JMH JSON and `host.json` can feed future history storage and
visualization. Trunk artifacts currently expire after three days. For cross-run
analysis compare only matching `hostKey` values and treat recorded environment
differences as caveats, not automatic invalidators.
