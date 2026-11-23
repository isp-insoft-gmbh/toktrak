import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import { closeSync, openSync } from "node:fs";
import { mkdtemp, readFile, readdir, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import test from "node:test";
import { pathToFileURL } from "node:url";
import { command, lines, renderedTracker, waitFor } from "./scheduler-helpers.mjs";

// Task Scheduler omits settings that equal these schema defaults from exported XML.
const SETTING_DEFAULTS = {
  StartWhenAvailable: "false",
  DisallowStartIfOnBatteries: "true",
  StopIfGoingOnBatteries: "true",
  ExecutionTimeLimit: "PT72H",
};

// schtasks exports locale-independent XML in a fraction of a second; PowerShell's
// Get-ScheduledTask took 3 to 5 seconds per call, and one PowerShell call hung past 30 seconds.
async function taskSettings(taskName) {
  const { stdout } = await command("schtasks.exe", ["/Query", "/TN", taskName, "/XML"]);
  const element = (name) => stdout.match(new RegExp(`<${name}>([^<]*)</${name}>`, "u"))?.[1];
  const setting = (name) => element(name) ?? SETTING_DEFAULTS[name];
  return {
    StartWhenAvailable: setting("StartWhenAvailable") === "true",
    DisallowStartIfOnBatteries: setting("DisallowStartIfOnBatteries") === "true",
    StopIfGoingOnBatteries: setting("StopIfGoingOnBatteries") === "true",
    ExecutionTimeLimit: setting("ExecutionTimeLimit"),
    StartBoundary: element("StartBoundary"),
  };
}

/// Opening the raw disk device for reading requires an elevated token.
function elevated() {
  try {
    closeSync(openSync("\\\\.\\PHYSICALDRIVE0", "r"));
    return true;
  } catch (error) {
    if (error?.code === "EPERM" || error?.code === "EACCES") return false;
    throw error;
  }
}

function running(pid) {
  try {
    process.kill(pid, 0);
    return true;
  } catch (error) {
    if (error?.code === "ESRCH") return false;
    throw error;
  }
}

// The task ignores a start request while an instance runs, so callers wait for the previous
// run's process to exit first; 120 immediate requests after such an exit all started a run.
async function startRun(taskName, runLog, count) {
  await command("schtasks.exe", ["/Run", "/TN", taskName]);
  await waitFor(
    async () => (await lines(runLog)).filter((line) => line === "run-start").length === count,
    `scheduled run ${count} did not start`,
  );
}

async function waitForRun(runLog, pidLog, count) {
  await waitFor(
    async () => (await lines(runLog)).filter((line) => line === "run-complete").length === count,
    `scheduled run ${count} did not complete`,
  );
  const pid = Number((await lines(pidLog))[count - 1]);
  assert.ok(Number.isSafeInteger(pid) && pid > 0, `run ${count} recorded no process ID`);
  await waitFor(async () => !running(pid), `scheduled run ${count} did not exit`);
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

  const elevatedProcess = elevated();
  context.diagnostic(`elevated: ${elevatedProcess}`);
  if (elevatedProcess) {
    await assert.rejects(() => tracker.verifyWindowsInstallPrivileges(), /must run without administrator privileges/u);
  } else {
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
  const pidLog = path.join(directory, "pids.log");
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
      `await appendFile(${JSON.stringify(pidLog)}, process.pid + "\\n");\n` +
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
  });

  await startRun(taskName, runLog, 1);
  await waitForRun(runLog, pidLog, 1);
  const migrated = await taskSettings(taskName);
  assert.equal(migrated.StartWhenAvailable, true);
  assert.equal(migrated.DisallowStartIfOnBatteries, false);
  assert.equal(migrated.StopIfGoingOnBatteries, false);
  assert.equal(migrated.ExecutionTimeLimit, "PT2H");
  assert.equal(await readFile(revision, "utf8"), "1");
  assert.equal(await readFile(sentinel, "utf8"), "preserve-me");

  await startRun(taskName, runLog, 2);
  await waitForRun(runLog, pidLog, 2);
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
