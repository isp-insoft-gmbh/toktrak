import assert from "node:assert/strict";
import { mkdir, mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import test from "node:test";
import { renderedTracker } from "./scheduler-helpers.mjs";

test("given_windowsGroupOutput_when_readingIntegrityLevel_then_returnsMandatoryLevel", async (context) => {
  const directory = await mkdtemp(path.join(tmpdir(), "toktrak-scheduler-test-"));
  context.after(() => rm(directory, { recursive: true, force: true }));
  const { module: tracker } = await renderedTracker(directory);

  assert.equal(tracker.windowsIntegrityLevel('"Mandatory Label","S-1-16-8192"'), 8_192);
  assert.equal(tracker.windowsIntegrityLevel('"Mandatory Label","S-1-16-12288"'), 12_288);
  assert.throws(() => tracker.windowsIntegrityLevel("no integrity SID"), /could not be determined/u);
  assert.throws(() => tracker.windowsIntegrityLevel("S-1-16-8192 S-1-16-12288"), /could not be determined/u);
});

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

test("given_dailyMacScheduler_when_renderingDefinition_then_runsOnceAtNine", async (context) => {
  const directory = await mkdtemp(path.join(tmpdir(), "toktrak-scheduler-test-"));
  context.after(() => rm(directory, { recursive: true, force: true }));
  const { module: tracker } = await renderedTracker(directory);

  const document = tracker.macPlist("/usr/local/bin/node", "/Users/example/toktrak.mjs");

  assert.match(
    document,
    /<key>StartCalendarInterval<\/key><dict><key>Hour<\/key><integer>9<\/integer><key>Minute<\/key><integer>0<\/integer><\/dict>/u,
  );
});

test("given_releasedWildcardMacSchedule_when_preparingRuns_then_repairsAndRunsOncePerDay", async (context) => {
  const directory = await mkdtemp(path.join(tmpdir(), "toktrak-scheduler-test-"));
  const platform = Object.getOwnPropertyDescriptor(process, "platform");
  const homeVariable = process.platform === "win32" ? "USERPROFILE" : "HOME";
  const home = process.env[homeVariable];
  context.after(async () => {
    Object.defineProperty(process, "platform", platform);
    if (home === undefined) delete process.env[homeVariable];
    else process.env[homeVariable] = home;
    await rm(directory, { recursive: true, force: true });
  });
  Object.defineProperty(process, "platform", { ...platform, value: "darwin" });
  process.env[homeVariable] = directory;
  const { module: tracker } = await renderedTracker(directory);
  const scriptPath = path.join(directory, "Library", "Application Support", "TokTrak", "toktrak.mjs");
  const plistPath = path.join(directory, "Library", "LaunchAgents", "de.isp-insoft.toktrak.plist");
  const released = tracker.macPlist(process.execPath, scriptPath).replace("<key>Minute</key><integer>0</integer>", "");
  await mkdir(path.dirname(plistPath), { recursive: true });
  await writeFile(plistPath, released);

  const first = await tracker.prepareMacScheduledRun(scriptPath, new Date(2026, 8, 7, 9));

  assert.equal(first.status, "pending");
  assert.match(await readFile(plistPath, "utf8"), /<key>Minute<\/key><integer>0<\/integer>/u);
  await tracker.completeMacScheduledRun(first);
  assert.equal((await tracker.prepareMacScheduledRun(scriptPath, new Date(2026, 8, 7, 10))).status, "uploaded");
  assert.equal((await tracker.prepareMacScheduledRun(scriptPath, new Date(2026, 8, 8, 9))).status, "pending");
});
