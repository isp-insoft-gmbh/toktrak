import assert from "node:assert/strict";
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";
import { compare, compareRuns, confirmed } from "./perf/compare.mjs";

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

const hostKey = "ubuntu-latest|ubuntu24|Linux|amd64|test CPU|4";

function pair(base, candidate, sameFixture = true, candidateHostKey = hostKey) {
  return compare(new Map([["replay", base]]), new Map([["replay", candidate]]), {
    sameFixture,
    baseHostKey: hostKey,
    candidateHostKey,
  })[0];
}

test("given_differentHost_when_grossSlowdownCompared_then_notComparable", () => {
  const components = hostKey.split("|");
  for (let index = 0; index < components.length; index++) {
    const changed = [...components];
    changed[index] = "different";
    const row = pair(result(1), result(3), true, changed.join("|"));
    assert.deepEqual(row, { id: "replay", verdict: "not comparable" });
  }
});

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
  assert.equal(
    compare(new Map(), new Map([["new", result(1)]]), { baseHostKey: hostKey, candidateHostKey: hostKey })[0].verdict,
    "not comparable",
  );
});

function runs(context) {
  const directory = mkdtempSync(join(tmpdir(), "toktrak-perf-compare-"));
  context.after(() => rmSync(directory, { recursive: true, force: true }));
  const base = join(directory, "base");
  const candidate = join(directory, "candidate");
  for (const [path, score] of [
    [base, 1],
    [candidate, 3],
  ]) {
    mkdirSync(join(path, "replay"), { recursive: true });
    writeFileSync(join(path, "replay/result.json"), JSON.stringify([result(score)]));
    writeFileSync(join(path, "host.json"), JSON.stringify({ hostKey }));
  }
  return { base, candidate };
}

function writeHost(directory, metadata) {
  writeFileSync(join(directory, "host.json"), JSON.stringify(metadata));
}

test("given_runArtifacts_when_hostKeysDiffer_then_notComparable", (context) => {
  const { base, candidate } = runs(context);
  assert.equal(compareRuns(base, candidate, true)[0].verdict, "suspect");
  writeHost(candidate, { hostKey: hostKey.replace("test CPU", "other CPU") });
  assert.deepEqual(compareRuns(base, candidate, true), [{ id: "replay", verdict: "not comparable" }]);
});

test("given_suspectSlowdown_when_confirmationHostChanges_then_inconclusive", (context) => {
  const { base, candidate } = runs(context);
  const first = compareRuns(base, candidate, true);
  writeHost(base, { hostKey: "another host" });
  assert.equal(confirmed(first, compareRuns(base, candidate, true))[0].verdict, "inconclusive");
});

test("given_sameHost_when_environmentVersionsDiffer_then_stillComparable", (context) => {
  const { base, candidate } = runs(context);
  writeHost(base, { hostKey, javaVersion: "26", nodeVersion: "26", gitSha: "base" });
  writeHost(candidate, { hostKey, javaVersion: "27", nodeVersion: "27", gitSha: "candidate" });
  assert.equal(compareRuns(base, candidate, true)[0].verdict, "suspect");
});

test("given_runArtifacts_when_hostMetadataMissingOrInvalid_then_toolingFails", (context) => {
  const { base, candidate } = runs(context);
  for (const invalid of [undefined, null, "", " ", 4, "a".repeat(4097)]) {
    writeHost(candidate, { hostKey: invalid });
    assert.throws(() => compareRuns(base, candidate, true), /missing or invalid performance hostKey/);
  }
  writeFileSync(join(candidate, "host.json"), " ".repeat(16 * 1024 + 1));
  assert.throws(() => compareRuns(base, candidate, true), /oversized performance host metadata/);
  writeFileSync(join(candidate, "host.json"), "{broken");
  assert.throws(() => compareRuns(base, candidate, true), SyntaxError);
  rmSync(join(candidate, "host.json"));
  assert.throws(() => compareRuns(base, candidate, true), /missing or oversized performance host metadata/);
});
