import assert from "node:assert/strict";
import { execFile } from "node:child_process";
import { createHash } from "node:crypto";
import { chmod, mkdir, mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import { createServer } from "node:http";
import { tmpdir } from "node:os";
import path from "node:path";
import test from "node:test";
import { pathToFileURL } from "node:url";
import { promisify } from "node:util";

const exec = promisify(execFile);
const TRACKER_SOURCE = new URL("../../sources/toktrak/assets/private/tracker.mjs", import.meta.url);
const TOKEN = `tt_${"A".repeat(43)}`;
const UPLOAD_BYTES_MAX = 5 * 1_024 * 1_024;

function renderTracker(template, baseUrl) {
  const source = template
    .replace("__TOKTRAK_BASE_URL__", JSON.stringify(baseUrl))
    .replace("__TOKTRAK_TOKEN__", JSON.stringify(TOKEN));
  assert.doesNotMatch(source, /__TOKTRAK_(?:BASE_URL|TOKEN)__/u);
  return Buffer.from(source);
}

async function writeFakeNpx(runtimeDirectory) {
  if (process.platform === "win32") {
    await writeFile(
      path.join(runtimeDirectory, "npx.cmd"),
      `@echo off\r\n` +
        `if "%3"=="daily" echo {"daily":[]}\r\n` +
        `if "%3"=="session" echo {"session":[]}\r\n` +
        `if "%3"=="blocks" echo {"blocks":[]}\r\n`,
    );
    return;
  }
  const command = path.join(runtimeDirectory, "npx");
  await writeFile(
    command,
    `#!/bin/sh\n` +
      `case "$3" in\n` +
      `daily) printf '%s\\n' '{"daily":[]}' ;;\n` +
      `session) printf '%s\\n' '{"session":[]}' ;;\n` +
      `blocks) printf '%s\\n' '{"blocks":[]}' ;;\n` +
      `*) exit 2 ;;\n` +
      `esac\n`,
  );
  await chmod(command, 0o700);
}

function sha256(bytes) {
  return createHash("sha256").update(bytes).digest("hex");
}

test("given_nodeRuntimeOutsideSchedulerPath_when_collectingUsage_then_usesAdjacentNpx", async (context) => {
  const directory = await mkdtemp(path.join(tmpdir(), "toktrak-runtime-path-test-"));
  const runtimeDirectory = path.join(directory, "node-runtime");
  const emptyPath = path.join(directory, "scheduler-path");
  const tracker = path.join(directory, "toktrak.mjs");
  const bootstrap = path.join(directory, "bootstrap.mjs");
  const uploads = [];
  let script;
  let serverFailure;
  const server = createServer(async (request, response) => {
    try {
      let requestBytes = 0;
      const chunks = [];
      for await (const chunk of request) {
        requestBytes += chunk.length;
        assert.ok(requestBytes <= UPLOAD_BYTES_MAX);
        chunks.push(chunk);
      }
      assert.equal(request.headers.authorization, `Bearer ${TOKEN}`);
      if (request.url === "/api/usage") {
        uploads.push(JSON.parse(Buffer.concat(chunks).toString("utf8")));
        response.end("{}");
        return;
      }
      assert.equal(request.url, "/api/tracker");
      response.setHeader("X-TokTrak-SHA256", sha256(script));
      response.end(script);
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

  await Promise.all([mkdir(runtimeDirectory), mkdir(emptyPath)]);
  await writeFakeNpx(runtimeDirectory);
  const address = server.address();
  assert(address && typeof address === "object");
  script = renderTracker(await readFile(TRACKER_SOURCE, "utf8"), `http://127.0.0.1:${address.port}`);
  await writeFile(tracker, script);
  const scheduledNode = path.join(runtimeDirectory, path.basename(process.execPath));
  await writeFile(bootstrap, `process.execPath = ${JSON.stringify(scheduledNode)};\n`);
  const environment = {
    ...process.env,
    PATH: emptyPath,
    PI_CODING_AGENT_DIR: path.join(directory, "pi-agent"),
  };
  delete environment.PI_AGENT_DIR;
  delete environment.PI_CODING_AGENT_SESSION_DIR;

  const { stderr } = await exec(process.execPath, ["--import", pathToFileURL(bootstrap).href, tracker, "full"], {
    env: environment,
    timeout: 30_000,
    maxBuffer: 64 * 1_024,
    windowsHide: true,
  });

  assert.match(stderr, /3\/3 usage reports ready/u);
  assert.equal(uploads.length, 1);
  for (const report of ["daily", "session", "blocks"]) {
    assert.equal(uploads[0].reports[report].ok, true);
  }
  assert.equal(serverFailure, undefined);
});
