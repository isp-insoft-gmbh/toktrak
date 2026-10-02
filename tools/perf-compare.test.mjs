import assert from "node:assert/strict";
import test from "node:test";
import { compare, confirmed } from "./perf/compare.mjs";

function result(score, scoreError = 0.1, benchmark = "toktrak.perf.CorpusReplayBenchmark.replay") {
  return {
    jmhVersion: "1.37",
    benchmark,
    mode: "avgt",
    threads: 1,
    forks: 2,
    warmupIterations: 5,
    warmupTime: "1 s",
    warmupBatchSize: 1,
    measurementIterations: 5,
    measurementTime: "1 s",
    measurementBatchSize: 1,
    primaryMetric: { score, scoreError, scoreUnit: "ms/op" },
  };
}

function pair(base, candidate, compatible = true) {
  return compare(new Map([["replay", base]]), new Map([["replay", candidate]]), compatible)[0];
}

test("given_sameWorkload_when_grossSlowdownExceedsError_then_suspect", () => {
  assert.equal(pair(result(1), result(2.5)).verdict, "suspect");
});

test("given_noisyOrSmallSlowdown_when_compared_then_notSuspect", () => {
  assert.equal(pair(result(1), result(1.99)).verdict, "ok");
  assert.equal(pair(result(1, 0.7), result(2.5, 0.9)).verdict, "ok");
});

test("given_suspectSlowdown_when_repeated_then_onlyConfirmedFailureIsRegression", () => {
  const first = [pair(result(1), result(2.5))];
  assert.equal(confirmed(first, [pair(result(1), result(2.5))])[0].verdict, "regression");
  assert.equal(confirmed(first, [pair(result(1), result(1.5))])[0].verdict, "inconclusive");
});

test("given_throughputWorkload_when_halved_then_suspect", () => {
  const base = result(100);
  const candidate = result(40);
  base.primaryMetric.scoreUnit = "ops/s";
  candidate.primaryMetric.scoreUnit = "ops/s";
  assert.equal(pair(base, candidate).verdict, "suspect");
});

test("given_changedDefinitionOrFixture_when_compared_then_notComparable", () => {
  assert.equal(pair(result(1), result(3, 0.1, "other")).verdict, "not comparable");
  assert.equal(pair(result(1), result(3), false).verdict, "not comparable");
  assert.equal(compare(new Map(), new Map([["new", result(1)]]))[0].verdict, "not comparable");
});
