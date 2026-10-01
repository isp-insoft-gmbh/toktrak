import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { mkdirSync, mkdtempSync, readFileSync, readdirSync, rmSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import test from "node:test";
import { changedPaths, relevantChecks } from "./ci-scope.mjs";

const workflowDirectory = join(process.cwd(), ".github", "workflows");
const workflow = (name) => readFileSync(join(workflowDirectory, `${name}.yml`), "utf8");

test("given_changedPaths_when_classifyingPr_then_runsOnlyRelevantChecks", () => {
  assert.deepEqual(relevantChecks(["README.md", "sources/pmd-deps.txt"]), {
    tracker: false,
    prod: false,
    perf: false,
    pit: false,
  });
  assert.deepEqual(relevantChecks(["sources/toktrak/App.java"]), {
    tracker: false,
    prod: true,
    perf: true,
    pit: true,
  });
  assert.deepEqual(relevantChecks(["sources/toktrak/assets/private/tracker.mjs"]), {
    tracker: true,
    prod: true,
    perf: false,
    pit: false,
  });
  assert.deepEqual(relevantChecks([".github/workflows/pit.yml"]), {
    tracker: false,
    prod: false,
    perf: false,
    pit: true,
  });
  assert.deepEqual(relevantChecks(["mise.lock"]), {
    tracker: true,
    prod: true,
    perf: true,
    pit: true,
  });
  assert.deepEqual(relevantChecks(["unknown-binary.dat"]), relevantChecks([".github/workflows/ci.yml"]));
  assert.deepEqual(relevantChecks([]), relevantChecks(["mise.lock"]));
});

test("given_oldPrAndRenamedSource_when_diffingMerge_then_ignoresBaseDriftAndPreservesDeletedPaths", () => {
  const directory = mkdtempSync(join(process.cwd(), "output", "ci-scope-"));
  const git = (...args) => execFileSync("git", args, { cwd: directory, encoding: "utf8", timeout: 30_000 });
  const commit = (message) =>
    git("-c", "user.name=Fixture", "-c", "user.email=fixture@example.invalid", "commit", "-qm", message);
  try {
    git("init", "-q", "-b", "trunk");
    git("config", "branch.autoSetupMerge", "false");
    mkdirSync(join(directory, "sources"));
    writeFileSync(join(directory, "sources", "Old.java"), "class Old {}\n");
    git("add", ".");
    commit("base");
    git("checkout", "-qb", "docs");
    writeFileSync(join(directory, "README.md"), "PR documentation\n");
    git("add", ".");
    commit("documentation");
    git("checkout", "-q", "trunk");
    writeFileSync(join(directory, "sources", "New.java"), "class New {}\n");
    git("add", ".");
    commit("unrelated trunk source");
    git(
      "-c",
      "user.name=Fixture",
      "-c",
      "user.email=fixture@example.invalid",
      "merge",
      "-q",
      "--no-ff",
      "docs",
      "-m",
      "merge docs",
    );
    assert.deepEqual(changedPaths(directory), ["README.md"]);
    git("checkout", "-qb", "move");
    mkdirSync(join(directory, "tools"));
    git("mv", "sources/Old.java", "tools/Old.java");
    commit("move source into tools");
    git("checkout", "-q", "trunk");
    git(
      "-c",
      "user.name=Fixture",
      "-c",
      "user.email=fixture@example.invalid",
      "merge",
      "-q",
      "--no-ff",
      "move",
      "-m",
      "merge move",
    );
    const moved = changedPaths(directory);
    assert.deepEqual(moved, ["sources/Old.java", "tools/Old.java"]);
    assert.equal(relevantChecks(moved).pit, true);
  } finally {
    rmSync(directory, { recursive: true, force: true });
  }
});

test("given_pullRequest_when_ciRuns_then_requiredCheckEnforcesSelectedOrSkippedResults", () => {
  const ci = workflow("ci");
  assert.match(ci, /pull_request:\n    branches: \[trunk\]/u);
  assert.match(ci, /needs: \[scope, core, tracker, prod, perf, pit\]/u);
  assert.ok(ci.includes("run: node tools/ci-scope.mjs"));
  assert.ok(ci.includes('check scope "$SCOPE_RESULT" "$scope_expected"'));
  assert.ok(ci.includes('check core "$CORE_RESULT" success'));
  for (const job of ["tracker", "prod", "perf", "pit"]) {
    assert.ok(ci.includes(`needs.scope.outputs.${job} == 'true'`));
    assert.ok(ci.includes(`check ${job} "$${job.toUpperCase()}_RESULT" "$${job}_expected"`));
    assert.match(workflow(job), /^  workflow_call:$/mu);
  }
  assert.ok(ci.includes('echo "## PR validation plan"'));
  assert.ok(ci.includes('echo "## CI validation"'));
});

test("given_pullRequest_when_checkingReports_then_uploadsOnlyFailedMutationXml", () => {
  for (const name of ["ci", "perf", "pit"]) {
    assert.match(workflow(name), /if: always\(\) && github.event_name != 'pull_request' && hashFiles/u);
  }
  const pit = workflow("pit");
  assert.match(
    pit,
    /name: Upload failed mutation XML\n        if: failure\(\) && github.event_name == 'pull_request'/u,
  );
  assert.match(pit, /name: mutation-failure-xml\n          path: output\/\.mutations\.stage-\*\/mutations\.xml/u);
  assert.match(pit, /include-hidden-files: true\n          if-no-files-found: warn/u);
});

test("given_pullRequest_when_pitRuns_then_recomputesMutationsWithoutSharedHistory", () => {
  const pit = workflow("pit");
  assert.match(pit, /- if: github\.event_name == 'pull_request'\n        run: mise run pit\n/u);
  assert.match(pit, /- if: github\.event_name != 'pull_request'\n        run: mise run pit --history\n/u);
  assert.equal(
    (pit.match(/- if: github\.event_name != 'pull_request'\n        uses: actions\/cache\//gu) ?? []).length,
    2,
  );
});

test("given_dprintPluginUpdate_when_publishingPr_then_doesNotExecuteCandidatePlugins", () => {
  const dprint = workflow("dprint");
  assert.match(dprint, /persist-credentials: false/u);
  assert.match(dprint, /run: node tools\/update-dprint\.mjs/u);
  assert.doesNotMatch(dprint, /jdx\/mise-action@|mise run fmt|mise run check/u);
  assert.ok(
    dprint.indexOf("run: node tools/update-dprint.mjs") < dprint.indexOf("name: Mint repository GitHub App token"),
  );
});

test("given_renovateUpdate_when_configured_then_disablesAutomaticMerge", () => {
  const renovate = JSON.parse(readFileSync(join(process.cwd(), "renovate.json"), "utf8"));
  assert.equal(renovate.automerge, false);
  assert.notEqual(renovate.platformAutomerge, true);
  for (const rule of renovate.packageRules) assert.notEqual(rule.automerge, true);
});

test("given_linuxWorkflows_when_choosingRunners_then_usesGithubHostedUbuntu", () => {
  for (const file of readdirSync(workflowDirectory).filter((entry) => entry.endsWith(".yml"))) {
    const source = readFileSync(join(workflowDirectory, file), "utf8");
    assert.doesNotMatch(source, /blacksmith/iu, file);
    if (file !== "tracker.yml") assert.match(source, /runs-on: ubuntu-24\.04/u, file);
  }
  assert.match(workflow("tracker"), /runner: ubuntu-24\.04/u);
  assert.match(workflow("perf"), /PERF_RUNNER_LABEL: ubuntu-24\.04/u);
});
