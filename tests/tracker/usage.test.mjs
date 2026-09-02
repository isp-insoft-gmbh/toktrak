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

function sha256(bytes) {
  return createHash("sha256").update(bytes).digest("hex");
}

async function writeFakeNpx(commandDirectory) {
  await mkdir(commandDirectory);
  if (process.platform === "win32") {
    await writeFile(
      path.join(commandDirectory, "npx.cmd"),
      `@echo off\r\n` +
        `echo %*>>"%FAKE_LOG%"\r\n` +
        `echo %PI_AGENT_DIR%>>"%FAKE_PI_LOG%"\r\n` +
        `if "%3"=="daily" echo {"daily":[{"period":"2026-08-15","agent":"all","inputTokens":1,"outputTokens":1,"cacheCreationTokens":0,"cacheReadTokens":0,"totalTokens":2,"totalCost":0.1}]}\r\n` +
        `if "%3"=="session" echo {"session":[{"period":"codex-session","agent":"codex","metadata":{"lastActivity":"2026-08-15T00:00:00Z"}}]}\r\n` +
        `if "%3"=="blocks" if "%FAKE_PARTIAL%"=="1" exit /b 7\r\n` +
        `if "%3"=="blocks" echo {"blocks":[]}\r\n` +
        `if "%3"=="codex" if "%4"=="daily" echo {"daily":[{"date":"2026-08-15","reasoningOutputTokens":7}],"totals":{}}\r\n` +
        `if "%3"=="codex" if "%4"=="session" echo {"sessions":[{"sessionId":"codex-session","directory":"C:\\\\work\\\\project","futureField":{"preserved":true}}],"totals":{}}\r\n`,
    );
    return;
  }
  const command = path.join(commandDirectory, "npx");
  await writeFile(
    command,
    `#!/bin/sh\n` +
      `printf '%s\\n' "$*" >>"$FAKE_LOG"\n` +
      `printf '%s\\n' "$PI_AGENT_DIR" >>"$FAKE_PI_LOG"\n` +
      `case "$3" in\n` +
      `daily) printf '%s\\n' '{"daily":[{"period":"2026-08-15","agent":"all","inputTokens":1,"outputTokens":1,"cacheCreationTokens":0,"cacheReadTokens":0,"totalTokens":2,"totalCost":0.1}]}' ;;\n` +
      `session) printf '%s\\n' '{"session":[{"period":"codex-session","agent":"codex","metadata":{"lastActivity":"2026-08-15T00:00:00Z"}}]}' ;;\n` +
      `blocks) [ "$FAKE_PARTIAL" = 1 ] && exit 7; printf '%s\\n' '{"blocks":[]}' ;;\n` +
      `codex) if [ "$4" = daily ]; then printf '%s\\n' '{"daily":[{"date":"2026-08-15","reasoningOutputTokens":7}],"totals":{}}'; else printf '%s\\n' '{"sessions":[{"sessionId":"codex-session","directory":"/work/project","futureField":{"preserved":true}}],"totals":{}}'; fi ;;\n` +
      `esac\n`,
  );
  await chmod(command, 0o700);
}

async function runTracker(tracker, commandDirectory, commandLog, piLog, piAgent, partial, mode, sessionDirectory) {
  const bootstrap = path.join(commandDirectory, "runtime-bootstrap.mjs");
  const scheduledNode = path.join(commandDirectory, path.basename(process.execPath));
  await writeFile(bootstrap, `process.execPath = ${JSON.stringify(scheduledNode)};\n`);
  const environment = {
    ...process.env,
    PATH: `${commandDirectory}${path.delimiter}${process.env.PATH ?? ""}`,
    FAKE_LOG: commandLog,
    FAKE_PI_LOG: piLog,
    FAKE_PARTIAL: partial,
    PI_CODING_AGENT_DIR: piAgent,
  };
  delete environment.PI_AGENT_DIR;
  if (sessionDirectory === undefined) delete environment.PI_CODING_AGENT_SESSION_DIR;
  else environment.PI_CODING_AGENT_SESSION_DIR = sessionDirectory;
  const { stdout, stderr } = await exec(process.execPath, ["--import", pathToFileURL(bootstrap).href, tracker, mode], {
    env: environment,
    timeout: 30_000,
    maxBuffer: 64 * 1_024,
    windowsHide: true,
  });
  return stdout + stderr;
}

async function lines(file) {
  return (await readFile(file, "utf8")).trim().split(/\r?\n/u);
}

