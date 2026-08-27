import assert from "node:assert/strict";
import { execFile } from "node:child_process";
import { createHash } from "node:crypto";
import { chmod, mkdir, mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import { createServer } from "node:http";
import { tmpdir } from "node:os";
import path from "node:path";
import test from "node:test";
import { promisify } from "node:util";

const exec = promisify(execFile);
const TRACKER_SOURCE = new URL("../../sources/toktrak/assets/private/tracker.mjs", import.meta.url);
const TOKEN = `tt_${"A".repeat(43)}`;
const UPDATE_BYTES_MAX = 512 * 1_024;
const UPLOAD_BYTES_MAX = 5 * 1_024 * 1_024;

function renderTracker(template, baseUrl) {
  const source = template
    .replace("__TOKTRAK_BASE_URL__", JSON.stringify(baseUrl))
    .replace("__TOKTRAK_TOKEN__", JSON.stringify(TOKEN));
  assert.doesNotMatch(source, /__TOKTRAK_(?:BASE_URL|TOKEN)__/u);
  return Buffer.from(source);
}

async function writeFakeNpx(commandDirectory) {
  await mkdir(commandDirectory);
  const source = `const report=process.argv[4];\nif(!["daily","session","blocks"].includes(report))process.exit(2);\nconsole.log(JSON.stringify({[report]:[]}));\n`;
  if (process.platform === "win32") {
    const script = path.join(commandDirectory, "fake-npx.mjs");
    await writeFile(script, source);
    await writeFile(path.join(commandDirectory, "npx.cmd"), `@"${process.execPath}" "${script}" %*\r\n`);
    return;
  }
  const script = path.join(commandDirectory, "npx");
  await writeFile(script, `#!/usr/bin/env node\n${source}`);
  await chmod(script, 0o700);
}

async function runTracker(tracker, commandDirectory, piDirectory) {
  const environment = {
    ...process.env,
    PATH: `${commandDirectory}${path.delimiter}${process.env.PATH ?? ""}`,
    PI_CODING_AGENT_DIR: piDirectory,
  };
  delete environment.PI_AGENT_DIR;
  delete environment.PI_CODING_AGENT_SESSION_DIR;
  const { stderr } = await exec(process.execPath, [tracker, "full"], {
    env: environment,
    timeout: 30_000,
    maxBuffer: 64 * 1_024,
    windowsHide: true,
  });
  return stderr;
}

function sha256(bytes) {
  return createHash("sha256").update(bytes).digest("hex");
}

test("given_trackerUpdateResponses_when_runningFullUpload_then_replacesOnlyVerifiedBoundedContent", async (context) => {
  const directory = await mkdtemp(path.join(tmpdir(), "toktrak-update-test-"));
  const commandDirectory = path.join(directory, "bin");
  const target = path.join(directory, "toktrak.mjs");
  const piDirectory = path.join(directory, "pi-agent");
  let updateBody;
  let updateHash;
  let usageRequests = 0;
  let updateRequests = 0;
  let serverFailure;
  const server = createServer(async (request, response) => {
    try {
      let requestBytes = 0;
      for await (const chunk of request) {
        requestBytes += chunk.length;
        assert.ok(requestBytes <= UPLOAD_BYTES_MAX);
      }
      assert.equal(request.headers.authorization, `Bearer ${TOKEN}`);
      if (request.url === "/api/usage") {
        usageRequests++;
        response.end("{}");
        return;
      }
      assert.equal(request.url, "/api/tracker");
      updateRequests++;
      response.setHeader("X-TokTrak-SHA256", updateHash);
      response.end(updateBody);
    } catch (error) {
      serverFailure ??= error;
      response.statusCode = 500;
      response.end("test server failed");
    }
  });
  await new Promise((resolve) => {
    server.listen(0, "127.0.0.1", resolve);
  });
  context.after(async () => {
    await new Promise((resolve, reject) => {
      server.close((error) => {
        if (error) reject(error);
        else resolve();
      });
    });
    await rm(directory, { recursive: true, force: true });
  });

  const template = await readFile(TRACKER_SOURCE, "utf8");
  const address = server.address();
  assert(address && typeof address === "object");
  const baseUrl = `http://127.0.0.1:${address.port}`;
  const original = renderTracker(template, baseUrl);
  const replacement = Buffer.concat([original, Buffer.from("\n// verified replacement\n")]);
  await writeFakeNpx(commandDirectory);

  updateBody = replacement;
  updateHash = sha256(replacement);
  await writeFile(target, original);
  let output = await runTracker(target, commandDirectory, piDirectory);
  assert.match(output, /tracker updated/u);
  assert.deepEqual(await readFile(target), replacement);

  output = await runTracker(target, commandDirectory, piDirectory);
  assert.match(output, /tracker already current/u);
  assert.deepEqual(await readFile(target), replacement);

  await writeFile(target, original);
  updateHash = "0".repeat(64);
  output = await runTracker(target, commandDirectory, piDirectory);
  assert.match(output, /self-update failed: update SHA-256 is invalid/u);
  assert.deepEqual(await readFile(target), original);

  updateBody = Buffer.alloc(UPDATE_BYTES_MAX + 1, "x");
  updateHash = sha256(updateBody);
  output = await runTracker(target, commandDirectory, piDirectory);
  assert.match(output, /self-update failed: response is too large/u);
  assert.deepEqual(await readFile(target), original);

  assert.equal(usageRequests, 4);
  assert.equal(updateRequests, 4);
  assert.equal(serverFailure, undefined);
});
