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
  assert.equal(relevantChecks(["tools/release-notes.mjs"]).prod, true);
  assert.equal(relevantChecks([".github/workflows/release.yml"]).prod, true);
  assert.deepEqual(relevantChecks(["unknown-binary.dat"]), relevantChecks([".github/workflows/ci.yml"]));
  assert.deepEqual(relevantChecks([]), relevantChecks(["mise.lock"]));
});

test("given_oldPrAndRenamedSource_when_diffingMerge_then_ignoresBaseDriftAndPreservesDeletedPaths", () => {
  const directory = mkdtempSync(join(process.cwd(), "output", "ci-scope-"));
  const git = (args, input) => execFileSync("git", args, { cwd: directory, input, timeout: 30_000 });
  const blob = (mark, content) => `blob\nmark :${mark}\ndata ${Buffer.byteLength(content)}\n${content}\n`;
  const commit = (branch, mark, parents, changes) =>
    `commit refs/heads/${branch}\nmark :${mark}\ncommitter Fixture <fixture@example.invalid> 0 +0000\n` +
    `data 1\nx\n${parents}${changes.join("\n")}\n`;
  try {
    git(["init", "-q", "-b", "trunk"]);
    const config = join(directory, ".git", "config");
    writeFileSync(config, `${readFileSync(config, "utf8")}\n[diff]\n\trenames = true\n`);
    const fixture =
      blob(1, "class Old {}\n") +
      blob(2, "class New {}\n") +
      blob(3, "PR documentation\n") +
      commit("trunk", 4, "", ["M 100644 :1 sources/Old.java"]) +
      commit("trunk", 5, "from :4\n", ["M 100644 :2 sources/New.java"]) +
      commit("pr", 6, "from :4\n", ["D sources/Old.java", "M 100644 :1 tools/Old.java", "M 100644 :3 README.md"]) +
      commit("trunk", 7, "from :5\nmerge :6\n", [
        "D sources/Old.java",
        "M 100644 :1 tools/Old.java",
        "M 100644 :3 README.md",
      ]);
    git(["fast-import", "--quiet"], fixture);
    const changed = changedPaths(directory);
    assert.deepEqual(changed, ["README.md", "sources/Old.java", "tools/Old.java"]);
    assert.equal(relevantChecks(changed).pit, true);
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
});

test("given_pullRequest_when_measuringPerformance_then_comparesOnSameRunnerWithGenerousBudget", () => {
  const perf = workflow("perf");
  assert.match(perf, /timeout-minutes: 60/u);
  assert.match(perf, /fetch-depth: 2/u);
  assert.match(perf, /if: github\.event_name == 'pull_request'\n        run: node tools\/perf\/compare\.mjs/u);
  assert.match(perf, /if: github\.event_name != 'pull_request'\n        run: mise run perf/u);
});

test("given_golemDispatch_when_running_then_javaOwnsLifecycleAndAuthRotation", () => {
  const golem = workflow("golem");
  assert.match(golem, /java -ea tools\/golems\/Golems\.java select --event schedule/u);
  assert.match(golem, /run: mise run golem "\$\{\{ matrix\.golem \}\}"/u);
  assert.doesNotMatch(golem, /run: java -ea tools\/golems\/Golems\.java run --golem/u);
  const mise = readFileSync(join(process.cwd(), "mise.toml"), "utf8");
  assert.match(
    mise,
    /\[task_templates\.golem-tools\]\ntools = \{[^\n]*aqua:earendil-works\/pi[^\n]*aqua:openai\/codex[^\n]*\}/u,
  );
  assert.match(mise, /\[tasks\.golem\]\nextends = "golem-tools"/u);
  assert.match(golem, /java -ea tools\/golems\/Auth\.java decrypt/u);
  assert.match(golem, /java -ea tools\/golems\/Auth\.java encrypt/u);
  assert.match(golem, /java -ea tools\/golems\/Auth\.java seed/u);
  assert.match(golem, /matrix:\n        include: \$\{\{ fromJSON\(needs\.select\.outputs\.tasks\) \}\}/u);
  assert.doesNotMatch(golem, /node tools\/golem\.mjs|matrix\.task|matrix\.thinking/u);
  assert.match(golem, /persist-credentials: false/u);
  assert.match(golem, /CUTOVER: \$\{\{ vars\.GOLEM_JAVA_CUTOVER \}\}/u);
  assert.match(golem, /"\$CUTOVER" != "enabled" && "\$TASK" != "canary"/u);
});

test("given_releaseIntentTag_when_publishing_then_ciOwnsContainerAndSerializesPromotion", () => {
  const release = workflow("release");
  const prod = workflow("prod");
  assert.match(release, /tags: \["v\[0-9\]\*"\]/u);
  assert.match(release, /group: release-publisher\n  cancel-in-progress: false\n  queue: max/u);
  assert.match(release, /persist-credentials: false/u);
  assert.match(release, /run: mise run ci/u);
  assert.match(release, /workflow_dispatch:\n/u);
  assert.match(release, /registry-preflight:\n    if: github\.event_name == 'workflow_dispatch'/u);
  assert.match(release, /release:\n    if: github\.event_name == 'push'/u);
  assert.match(release, /secrets\.ISP_INSOFT_REGISTRY_CI_USER/u);
  assert.match(release, /secrets\.ISP_INSOFT_REGISTRY_CI_PW/u);
  assert.match(release, /node tools\/release-notes\.mjs release/u);
  assert.match(release, /node tools\/container-ci\.mjs release/u);
  assert.match(release, /TOKTRAK_IMAGE_REPOSITORY: \$\{\{ vars\.TOKTRAK_IMAGE_REPOSITORY \}\}/u);
  assert.match(prod, /node tools\/container-ci\.mjs verify dev/u);
  assert.doesNotMatch(prod, /TOKTRAK_REGISTRY_PASSWORD/u);
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
  const ubuntuRunner = /^ubuntu-\d{2}\.\d{2}$/u;
  for (const file of readdirSync(workflowDirectory).filter((entry) => entry.endsWith(".yml"))) {
    const source = readFileSync(join(workflowDirectory, file), "utf8");
    const runners = [...source.matchAll(/^\s+runs-on: ([^\n#]+)$/gmu)].map((match) => match[1].trim());
    assert.ok(runners.length > 0, file);
    if (file === "golem.yml") {
      assert.deepEqual(runners, ["ubuntu-26.04", "${{ matrix.os }}", "ubuntu-26.04"]);
      assert.match(source, /matrix:\n        include: \$\{\{ fromJSON\(needs\.select\.outputs\.tasks\) \}\}/u);
    } else if (file === "tracker.yml") {
      assert.deepEqual(runners, ["${{ matrix.runner }}"]);
      const matrixRunners = [...source.matchAll(/^\s+runner: (\S+)$/gmu)].map((match) => match[1]);
      assert.ok(
        matrixRunners.some((runner) => ubuntuRunner.test(runner)),
        file,
      );
      assert.ok(
        matrixRunners.some((runner) => /^macos-\d+$/u.test(runner)),
        file,
      );
      assert.ok(
        matrixRunners.some((runner) => /^windows-\d+$/u.test(runner)),
        file,
      );
    } else {
      for (const runner of runners) assert.match(runner, ubuntuRunner, file);
    }
  }
  const perf = workflow("perf");
  const runner = perf.match(/^\s+runs-on: (ubuntu-\d{2}\.\d{2})$/mu)?.[1];
  const label = perf.match(/^\s+PERF_RUNNER_LABEL: (\S+)$/mu)?.[1];
  assert.ok(runner && label, "performance runner and baseline label are required");
  assert.equal(label, runner);
});