test("given_fakeCcusageAndServer_when_runningFullAndDaily_then_retriesUploadsPartialsAndUpdates", async (context) => {
  const directory = await mkdtemp(path.join(tmpdir(), "toktrak-usage-test-"));
  const commandDirectory = path.join(directory, "bin");
  const tracker = path.join(directory, "toktrak.mjs");
  const commandLog = path.join(directory, "ccusage.log");
  const piLog = path.join(directory, "pi.log");
  const piSessions = path.join(directory, "pi sessions");
  const environmentPiSessions = path.join(directory, "environment pi sessions");
  const piAgent = path.join(directory, "pi agent");
  let usageRequests = 0;
  let updateRequests = 0;
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
        usageRequests++;
        if (usageRequests === 2) {
          response.writeHead(503);
        } else {
          uploads.push(Buffer.concat(chunks).toString("utf8"));
        }
        response.end("{}");
        return;
      }
      assert.equal(request.url, "/api/tracker");
      updateRequests++;
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

  const address = server.address();
  assert(address && typeof address === "object");
  script = renderTracker(await readFile(TRACKER_SOURCE, "utf8"), `http://127.0.0.1:${address.port}`);
  await writeFile(tracker, script);
  await Promise.all([mkdir(piSessions), mkdir(environmentPiSessions), mkdir(piAgent), writeFakeNpx(commandDirectory)]);
  await writeFile(path.join(piAgent, "settings.json"), JSON.stringify({ sessionDir: piSessions }));

  const full = await runTracker(
    tracker,
    commandDirectory,
    commandLog,
    piLog,
    piAgent,
    "0",
    "full",
    environmentPiSessions,
  );
  const daily = await runTracker(tracker, commandDirectory, commandLog, piLog, piAgent, "1", "daily", undefined);

  assert.doesNotMatch(full, /DeprecationWarning/u);
  assert.doesNotMatch(daily, /DeprecationWarning/u);
  assert.match(full, /starting full mode on /u);
  assert.match(full, /collecting full usage snapshot/u);
  assert.match(full, /reading daily usage with ccusage@20\.0\.17/u);
  assert.match(full, /daily usage ready \(1 rows\)/u);
  assert.match(full, /3\/3 usage reports ready/u);
  assert.match(full, /reading codex daily detail/u);
  assert.match(full, /codex daily detail ready \(1 rows\)/u);
  assert.match(full, /reading codex session detail/u);
  assert.match(full, /codex session detail ready \(1 rows\)/u);
  assert.match(full, /checking for tracker update/u);
  assert.match(full, /tracker already current/u);
  assert.ok(full.includes(`Pi sessions: ${environmentPiSessions} (PI_CODING_AGENT_SESSION_DIR)`));
  assert.ok(daily.includes(`Pi sessions: ${piSessions} (Pi settings)`));
  assert.match(daily, /2\/3 usage reports ready/u);
  assert.match(daily, /retrying once/u);

  assert.equal(usageRequests, 3);
  assert.equal(updateRequests, 2);
  assert.equal(uploads.length, 2);
  assert.ok(uploads[0].includes('"full":true'));
  assert.ok(uploads[0].includes('"session":{"ok":true'));
  assert.ok(
    uploads[0].includes(
      `"projectPath":${JSON.stringify(process.platform === "win32" ? "C:\\work\\project" : "/work/project")}`,
    ),
  );
  assert.ok(uploads[0].includes('"sourceReports":{"codex":{"daily":{"ok":true'));
  assert.ok(uploads[0].includes('"reasoningOutputTokens":7'));
  assert.ok(uploads[0].includes('"futureField":{"preserved":true'));
  assert.ok(uploads[1].includes('"full":false'));
  assert.ok(uploads[1].includes('"blocks":{"ok":false'));
  assert.ok(uploads[1].includes('"sourceReports":{"codex":{"daily":{"ok":true'));
  assert.deepEqual(await readFile(tracker), script);

  const commands = await lines(commandLog);
  assert.equal(commands.length, 10);
  assert.ok(commands.slice(0, 5).every((value) => !value.includes("--since")));
  assert.ok(commands.slice(5).every((value) => value.includes("--since")));
  assert.ok(commands.every((value) => value.includes("ccusage@20.0.17")));
  assert.deepEqual(await lines(piLog), [...Array(5).fill(environmentPiSessions), ...Array(5).fill(piSessions)]);
  assert.equal(serverFailure, undefined);
});
