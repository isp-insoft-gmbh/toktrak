import assert from "node:assert/strict";
import { execFile } from "node:child_process";
import { randomUUID } from "node:crypto";
import { mkdtemp, readFile, readdir, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import test from "node:test";
import { pathToFileURL } from "node:url";
import { promisify } from "node:util";
import { command, lines, renderedTracker, waitFor } from "./scheduler-helpers.mjs";

const exec = promisify(execFile);

async function taskSettings(taskName) {
  const script =
    "$t=Get-ScheduledTask -TaskName $env:TASK_NAME;" +
    "[pscustomobject]@{" +
    "StartWhenAvailable=$t.Settings.StartWhenAvailable;" +
    "DisallowStartIfOnBatteries=$t.Settings.DisallowStartIfOnBatteries;" +
    "StopIfGoingOnBatteries=$t.Settings.StopIfGoingOnBatteries;" +
    "ExecutionTimeLimit=$t.Settings.ExecutionTimeLimit.ToString();" +
    "StartBoundary=$t.Triggers.StartBoundary;" +
    "State=$t.State.ToString()}|ConvertTo-Json -Compress";
  const { stdout } = await exec("powershell.exe", ["-NoProfile", "-NonInteractive", "-Command", script], {
    timeout: 30_000,
    maxBuffer: 64 * 1_024,
    windowsHide: true,
    env: { ...process.env, TASK_NAME: taskName },
  });
  return JSON.parse(stdout);
}

async function temporaryDefinitions() {
  return new Set((await readdir(tmpdir())).filter((name) => name.startsWith("toktrak-task.")));
}

test("given_windowsProcess_when_verifyingInstallPrivileges_then_matchesWindowsPrincipal", {
  skip: process.platform !== "win32",
}, async (context) => {
  const directory = await mkdtemp(path.join(tmpdir(), "toktrak-scheduler-test-"));
  context.after(() => rm(directory, { recursive: true, force: true }));
  const { module: tracker } = await renderedTracker(directory);
  const script =
    "$identity=[Security.Principal.WindowsIdentity]::GetCurrent();" +
    "$principal=[Security.Principal.WindowsPrincipal]::new($identity);" +
    "$principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)";
  const { stdout } = await exec("powershell.exe", ["-NoProfile", "-NonInteractive", "-Command", script], {
    timeout: 30_000,
    maxBuffer: 64 * 1_024,
    windowsHide: true,
  });

  if (stdout.trim() === "True") {
    await assert.rejects(() => tracker.verifyWindowsInstallPrivileges(), /must run without administrator privileges/u);
  } else {
    assert.equal(stdout.trim(), "False");
    await assert.doesNotReject(() => tracker.verifyWindowsInstallPrivileges());
  }
});

test("given_oldWindowsTask_when_runningUpdatedTracker_then_migratesOnceAndPreservesFiles", {
  skip: process.platform !== "win32",
  timeout: 45_000,
}, async (context) => {
  const directory = await mkdtemp(path.join(tmpdir(), "toktrak-tracker-windows-"));
  const taskName = `TokTrak-Test-${randomUUID()}`;
  const runLog = path.join(directory, "runs.log");
  const revision = path.join(directory, "scheduler-revision");
  const sentinel = path.join(directory, "task.xml");
  const fixture = path.join(directory, "Märker.mjs");
  const oldDefinition = path.join(directory, "old-task.xml");
  const definitionsBefore = await temporaryDefinitions();
  let taskCreateAttempted = false;
  context.after(async () => {
    try {
      if (taskCreateAttempted) {
        await command("schtasks.exe", ["/End", "/TN", taskName], true);
        await command("schtasks.exe", ["/Delete", "/TN", taskName, "/F"], true);
        const remaining = await command("schtasks.exe", ["/Query", "/TN", taskName], true);
        assert.ok(remaining instanceof Error, "temporary Scheduled Task remained registered");
      }
    } finally {
      await rm(directory, { recursive: true, force: true });
    }
  });

  const { module: tracker, tracker: trackerPath } = await renderedTracker(directory);
  await writeFile(sentinel, "preserve-me");
  await writeFile(
    fixture,
    `import { appendFile, readFile, writeFile } from "node:fs/promises";\n` +
      `import { fileURLToPath } from "node:url";\n` +
      `import { createWindowsTask } from ${JSON.stringify(pathToFileURL(trackerPath).href)};\n` +
      `const taskName=${JSON.stringify(taskName)};\n` +
      `const revision=${JSON.stringify(revision)};\n` +
      `const runLog=${JSON.stringify(runLog)};\n` +
      `await appendFile(runLog, "run-start\\nargs=" + JSON.stringify(process.argv.slice(2)) + "\\n");\n` +
      `let current;try{current=await readFile(revision,"utf8");}catch(error){if(error?.code!=="ENOENT")throw error;}\n` +
      `if(current!=="1"){await createWindowsTask(taskName,process.execPath,fileURLToPath(import.meta.url),"C:\\\\Pi & Sessions");await writeFile(revision,"1");await appendFile(runLog,"migrated\\n");}else{await appendFile(runLog,"migration-skipped\\n");}\n` +
      `await appendFile(runLog,"run-complete\\n");\n`,
  );

  const desired = tracker.windowsTaskXml(process.execPath, fixture, "C:\\Pi & Sessions");
  const old = desired
    .replace("<StartWhenAvailable>true", "<StartWhenAvailable>false")
    .replace("<DisallowStartIfOnBatteries>false", "<DisallowStartIfOnBatteries>true")
    .replace("<StopIfGoingOnBatteries>false", "<StopIfGoingOnBatteries>true")
    .replace("<ExecutionTimeLimit>PT2H", "<ExecutionTimeLimit>PT72H");
  await writeFile(oldDefinition, Buffer.from(`\ufeff${old}`, "utf16le"));
  taskCreateAttempted = true;
  await command("schtasks.exe", ["/Create", "/TN", taskName, "/XML", oldDefinition, "/F"]);

  assert.deepEqual(await taskSettings(taskName), {
    StartWhenAvailable: false,
    DisallowStartIfOnBatteries: true,
    StopIfGoingOnBatteries: true,
    ExecutionTimeLimit: "PT72H",
    StartBoundary: "2020-01-01T09:00:00",
    State: "Ready",
  });
  await new Promise((resolve) => {
    setTimeout(resolve, 1_000);
  });
  assert.deepEqual(await lines(runLog), []);

  await command("schtasks.exe", ["/Run", "/TN", taskName]);
  await waitFor(
    async () => (await lines(runLog)).filter((line) => line === "run-complete").length === 1,
    "first scheduled run did not complete",
  );
  await waitFor(async () => (await taskSettings(taskName)).State === "Ready", "task stayed running");
  const migrated = await taskSettings(taskName);
  assert.equal(migrated.StartWhenAvailable, true);
  assert.equal(migrated.DisallowStartIfOnBatteries, false);
  assert.equal(migrated.StopIfGoingOnBatteries, false);
  assert.equal(migrated.ExecutionTimeLimit, "PT2H");
  assert.equal(await readFile(revision, "utf8"), "1");
  assert.equal(await readFile(sentinel, "utf8"), "preserve-me");

  await command("schtasks.exe", ["/Run", "/TN", taskName]);
  await waitFor(
    async () => (await lines(runLog)).filter((line) => line === "run-complete").length === 2,
    "second scheduled run did not complete",
  );
  assert.deepEqual(await lines(runLog), [
    "run-start",
    'args=["daily","--scheduled","--pi-path","C:\\\\Pi & Sessions"]',
    "migrated",
    "run-complete",
    "run-start",
    'args=["daily","--scheduled","--pi-path","C:\\\\Pi & Sessions"]',
    "migration-skipped",
    "run-complete",
  ]);
  assert.deepEqual(await temporaryDefinitions(), definitionsBefore);
});
