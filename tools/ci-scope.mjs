#!/usr/bin/env node

import { execFileSync } from "node:child_process";
import { appendFileSync } from "node:fs";
import { resolve } from "node:path";
import { fileURLToPath } from "node:url";

const CHECKS = ["tracker", "prod", "perf", "pit"];
const all = () => Object.fromEntries(CHECKS.map((name) => [name, true]));

export function relevantChecks(paths) {
  if (paths.length === 0 || paths.length > 1_000) return all();
  const selected = Object.fromEntries(CHECKS.map((name) => [name, false]));
  for (const path of paths) {
    if (
      path === "mise.toml" ||
      path === "mise.lock" ||
      path.startsWith(".mise/") ||
      path === "tools/Build.java" ||
      path === ".github/workflows/ci.yml" ||
      path === "tools/ci-scope.mjs"
    )
      return all();
    if (
      path === "Containerfile" ||
      path === ".containerignore" ||
      path === "CHANGELOG.md" ||
      path === ".github/workflows/prod.yml" ||
      path === ".github/workflows/release.yml" ||
      path === "tools/container-ci.mjs" ||
      path === "tools/release-notes.mjs" ||
      path === "tools/release-notes.test.mjs"
    ) {
      selected.prod = true;
    } else if (
      path === ".github/workflows/perf.yml" ||
      path.startsWith("tools/perf/") ||
      path === "sources/perf-deps.txt"
    ) {
      selected.perf = true;
    } else if (path === ".github/workflows/pit.yml" || path === "sources/pit-deps.txt") {
      selected.pit = true;
    } else if (path === ".github/workflows/tracker.yml" || path.startsWith("tests/tracker/")) {
      selected.tracker = true;
    } else if (path === "sources/toktrak/assets/private/tracker.mjs") {
      selected.tracker = true;
      selected.prod = true;
    } else if (
      path === "sources/main-deps.txt" ||
      path === "sources/build-deps.txt" ||
      path === "sources/test-deps.txt" ||
      path === "sources/refaster-deps.txt"
    ) {
      selected.prod = true;
      selected.perf = true;
      selected.pit = true;
    } else if (path.startsWith("sources/") && path.endsWith(".java")) {
      selected.prod = true;
      selected.perf = true;
      selected.pit = true;
    } else if (path.startsWith("sources/") && path.endsWith(".mustache")) {
      selected.prod = true;
    } else if (path.startsWith("sources/toktrak/assets/")) {
      selected.prod = true;
    } else if (path.startsWith("tests/") && path.endsWith(".java")) {
      selected.pit = true;
    } else if (path.startsWith("tests/corpus/")) {
      selected.prod = true;
      selected.perf = true;
      selected.pit = true;
    } else if (
      path.startsWith(".system/") ||
      path.startsWith(".claude/") ||
      path.startsWith(".github/golems/") ||
      path.startsWith("tools/") ||
      path.endsWith(".md") ||
      path === "renovate.json" ||
      path === "dprint.json" ||
      path === ".rumdl.toml" ||
      path === ".github/workflows/golem.yml" ||
      path === ".github/workflows/dprint.yml" ||
      path === "sources/pmd-deps.txt" ||
      path === "sources/pmd.xml" ||
      path === "sources/error-prone.cfg"
    ) {
      // Core CI checks these files; no additional platform-specific work is needed.
    } else {
      return all();
    }
  }
  return selected;
}

export function changedPaths(directory) {
  const changed = execFileSync("git", ["diff", "--name-only", "--no-renames", "-z", "HEAD^1", "HEAD"], {
    cwd: directory,
    maxBuffer: 1024 * 1024,
    timeout: 30_000,
  });
  return new TextDecoder("utf-8", { fatal: true }).decode(changed).split("\0").filter(Boolean);
}

if (process.argv[1] && fileURLToPath(import.meta.url) === resolve(process.argv[1])) {
  if (process.env.GITHUB_EVENT_NAME !== "pull_request" || !process.env.GITHUB_OUTPUT) {
    throw new Error("CI scope requires a pull request and GITHUB_OUTPUT");
  }
  const paths = changedPaths(process.cwd());
  const checks = relevantChecks(paths);
  appendFileSync(
    process.env.GITHUB_OUTPUT,
    `${CHECKS.map((name) => `${name}=${checks[name]}`).join("\n")}\nchanged=${paths.length}\n`,
  );
  console.log(
    `Changed files: ${paths.length}; selected: ${CHECKS.filter((name) => checks[name]).join(", ") || "core only"}`,
  );
}
