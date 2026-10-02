import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { randomBytes } from "node:crypto";
import { existsSync, mkdtempSync, mkdirSync, readFileSync, rmSync, statSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { tmpdir } from "node:os";
import test from "node:test";
import {
  authCheckTargets,
  changedPaths,
  compactPullRequestContext,
  evidenceBody,
  harnessArguments,
  labelColor,
  renderedPullRequestBody,
  runDetailsBody,
  parseTask,
  protectedChanges,
  runLifecycle,
  selectedTasks,
  streamed,
  validateDefinitions,
  validClaudeSubscription,
} from "./golem.mjs";

const valid = `---
harness: pi
model: openai-codex/gpt-5.5
thinking: max
weekday: monday
---

Find one defect.
`;

const failure = (source, pattern) => assert.throws(() => parseTask(source, "bugs.md"), pattern);

test("accepts one complete strict task definition", () => {
  assert.deepEqual(parseTask(valid, "bugs.md"), {
    harness: "pi",
    model: "openai-codex/gpt-5.5",
    thinking: "max",
    weekday: "monday",
    body: "Find one defect.",
  });
});

test("rejects missing, duplicate, unknown, and non-scalar frontmatter", () => {
  failure(valid.replace("---\n", "", 1), /opening delimiter is missing/);
  failure(valid.replace("weekday: monday\n---", "weekday: monday"), /closing delimiter is missing/);
  failure(valid.replace("thinking: max", "thinking: max\nthinking: high"), /thinking: duplicate field/);
  failure(valid.replace("thinking: max", "effort: max"), /effort: unknown field/);
  failure(valid.replace("thinking: max", "thinking: [max]"), /not a plain scalar/);
  failure(valid.replace("thinking: max", "thinking: &level max"), /not a plain scalar/);
  failure(valid.replace("thinking: max", "thinking: max # comment"), /not a plain scalar/);
  failure(valid.replace("thinking: max", "# thinking\nthinking: max"), /comments and blank lines are forbidden/);
  failure(valid.replace("model: openai-codex/gpt-5.5\n", ""), /model: required field is missing/);
});

test("rejects invalid values, empty prompts, and provider billing drift", () => {
  failure(valid.replace("harness: pi", "harness: other"), /harness: unsupported value/);
  failure(valid.replace("weekday: monday", "weekday: someday"), /weekday: unsupported value/);
  failure(valid.replace("model: openai-codex/gpt-5.5", `model: ${"m".repeat(45)}`), /model: exceeds 44/);
  failure(valid.replace("thinking: max", `thinking: ${"x".repeat(33)}`), /thinking: exceeds 32/);
  failure(valid.replace("model: openai-codex/gpt-5.5", "model: anthropic/claude"), /Pi must use ChatGPT/);
  failure(
    valid.replace("harness: pi\nmodel: openai-codex/gpt-5.5", "harness: codex\nmodel: openai/gpt"),
    /must be native to codex/,
  );
  failure(valid.replace("Find one defect.", "   "), /prompt: Markdown body is empty/);
});

test("validates bounded canonical task files and reserved common instructions", () => {
  const directory = mkdtempSync(join(tmpdir(), "toktrak-golems-"));
  try {
    writeFileSync(join(directory, "_golem.md"), "Common instructions.\n");
    writeFileSync(join(directory, "bugs.md"), valid);
    assert.deepEqual([...validateDefinitions(directory).keys()], ["bugs"]);
    writeFileSync(join(directory, "Bad.md"), valid);
    assert.throws(() => validateDefinitions(directory), /task ID: must be lowercase kebab-case/);
    rmSync(join(directory, "Bad.md"));
    writeFileSync(join(directory, "bad.txt"), "x");
    assert.throws(() => validateDefinitions(directory), /is not a task Markdown file/);
    rmSync(join(directory, "bad.txt"));
    writeFileSync(join(directory, "bugs.md"), valid.replaceAll("\n", "\r\n"));
    assert.throws(() => validateDefinitions(directory), /contains non-LF line endings/);
    writeFileSync(join(directory, "bugs.md"), valid);
    writeFileSync(join(directory, "_golem.md"), "x".repeat(64 * 1024 + 1));
    assert.throws(() => validateDefinitions(directory), /exceeds 65536 bytes/);
    writeFileSync(join(directory, "_golem.md"), "Common.\n");
    writeFileSync(join(directory, "bugs.md"), Buffer.from([0xff]));
    assert.throws(() => validateDefinitions(directory), /is not strict UTF-8/);
    writeFileSync(join(directory, "bugs.md"), `\uFEFF${valid}`);
    assert.throws(() => validateDefinitions(directory), /starts with a UTF-8 BOM/);
  } finally {
    rmSync(directory, { recursive: true, force: true });
  }
});

test("rejects missing definition directory and common instructions", () => {
  const directory = mkdtempSync(join(tmpdir(), "toktrak-golems-"));
  try {
    assert.throws(() => validateDefinitions(join(directory, "missing")), /directory: is missing/);
    writeFileSync(join(directory, "bugs.md"), valid);
    assert.throws(() => validateDefinitions(directory), /_golem\.md: file: is missing/);
    mkdirSync(join(directory, "nested.md"));
    writeFileSync(join(directory, "_golem.md"), "Common.\n");
    assert.throws(() => validateDefinitions(directory), /must be a regular non-symbolic file/);
  } finally {
    rmSync(directory, { recursive: true, force: true });
  }
});

test("selects one explicit task or every task due on a UTC weekday", () => {
  const tasks = new Map([
    ["bugs", { id: "bugs", weekday: "monday" }],
    ["security", { id: "security", weekday: "tuesday" }],
    ["docs", { id: "docs", weekday: "monday" }],
  ]);
  assert.deepEqual(selectedTasks(tasks, "security", "monday"), [tasks.get("security")]);
  assert.deepEqual(selectedTasks(tasks, undefined, "monday"), [tasks.get("bugs"), tasks.get("docs")]);
  assert.deepEqual(selectedTasks(tasks, undefined, "sunday"), []);
  assert.throws(() => selectedTasks(tasks, "missing", "monday"), /unknown golem task/);
  assert.throws(() => selectedTasks(tasks, undefined, "someday"), /invalid dispatch weekday/);
});

test("selects one authentication target per harness", () => {
  const tasks = new Map([
    ["bugs", { id: "bugs", harness: "pi" }],
    ["docs", { id: "docs", harness: "pi" }],
    ["qa", { id: "qa", harness: "claude" }],
    ["security", { id: "security", harness: "codex" }],
  ]);
  assert.deepEqual([...authCheckTargets(tasks).keys()], ["bugs", "qa", "security"]);
});

test("given_existingPr_when_repairing_then_runsPrepareExecutePublishInOrder", async () => {
  const steps = [];
  const result = await runLifecycle(
    "bugs",
    async (id) => {
      steps.push("prepare");
      return { id };
    },
    async (run) => {
      steps.push("execute");
      return run;
    },
    async (run) => {
      steps.push("publish");
      return run.id;
    },
  );
  assert.equal(result, "bugs");
  assert.deepEqual(steps, ["prepare", "execute", "publish"]);
});

test("given_priorFailedChecks_when_resumingPr_then_providesBoundedLinksWithoutFullReviewText", () => {
  const head = "a".repeat(40);
  const url = "https://github.com/isp-insoft-gmbh/toktrak/pull/12";
  const detailsUrl = "https://github.com/isp-insoft-gmbh/toktrak/actions/runs/123/job/456";
  const context = compactPullRequestContext(12, {
    headRefOid: head,
    url,
    comments: [{ body: "untrusted large review text" }],
    statusCheckRollup: [
      { conclusion: "FAILURE", detailsUrl },
      { conclusion: "SUCCESS", detailsUrl: "https://github.com/isp-insoft-gmbh/toktrak/actions/runs/789" },
    ],
  });
  assert.match(context, /Previous head: a{40}/u);
  assert.ok(context.includes(detailsUrl));
  assert.doesNotMatch(context, /untrusted large review text|\/runs\/789/u);
  assert.ok(Buffer.byteLength(context) < 1_024);
  assert.throws(
    () => compactPullRequestContext(12, { url, headRefOid: "invalid", statusCheckRollup: [] }),
    /invalid context/u,
  );
});

test("terminates the complete process tree on timeout", async () => {
  const directory = mkdtempSync(join(tmpdir(), "toktrak-golem-process-"));
  const pidFile = join(directory, "descendant.pid");
  let descendant;
  try {
    const script = `
      const { spawn } = require("node:child_process");
      const { writeFileSync } = require("node:fs");
      const child = spawn(process.execPath, ["-e", "setInterval(() => {}, 1000)"], { stdio: "ignore" });
      writeFileSync(${JSON.stringify(pidFile)}, String(child.pid));
      setInterval(() => {}, 1000);
    `;
    await assert.rejects(
      streamed(process.execPath, ["-e", script], {
        timeout: 500,
        operation: "controlled process tree",
      }),
      /controlled process tree timed out/,
    );
    assert.equal(existsSync(pidFile), true);
    descendant = Number(readFileSync(pidFile, "utf8"));
    await new Promise((accept) => setTimeout(accept, 100));
    assert.throws(() => process.kill(descendant, 0), /ESRCH/);
  } finally {
    if (descendant) {
      try {
        process.kill(descendant, "SIGKILL");
      } catch (error) {
        if (error.code !== "ESRCH") throw error;
      }
    }
    rmSync(directory, { recursive: true, force: true });
  }
});

test("given_claudeAuthentication_when_validated_then_onlySubscriptionCredentialsPass", () => {
  const local = { loggedIn: true, authMethod: "claude.ai" };
  assert.equal(validClaudeSubscription({ ...local, subscriptionType: "team" }, ""), true);
  assert.equal(validClaudeSubscription({ ...local, subscriptionType: "max" }, ""), true);
  assert.equal(validClaudeSubscription({ ...local, subscriptionType: "pro" }, ""), false);
  assert.equal(validClaudeSubscription({ ...local, subscriptionType: "team", loggedIn: false }, ""), false);
  assert.equal(validClaudeSubscription({ loggedIn: true, authMethod: "api_key", subscriptionType: "team" }, ""), false);
  assert.equal(validClaudeSubscription({ ...local, subscriptionType: "team" }, "setup-token"), false);
  assert.equal(
    validClaudeSubscription({ loggedIn: true, authMethod: "oauth_token", apiProvider: "firstParty" }, "setup-token"),
    true,
  );
  assert.equal(
    validClaudeSubscription({ loggedIn: true, authMethod: "oauth_token", apiProvider: "thirdParty" }, "setup-token"),
    false,
  );
});

test("encrypts, binds, and restores rotating subscription authentication", () => {
  const home = mkdtempSync(join(tmpdir(), "toktrak-golem-auth-"));
  const script = join(process.cwd(), "tools", "golem.mjs");
  const cache = join(home, "pi.cache");
  const secondCache = join(home, "pi-second.cache");
  const secret = Buffer.from('{"openai-codex":{"type":"oauth","refresh":"secret"}}');
  const key = randomBytes(32).toString("base64");
  const run = (arguments_, environment) =>
    spawnSync(process.execPath, [script, "auth", ...arguments_], {
      encoding: "utf8",
      env: { ...process.env, HOME: home, USERPROFILE: home, ...environment },
      timeout: 30_000,
    });
  try {
    const seeded = run(["seed", "pi"], {
      GOLEM_AUTH_SEED: secret.toString("base64"),
    });
    assert.equal(seeded.status, 0, seeded.stderr);
    const authentication = join(home, ".pi", "agent", "auth.json");
    assert.deepEqual(readFileSync(authentication), secret);
    if (process.platform !== "win32") assert.equal(statSync(authentication).mode & 0o777, 0o600);

    const encrypted = run(["encrypt", "pi", cache], {
      GOLEM_AUTH_CACHE_KEY: key,
    });
    const encryptedAgain = run(["encrypt", "pi", secondCache], {
      GOLEM_AUTH_CACHE_KEY: key,
    });
    assert.equal(encrypted.status, 0, encrypted.stderr);
    assert.equal(encryptedAgain.status, 0, encryptedAgain.stderr);
    assert.equal(readFileSync(cache).includes(secret), false);
    assert.notDeepEqual(readFileSync(cache), readFileSync(secondCache));
    const firstCache = readFileSync(cache);
    const overwritten = run(["encrypt", "pi", cache], {
      GOLEM_AUTH_CACHE_KEY: key,
    });
    assert.equal(overwritten.status, 0, overwritten.stderr);
    assert.notDeepEqual(readFileSync(cache), firstCache);

    rmSync(authentication);
    const restored = run(["decrypt", "pi", cache], {
      GOLEM_AUTH_CACHE_KEY: key,
    });
    assert.equal(restored.status, 0, restored.stderr);
    assert.deepEqual(readFileSync(authentication), secret);

    const wrongProvider = run(["decrypt", "codex", cache], {
      GOLEM_AUTH_CACHE_KEY: key,
    });
    assert.notEqual(wrongProvider.status, 0);
    assert.doesNotMatch(wrongProvider.stderr, /secret/);
    const wrongKey = run(["decrypt", "pi", cache], {
      GOLEM_AUTH_CACHE_KEY: randomBytes(32).toString("base64"),
    });
    assert.notEqual(wrongKey.status, 0);
    assert.doesNotMatch(wrongKey.stderr, /secret/);
  } finally {
    rmSync(home, { recursive: true, force: true });
  }
});

test("given_rotatingAuthentication_when_workflowRuns_then_javaOwnsSeedEncryptDecrypt", () => {
  const workflow = readFileSync(join(process.cwd(), ".github", "workflows", "golem.yml"), "utf8");
  const mise = readFileSync(join(process.cwd(), "mise.toml"), "utf8");
  assert.doesNotMatch(workflow, /GolemAuth|npm install --global/);
  assert.match(workflow, /java -ea tools\/golems\/Auth\.java decrypt/);
  assert.match(workflow, /java -ea tools\/golems\/Auth\.java encrypt/);
  assert.match(workflow, /java -ea tools\/golems\/Auth\.java seed/);
  assert.doesNotMatch(workflow, /node tools\/golem\.mjs/);
  assert.match(workflow, /mise run golem-auth-check (?:bugs|security)/);
  assert.match(mise, /\[task_templates\.golem-tools\]/);
  assert.match(mise, /\[tasks\.golem-auth-check\]\nextends = "golem-tools"/);
  assert.match(mise, /\[tasks\.golem\]\nextends = "golem-tools"/);
  assert.match(mise, /\[tasks\.golem-auth-check\][^\[]*run = "java -ea tools\/Build\.java golem-auth-check"/);
  assert.match(mise, /\[tasks\.golem\][^\[]*run = "java -ea tools\/Build\.java golem"/);
  assert.match(mise, /\[tasks\.dev\][^\[]*env = \{ TOKTRAK_DEV_AUTH = "true" \}/);
  assert.match(
    mise,
    /\[tasks\.dev\][^\[]*run = "java -ea tools\/Build\.java dev -- --corpus tests\/corpus\/dev\.jsonl/,
  );
  assert.doesNotMatch(mise, /mise watch|watchexec|dev-server/);
});

test("builds explicit ephemeral harness adapters", () => {
  assert.deepEqual(harnessArguments({ harness: "pi", model: "openai-codex/gpt-5.5", thinking: "max" }, true), [
    "--print",
    "--no-session",
    "--no-tools",
    "--model",
    "openai-codex/gpt-5.5",
    "--thinking",
    "max",
  ]);
  assert.deepEqual(harnessArguments({ harness: "claude", model: "fable", thinking: "high" }), [
    "--print",
    "--no-session-persistence",
    "--model",
    "fable",
    "--effort",
    "high",
    "--dangerously-skip-permissions",
  ]);
  assert.deepEqual(
    harnessArguments({
      harness: "codex",
      model: "gpt-5.6-sol",
      thinking: "max",
    }),
    [
      "exec",
      "--ephemeral",
      "--model",
      "gpt-5.6-sol",
      "--config",
      'model_reasoning_effort="max"',
      "--dangerously-bypass-approvals-and-sandbox",
      "-",
    ],
  );
});

test("colors golem labels while leaving other metadata grey", () => {
  const bugs = labelColor("golem:bugs");
  const qa = labelColor("golem:qa");

  assert.equal(labelColor("golem"), "6f42c1");
  assert.match(bugs, /^[0-9a-f]{6}$/);
  assert.match(qa, /^[0-9a-f]{6}$/);
  assert.notEqual(bugs, qa);
  assert.equal(labelColor("harness:pi"), "555555");
});

test("detects every protected control-plane prefix", () => {
  const paths = [
    ".system/RULES.md",
    ".github/golems/bugs.md",
    ".github/workflows/ci.yml",
    ".claude/skills/a/SKILL.md",
    ".agents/skills/a/SKILL.md",
    ".codex/config.toml",
    ".pi/settings.json",
    "sources/toktrak/App.java",
  ];
  assert.deepEqual(protectedChanges(paths), paths.slice(0, 7));
});

test("given_renamedWorkflow_when_guardingGolemBranch_then_detectsProtectedDeletion", () => {
  const directory = mkdtempSync(join(process.cwd(), "output", "golem-protected-"));
  const git = (...args) =>
    spawnSync("git", args, { cwd: directory, encoding: "utf8", timeout: 30_000, windowsHide: true });
  const checked = (...args) => {
    const result = git(...args);
    assert.equal(result.status, 0, result.stderr);
    return result.stdout.trim();
  };
  try {
    checked("init", "-q");
    mkdirSync(join(directory, ".github", "workflows"), { recursive: true });
    writeFileSync(join(directory, ".github", "workflows", "ci.yml"), "name: CI\n");
    checked("add", ".");
    checked("-c", "user.name=Fixture", "-c", "user.email=fixture@example.invalid", "commit", "-qm", "base");
    checked("update-ref", "refs/remotes/origin/trunk", checked("rev-parse", "HEAD"));
    checked("mv", ".github/workflows/ci.yml", "ci.yml");
    checked("-c", "user.name=Fixture", "-c", "user.email=fixture@example.invalid", "commit", "-qm", "rename");
    assert.deepEqual(changedPaths(directory, "trunk"), [".github/workflows/ci.yml", "ci.yml"]);
    assert.deepEqual(protectedChanges(changedPaths(directory, "trunk")), [".github/workflows/ci.yml"]);
  } finally {
    rmSync(directory, { recursive: true, force: true });
  }
});

test("replaces hidden pull-request run link without metadata", () => {
  const old = `Reason.\n\n<!-- golem-metadata:start -->\n## Golem metadata\n\n- Task: \`bugs\`\n- Run: old\n<!-- golem-metadata:end -->\n`;
  const once = runDetailsBody(old, "https://github.com/owner/repo/actions/runs/1");
  const twice = runDetailsBody(once, "https://github.com/owner/repo/actions/runs/2");
  assert.equal(twice.match(/golem-run:start/g)?.length, 1);
  assert.match(twice, /<details>/);
  assert.match(twice, /https:\/\/github\.com\/owner\/repo\/actions\/runs\/2/);
  assert.doesNotMatch(twice, /Golem metadata|Task:|golem-metadata|runs\/1/);
  assert.match(twice, /^Reason\./);
});

test("replaces evidence placeholders without footer metadata", () => {
  const body = "See ![diagram]([evidence:flow.svg]) and [demo]([evidence:demo.webm]).";
  const evidence = new Map([
    ["flow.svg", "https://gatebridge.link/1y/flow.svg"],
    ["demo.webm", "https://gatebridge.link/1y/demo.webm"],
  ]);

  assert.equal(
    evidenceBody(body, evidence),
    "See ![diagram](https://gatebridge.link/1y/flow.svg) and [demo](https://gatebridge.link/1y/demo.webm).",
  );
});

test("requires a rendered golem pull-request body", () => {
  const body = "See ![diagram]([evidence:flow.svg]).";
  const evidence = new Map([["flow.svg", "https://gatebridge.link/1y/flow.svg"]]);

  assert.equal(renderedPullRequestBody(body, evidence), "See ![diagram](https://gatebridge.link/1y/flow.svg).");
  assert.throws(() => renderedPullRequestBody(body, new Map()), /references missing evidence/);
});
