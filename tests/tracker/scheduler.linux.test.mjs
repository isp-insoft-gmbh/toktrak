import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import { mkdtemp, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import test from "node:test";
import { command, lines, markerFixture, renderedTracker, verifyTwoRuns } from "./scheduler-helpers.mjs";

test("given_linuxUserScheduler_when_runningTrackerService_then_executesAndRemovesIsolatedUnits", {
  skip: process.platform !== "linux",
  timeout: 45_000,
}, async (context) => {
  const directory = await mkdtemp(path.join(tmpdir(), "toktrak-linux-scheduler-"));
  const unitName = `toktrak-test-${randomUUID()}`;
  const serviceName = `${unitName}.service`;
  const timerName = `${unitName}.timer`;
  let linked = false;
  context.after(async () => {
    try {
      if (linked) {
        await command("systemctl", ["--user", "stop", timerName, serviceName], true);
        await command("systemctl", ["--user", "disable", "--runtime", timerName], true);
        await command("systemctl", ["--user", "disable", "--runtime", serviceName], true);
        const timerState = await command("systemctl", ["--user", "show", timerName, "--property=LoadState", "--value"]);
        const serviceState = await command("systemctl", [
          "--user",
          "show",
          serviceName,
          "--property=LoadState",
          "--value",
        ]);
        assert.equal(timerState.stdout.trim(), "not-found", "temporary timer remained registered");
        assert.equal(serviceState.stdout.trim(), "not-found", "temporary service remained registered");
      }
    } finally {
      await rm(directory, { recursive: true, force: true });
    }
  });
  try {
    await command("systemctl", ["--user", "show", "--property=Version", "--value"]);
  } catch (error) {
    throw new Error("native tracker tests require a running systemd user manager", { cause: error });
  }
  const { script, marker } = await markerFixture(directory);
  const { module: tracker } = await renderedTracker(directory);
  const units = tracker.linuxUnits(process.execPath, script, directory);
  const service = path.join(directory, serviceName);
  const timer = path.join(directory, timerName);
  await writeFile(service, units.service);
  await writeFile(timer, units.timer);
  linked = true;
  await command("systemctl", ["--user", "link", "--runtime", service, timer]);
  const registered = await command("systemctl", ["--user", "cat", serviceName, timerName]);
  assert.match(registered.stdout, /OnCalendar=daily/u);
  assert.match(registered.stdout, /--scheduled/u);
  await verifyTwoRuns(
    () => command("systemctl", ["--user", "start", serviceName]),
    marker,
    "systemd service did not run",
  );
  assert.deepEqual(await lines(marker), [
    JSON.stringify(["daily", "--scheduled", "--pi-path", directory]),
    JSON.stringify(["daily", "--scheduled", "--pi-path", directory]),
  ]);
});
