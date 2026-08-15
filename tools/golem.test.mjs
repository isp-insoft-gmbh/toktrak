import assert from "node:assert/strict";
import { existsSync, mkdtempSync, mkdirSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { tmpdir } from "node:os";
import test from "node:test";
import {
  harnessArguments,
  metadataBody,
  parseTask,
  protectedChanges,
  runLifecycle,
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

const failure = (source, pattern) =>
  assert.throws(() => parseTask(source, "bugs.md"), pattern);

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
  failure(valid.replace("harness: pi\nmodel: openai-codex/gpt-5.5", "harness: codex\nmodel: openai/gpt"), /must be native to codex/);
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

test("builds explicit ephemeral harness adapters", () => {
  assert.deepEqual(
    harnessArguments({ harness: "pi", model: "openai-codex/gpt-5.5", thinking: "max" }, true),
    ["--print", "--no-session", "--no-tools", "--model", "openai-codex/gpt-5.5", "--thinking", "max"],
  );
  assert.deepEqual(
    harnessArguments({ harness: "claude", model: "fable", thinking: "high" }),
    ["--print", "--no-session-persistence", "--model", "fable", "--effort", "high", "--dangerously-skip-permissions"],
  );
  assert.deepEqual(
    harnessArguments({ harness: "codex", model: "gpt-5.6-sol", thinking: "max" }),
    ["exec", "--ephemeral", "--model", "gpt-5.6-sol", "--config", 'model_reasoning_effort="max"', "--dangerously-bypass-approvals-and-sandbox", "-"],
  );
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

test("replaces deterministic pull-request metadata without duplicating it", () => {
  const task = {
    id: "bugs",
    harness: "pi",
    model: "openai-codex/gpt-5.5",
    thinking: "max",
    weekday: "monday",
  };
  const once = metadataBody("Reason.\n", task, "abc123", "local", ["https://gatebridge.link/1y/a"]);
  const twice = metadataBody(once, task, "def456", "local");
  assert.equal(twice.match(/golem-metadata:start/g)?.length, 1);
  assert.match(twice, /Prompt revision: `def456`/);
  assert.doesNotMatch(twice, /abc123|gatebridge/);
  assert.match(twice, /^Reason\./);
});
