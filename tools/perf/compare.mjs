#!/usr/bin/env node

import { execFileSync, spawnSync } from "node:child_process";
import { createHash } from "node:crypto";
import {
  appendFileSync,
  cpSync,
  existsSync,
  mkdirSync,
  readFileSync,
  readdirSync,
  rmSync,
  statSync,
  writeFileSync,
} from "node:fs";
import { join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const root = resolve(import.meta.dirname, "../..");
const output = join(root, "output");
const identityFiles = ["tests/corpus/dev.jsonl", "tools/perf/toktrak/perf/CorpusReplayBenchmark.java"];

function git(...args) {
  return execFileSync("git", args, { cwd: root, encoding: "utf8", timeout: 30_000 }).trim();
}

function run(directory) {
  const runs = join(directory, "output/perf");
  const before = new Set(existsSync(runs) ? readdirSync(runs) : []);
  const env = { ...process.env };
  delete env.GITHUB_STEP_SUMMARY;
  const result = spawnSync("mise", ["run", "perf"], { cwd: directory, env, stdio: "inherit", timeout: 1_800_000 });
  if (result.error) throw result.error;
  if (result.status !== 0) throw new Error(`perf failed in ${directory}: ${result.status ?? result.signal}`);
  const added = readdirSync(runs).filter((entry) => !before.has(entry));
  if (added.length !== 1) throw new Error(`expected one new perf run in ${runs}, found ${added.length}`);
  return join(runs, added[0]);
}

function measurements(directory) {
  const entries = new Map();
  const names = readdirSync(directory);
  if (names.length > 130) throw new Error(`too many benchmark entries: ${directory}`);
  for (const name of names) {
    if (name === "host.json" || name === "summary.md") continue;
    const file = join(directory, name, "result.json");
    if (!existsSync(file) || statSync(file).size > 1024 * 1024) {
      throw new Error(`missing or oversized benchmark result: ${file}`);
    }
    const results = JSON.parse(readFileSync(file, "utf8"));
    if (!Array.isArray(results) || results.length !== 1) throw new Error(`expected one JMH result: ${file}`);
    const result = results[0];
    if (
      !Number.isFinite(result.primaryMetric?.score) ||
      result.primaryMetric.score <= 0 ||
      !Number.isFinite(result.primaryMetric?.scoreError) ||
      result.primaryMetric.scoreError < 0 ||
      !/^(?:ns|us|ms|s)\/op$|^ops\/(?:ns|us|ms|s)$/.test(result.primaryMetric.scoreUnit)
    ) {
      throw new Error(`invalid JMH measurement: ${file}`);
    }
    entries.set(name, result);
  }
  if (!entries.size) throw new Error(`no benchmark results: ${directory}`);
  return entries;
}

function identity(directory) {
  return identityFiles.map((file) => {
    const path = join(directory, file);
    return existsSync(path) ? createHash("sha256").update(readFileSync(path)).digest("hex") : null;
  });
}

const settings = [
  "jmhVersion",
  "benchmark",
  "mode",
  "threads",
  "forks",
  "warmupIterations",
  "warmupTime",
  "warmupBatchSize",
  "measurementIterations",
  "measurementTime",
  "measurementBatchSize",
];

function requireHostKey(value) {
  if (typeof value !== "string" || !value.trim() || value.length > 4096) {
    throw new Error("missing or invalid performance hostKey");
  }
  return value;
}

function hostKey(directory) {
  const file = join(directory, "host.json");
  if (!existsSync(file) || statSync(file).size > 16 * 1024) {
    throw new Error(`missing or oversized performance host metadata: ${file}`);
  }
  return requireHostKey(JSON.parse(readFileSync(file, "utf8")).hostKey);
}

export function compareRuns(baseDirectory, candidateDirectory, sameFixture) {
  return compare(measurements(baseDirectory), measurements(candidateDirectory), {
    sameFixture,
    baseHostKey: hostKey(baseDirectory),
    candidateHostKey: hostKey(candidateDirectory),
  });
}

export function compare(base, candidate, { sameFixture = true, baseHostKey, candidateHostKey }) {
  const sameHost = requireHostKey(baseHostKey) === requireHostKey(candidateHostKey);
  const rows = [];
  for (const id of new Set([...base.keys(), ...candidate.keys()])) {
    const left = base.get(id);
    const right = candidate.get(id);
    if (
      !left ||
      !right ||
      !sameFixture ||
      !sameHost ||
      settings.some((key) => left[key] !== right[key]) ||
      JSON.stringify(left.params ?? null) !== JSON.stringify(right.params ?? null) ||
      left.primaryMetric.scoreUnit !== right.primaryMetric.scoreUnit
    ) {
      rows.push({ id, verdict: "not comparable" });
      continue;
    }
    const latency = right.primaryMetric.scoreUnit.endsWith("/op");
    const ratio = latency
      ? right.primaryMetric.score / left.primaryMetric.score
      : left.primaryMetric.score / right.primaryMetric.score;
    const suspect =
      ratio >= 2 &&
      (latency
        ? right.primaryMetric.score - right.primaryMetric.scoreError >
          left.primaryMetric.score + left.primaryMetric.scoreError
        : left.primaryMetric.score - left.primaryMetric.scoreError >
          right.primaryMetric.score + right.primaryMetric.scoreError);
    rows.push({
      id,
      base: left.primaryMetric.score,
      candidate: right.primaryMetric.score,
      errorBase: left.primaryMetric.scoreError,
      errorCandidate: right.primaryMetric.scoreError,
      unit: right.primaryMetric.scoreUnit,
      ratio,
      verdict: suspect ? "suspect" : "ok",
    });
  }
  return rows.sort((a, b) => a.id.localeCompare(b.id));
}

export function confirmed(first, confirmation) {
  return first.map((row) => {
    const retry = confirmation?.find((item) => item.id === row.id);
    return {
      ...row,
      verdict: row.verdict === "suspect" ? (retry?.verdict === "suspect" ? "regression" : "inconclusive") : row.verdict,
      ...(retry ? { confirmation: retry } : {}),
    };
  });
}

function report(base, candidate, first, confirmation, baseSha, headSha) {
  const results = confirmed(first, confirmation);
  const baseHostKey = hostKey(base);
  const candidateHostKey = hostKey(candidate);
  const document = { schemaVersion: 1, baseSha, headSha, baseHostKey, candidateHostKey, measurements: results };
  const destination = join(candidate, "comparison.json");
  writeFileSync(destination, JSON.stringify(document, null, 2) + "\n");
  const lines = [
    "## Performance comparison",
    "",
    `Base: \`${baseSha}\` · Candidate: \`${headSha}\``,
    "",
    `Base host: \`${baseHostKey}\` · Candidate host: \`${candidateHostKey}\``,
    "",
    "| Benchmark | Base | Candidate | Unit | Ratio | Verdict |",
    "| --- | ---: | ---: | --- | ---: | --- |",
  ];
  for (const row of results) {
    lines.push(
      `| \`${row.id}\` | ${row.base?.toFixed(3) ?? "n/a"} | ${row.candidate?.toFixed(3) ?? "n/a"} | ${row.unit ?? "n/a"} | ${row.ratio?.toFixed(2) ?? "n/a"}× | ${row.verdict} |`,
    );
  }
  lines.push(
    "",
    "Only matching host keys and workloads are comparable. A suspected ≥2× slowdown is confirmed with a second baseline/candidate pair.",
    `Raw baseline: \`${base}\` · candidate: \`${candidate}\``,
    "",
  );
  const summary = lines.join("\n");
  console.log(summary);
  if (process.env.GITHUB_STEP_SUMMARY) appendFileSync(process.env.GITHUB_STEP_SUMMARY, summary);
  if (results.some((row) => row.verdict === "regression")) throw new Error("confirmed gross performance regression");
}

if (process.argv[1] && fileURLToPath(import.meta.url) === resolve(process.argv[1])) {
  if (process.argv.length !== 2) throw new Error("usage: node tools/perf/compare.mjs");
  if (git("status", "--porcelain")) throw new Error("performance comparison requires a clean checkout");
  const headSha = git("rev-parse", "HEAD");
  const baseSha = git("rev-parse", "HEAD^1");
  mkdirSync(output, { recursive: true });
  const baseline = join(output, `perf-base-${process.pid}`);
  if (existsSync(baseline)) throw new Error(`baseline directory exists: ${baseline}`);
  git("worktree", "add", "--detach", baseline, baseSha);
  try {
    const baseRun = run(baseline);
    const candidateRun = run(root);
    const comparable = JSON.stringify(identity(baseline)) === JSON.stringify(identity(root));
    const first = compareRuns(baseRun, candidateRun, comparable);
    let confirmation;
    if (first.some((row) => row.verdict === "suspect")) {
      // Reverse order to reduce systematic effects from warming or runner contention.
      const candidateRetry = run(root);
      const baseRetry = run(baseline);
      confirmation = compareRuns(baseRetry, candidateRetry, comparable);
      cpSync(candidateRetry, join(candidateRun, "confirmation-candidate"), { recursive: true });
      cpSync(baseRetry, join(candidateRun, "confirmation-baseline"), { recursive: true });
    }
    const retainedBase = join(candidateRun, "baseline");
    cpSync(baseRun, retainedBase, { recursive: true });
    report(retainedBase, candidateRun, first, confirmation, baseSha, headSha);
  } finally {
    git("worktree", "remove", "--force", baseline);
    rmSync(baseline, { recursive: true, force: true });
  }
}
