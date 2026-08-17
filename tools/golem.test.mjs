import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { randomBytes } from "node:crypto";
import { existsSync, mkdtempSync, mkdirSync, readFileSync, rmSync, statSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { tmpdir } from "node:os";
import test from "node:test";
import {
  authCheckTargets,
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

test("preserves preflight, harness, and publication command order", async () => {
  const commands = [];
  const result = await runLifecycle(
    "bugs",
    async (id) => {
      commands.push("git fetch", "gh pr list", "git worktree add");
      return { id };
    },
    async (run) => {
      commands.push("pi --print");
      return run;
    },
    async (run) => {
      commands.push("git push", "gh pr create", "gh pr checks");
      return run.id;
    },
  );
  assert.equal(result, "bugs");
  assert.deepEqual(commands, [
    "git fetch",
    "gh pr list",
    "git worktree add",
    "pi --print",
    "git push",
    "gh pr create",
    "gh pr checks",
  ]);
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

test("keeps workflow authentication on the tested golem entrypoint", () => {
  const workflow = readFileSync(join(process.cwd(), ".github", "workflows", "golem.yml"), "utf8");
  assert.doesNotMatch(workflow, /GolemAuth/);
  assert.match(workflow, /node tools\/golem\.mjs auth decrypt/);
  assert.match(workflow, /node tools\/golem\.mjs auth encrypt/);
  assert.match(workflow, /node tools\/golem\.mjs auth seed/);
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
    ".claude/skills/a/SKILL.md",
    ".agents/skills/a/SKILL.md",
    ".codex/config.toml",
    ".pi/settings.json",
    "sources/toktrak/App.java",
  ];
  assert.deepEqual(protectedChanges(paths), paths.slice(0, 6));
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
