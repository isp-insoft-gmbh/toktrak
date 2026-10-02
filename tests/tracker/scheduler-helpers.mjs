import assert from "node:assert/strict";
import { execFile } from "node:child_process";
import { randomUUID } from "node:crypto";
import { readFile, writeFile } from "node:fs/promises";
import path from "node:path";
import { pathToFileURL } from "node:url";
import { promisify } from "node:util";

const exec = promisify(execFile);
const TRACKER_SOURCE = new URL("../../sources/toktrak/assets/private/tracker.mjs", import.meta.url);
const TOKEN = `tt_${"A".repeat(43)}`;

export async function renderedTracker(directory) {
  const template = await readFile(TRACKER_SOURCE, "utf8");
  const source = template
    .replace("__TOKTRAK_BASE_URL__", JSON.stringify("https://toktrak.invalid"))
    .replace("__TOKTRAK_TOKEN__", JSON.stringify(TOKEN));
  assert.doesNotMatch(source, /__TOKTRAK_(?:BASE_URL|TOKEN)__/u);
  const tracker = path.join(directory, "tracker-under-test.mjs");
  await writeFile(tracker, source);
  return { module: await import(`${pathToFileURL(tracker)}?test=${randomUUID()}`), tracker };
}

export async function command(executable, arguments_, tolerateFailure = false) {
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

export async function waitFor(predicate, message) {
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

export async function lines(file) {
  try {
    return (await readFile(file, "utf8")).trim().split("\n");
  } catch (error) {
    if (error?.code === "ENOENT") return [];
    throw error;
  }
}

export async function verifyTwoRuns(run, marker, message) {
  await run();
  await waitFor(async () => (await lines(marker)).length === 1, message);
  await run();
  await waitFor(async () => (await lines(marker)).length === 2, message);
}

export async function markerFixture(directory) {
  const script = path.join(directory, "Märker & scheduler.mjs");
  const marker = path.join(directory, "runs.log");
  await writeFile(
    script,
    `import { appendFile } from "node:fs/promises";\n` +
      `await appendFile(${JSON.stringify(marker)}, JSON.stringify(process.argv.slice(2)) + "\\n");\n`,
  );
  return { script, marker };
}
