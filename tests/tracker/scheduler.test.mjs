import assert from "node:assert/strict";
import { execFile } from "node:child_process";
import { randomUUID } from "node:crypto";
import { mkdtemp, readFile, readdir, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import test from "node:test";
import { pathToFileURL } from "node:url";
import { promisify } from "node:util";

const exec = promisify(execFile);
const TRACKER_SOURCE = new URL("../../sources/toktrak/assets/private/tracker.mjs", import.meta.url);
const TOKEN = `tt_${"A".repeat(43)}`;

async function renderedTracker(directory) {
  const template = await readFile(TRACKER_SOURCE, "utf8");
  const source = template
    .replace("__TOKTRAK_BASE_URL__", JSON.stringify("https://toktrak.invalid"))
    .replace("__TOKTRAK_TOKEN__", JSON.stringify(TOKEN));
  assert.doesNotMatch(source, /__TOKTRAK_(?:BASE_URL|TOKEN)__/u);
  const tracker = path.join(directory, "tracker-under-test.mjs");
  await writeFile(tracker, source);
  return { module: await import(`${pathToFileURL(tracker)}?test=${randomUUID()}`), tracker };
}

async function command(executable, arguments_, tolerateFailure = false) {
  try {
    return await exec(executable, arguments_, {
      timeout: 30_000,
      maxBuffer: 64 * 1_024,
      windowsHide: true,
    });
  } catch (error) {
    if (!tolerateFailure) throw error;
    return error;
  }
}

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

async function waitFor(predicate, message) {
  const deadline = Date.now() + 15_000;
  while (Date.now() < deadline) {
    // oxlint-disable-next-line no-await-in-loop -- Each poll observes state after the prior delay.
    if (await predicate()) return;
    // oxlint-disable-next-line no-await-in-loop -- Serial delay bounds the polling rate.
    await new Promise((resolve) => {
      setTimeout(resolve, 100);
    });
  }
  assert.fail(message);
}

async function lines(file) {
  try {
    return (await readFile(file, "utf8")).trim().split("\n");
  } catch (error) {
    if (error?.code === "ENOENT") return [];
    throw error;
  }
}

async function temporaryDefinitions() {
  return new Set((await readdir(tmpdir())).filter((name) => name.startsWith("toktrak-task.")));
}

test("given_schedulerInputs_when_renderingWindowsDefinition_then_emitsBoundedNativeXml", async (context) => {
  const directory = await mkdtemp(path.join(tmpdir(), "toktrak-tracker-test-"));
  context.after(() => rm(directory, { recursive: true, force: true }));
  const { module: tracker } = await renderedTracker(directory);
  const nodePath = "C:\\Node & Runtime\\node.exe";
  const scriptPath = "C:\\Üser & Co\\toktrak.mjs";
  const piSessions = "C:\\Pi & Sessions";
  const document = tracker.windowsTaskXml(nodePath, scriptPath, piSessions);
  const definition = tracker.windowsTaskDefinition(nodePath, scriptPath, piSessions);

  assert.ok(definition.length < 64 * 1_024);
  assert.deepEqual([...definition.subarray(0, 2)], [0xff, 0xfe]);
  assert.equal(definition.subarray(2).toString("utf16le"), document);
  assert.match(document, /<StartBoundary>2020-01-01T09:00:00<\/StartBoundary>/u);
  assert.match(document, /<StartWhenAvailable>true<\/StartWhenAvailable>/u);
  assert.match(document, /<DisallowStartIfOnBatteries>false<\/DisallowStartIfOnBatteries>/u);
  assert.match(document, /<StopIfGoingOnBatteries>false<\/StopIfGoingOnBatteries>/u);
  assert.match(document, /<ExecutionTimeLimit>PT2H<\/ExecutionTimeLimit>/u);
  assert.match(document, /C:\\Node &amp; Runtime\\node.exe/u);
  assert.match(document, /C:\\Üser &amp; Co\\toktrak.mjs/u);
  assert.match(document, /C:\\Pi &amp; Sessions/u);
  assert.throws(() => tracker.windowsTaskXml('C:\\bad"path', scriptPath), /scheduler path is invalid/u);
  await assert.rejects(tracker.createWindowsTask("", nodePath, scriptPath), /scheduler task name is invalid/u);
});

test("given_schedulerInputs_when_renderingPortableDefinitions_then_quotesAndEscapesPaths", async (context) => {
  const directory = await mkdtemp(path.join(tmpdir(), "toktrak-scheduler-test-"));
  context.after(() => rm(directory, { recursive: true, force: true }));
  const { module: tracker } = await renderedTracker(directory);

  const linux = tracker.linuxUnits("/opt/Node & Runtime/node", '/home/O"Connor/toktrak.mjs', "/home/pi sessions");
  assert.equal(
    linux.service,
    `[Unit]\nDescription=TokTrak usage uploader\n\n[Service]\nType=oneshot\nExecStart="/opt/Node & Runtime/node" "/home/O\\"Connor/toktrak.mjs" daily --scheduled --pi-path "/home/pi sessions"\n`,
  );
  assert.equal(
    linux.timer,
    "[Unit]\nDescription=Upload TokTrak usage daily\n\n[Timer]\nOnCalendar=daily\nRandomizedDelaySec=30m\nPersistent=true\n\n[Install]\nWantedBy=timers.target\n",
  );
  assert.throws(() => tracker.linuxUnits("/node\0path", "/tracker"), /scheduler path is invalid/u);

  const mac = tracker.macPlist(
    "/Applications/Node & <Runtime>/node",
    `/Users/O'Connor/"tracker".mjs`,
    "/Users/Pi & Sessions",
  );
  assert.match(mac, /<string>\/Applications\/Node &amp; &lt;Runtime&gt;\/node<\/string>/u);
  assert.match(mac, /<string>\/Users\/O&apos;Connor\/&quot;tracker&quot;\.mjs<\/string>/u);
  assert.match(mac, /<string>--pi-path<\/string><string>\/Users\/Pi &amp; Sessions<\/string>/u);
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
  let taskCreated = false;
  context.after(async () => {
    if (taskCreated) {
      await command("schtasks.exe", ["/End", "/TN", taskName], true);
      await command("schtasks.exe", ["/Delete", "/TN", taskName, "/F"], true);
    }
    await rm(directory, { recursive: true, force: true });
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
  await command("schtasks.exe", ["/Create", "/TN", taskName, "/XML", oldDefinition, "/F"]);
  taskCreated = true;

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
