import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import { mkdtemp, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import test from "node:test";
import { command, lines, markerFixture, renderedTracker, verifyTwoRuns, waitFor } from "./scheduler-helpers.mjs";

test("given_macGuiScheduler_when_runningTrackerAgent_then_executesAndRemovesIsolatedJob", {
  skip: process.platform !== "darwin",
  timeout: 45_000,
}, async (context) => {
  const directory = await mkdtemp(path.join(tmpdir(), "toktrak-mac-scheduler-"));
  const label = `de.isp-insoft.toktrak.test.${randomUUID()}`;
  const domain = `gui/${process.getuid()}`;
  const job = `${domain}/${label}`;
  let bootstrapAttempted = false;
  context.after(async () => {
    try {
      if (bootstrapAttempted) {
        await command("launchctl", ["bootout", job], true);
        const remaining = await command("launchctl", ["print", job], true);
        assert.ok(
          remaining instanceof Error &&
            /Could not find (?:specified )?service|No such process/u.test(remaining.stderr || remaining.message),
          "temporary LaunchAgent remained registered or could not be queried",
        );
      }
    } finally {
      await rm(directory, { recursive: true, force: true });
    }
  });
  const { script, marker } = await markerFixture(directory);
  const { module: tracker } = await renderedTracker(directory);
  const productionLabel = "<key>Label</key><string>de.isp-insoft.toktrak</string>";
  const rendered = tracker.macPlist(process.execPath, script, directory);
  assert.equal(rendered.split(productionLabel).length, 2);
  const plist = path.join(directory, `${label}.plist`);
  await writeFile(plist, rendered.replace(productionLabel, `<key>Label</key><string>${label}</string>`));
  bootstrapAttempted = true;
  try {
    await command("launchctl", ["bootstrap", domain, plist]);
  } catch (error) {
    throw new Error("native tracker tests require a working macOS GUI LaunchAgent domain", { cause: error });
  }
  const registered = await command("launchctl", ["print", job]);
  assert.match(registered.stdout, /Märker & scheduler\.mjs/u);
  const kickstart = async () => {
    const { stdout } = await command("launchctl", ["kickstart", "-p", job]);
    assert.match(stdout.trim(), /^[1-9][0-9]*$/u);
    const pid = Number(stdout.trim());
    assert.ok(Number.isSafeInteger(pid));
    await waitFor(async () => {
      try {
        process.kill(pid, 0);
        return false;
      } catch (error) {
        if (error.code === "ESRCH") return true;
        throw error;
      }
    }, "LaunchAgent did not exit");
  };
  await verifyTwoRuns(kickstart, marker, "LaunchAgent did not run");
  assert.deepEqual(await lines(marker), [
    JSON.stringify(["daily", "--scheduled", "--pi-path", directory]),
    JSON.stringify(["daily", "--scheduled", "--pi-path", directory]),
  ]);
});
