#!/usr/bin/env node

import { execFile } from "node:child_process";
import { createHash, randomBytes, randomInt } from "node:crypto";
import { existsSync } from "node:fs";
import {
  chmod,
  mkdir,
  open,
  readFile,
  readdir,
  rename,
  rm,
} from "node:fs/promises";
import { homedir } from "node:os";
import path from "node:path";
import { setTimeout as delay } from "node:timers/promises";
import { fileURLToPath } from "node:url";
import { promisify } from "node:util";

const BASE_URL = __TOKTRAK_BASE_URL__;
const TOKEN = __TOKTRAK_TOKEN__;
const TRACKER_VERSION = "0";
const CCUSAGE_VERSION = "20.0.17";
const REPORTS = ["daily", "session", "blocks"];
const SOURCE_COMMANDS = new Set([
  "claude",
  "codex",
  "opencode",
  "amp",
  "droid",
  "codebuff",
  "hermes",
  "pi",
  "goose",
  "openclaw",
  "kilo",
  "kimi",
  "qwen",
  "copilot",
  "gemini",
  "grok",
]);
const JITTER_MILLIS_MAX = 30 * 60 * 1_000;
const COMMAND_TIMEOUT_MILLIS = 2 * 60 * 1_000;
const HTTP_TIMEOUT_MILLIS = 30 * 1_000;
const COMMAND_OUTPUT_BYTES_MAX = 1_500_000;
const UPLOAD_BYTES_MAX = 5 * 1_024 * 1_024;
const PROJECT_PATH_CHARACTERS_MAX = 2_048;
const UPDATE_BYTES_MAX = 512 * 1_024;
const SETTINGS_BYTES_MAX = 256 * 1_024;
const CONFIG_CHARACTERS_MAX = 8 * 1_024;
const LOG_CHARACTERS_MAX = 1_000;
const CURRENT_SCRIPT = fileURLToPath(import.meta.url);
const PLATFORM_NAME =
  process.platform === "win32" ? "Windows" : process.platform === "darwin" ? "macOS" : "Linux";
const exec = promisify(execFile);

function log(level, message) {
  const clean = String(message)
    .replaceAll(TOKEN, "[redacted]")
    .replace(/[\u0000-\u001f\u007f]/g, " ")
    .slice(0, LOG_CHARACTERS_MAX);
  console.error(`[toktrak] ${level}: ${clean}`);
}

function requiredPath(value, name) {
  if (!value || value.length > 4_096 || /[\u0000\r\n]/.test(value)) {
    throw new Error(`${name} is unavailable`);
  }
  return value;
}

function installationPath() {
  if (process.platform === "win32") {
    return path.join(requiredPath(process.env.LOCALAPPDATA, "LocalAppData"), "TokTrak", "toktrak.mjs");
  }
  if (process.platform === "darwin") {
    return path.join(homedir(), "Library", "Application Support", "TokTrak", "toktrak.mjs");
  }
  const dataHome = process.env.XDG_DATA_HOME || path.join(homedir(), ".local", "share");
  return path.join(requiredPath(dataHome, "XDG data directory"), "toktrak", "toktrak.mjs");
}

function configHome() {
  return requiredPath(process.env.XDG_CONFIG_HOME || path.join(homedir(), ".config"), "XDG config directory");
}

function quoted(value) {
  if (/[\u0000\r\n]/.test(value)) throw new Error("scheduler path is invalid");
  return `"${value.replaceAll("\\", "\\\\").replaceAll('"', '\\"')}"`;
}

function xml(value) {
  return value
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&apos;");
}

export function linuxUnits(nodePath, scriptPath, piSessions) {
  const fallback = piSessions ? ` --pi-path ${quoted(piSessions)}` : "";
  const command = `${quoted(nodePath)} ${quoted(scriptPath)} daily --scheduled${fallback}`;
  return {
    service: `[Unit]\nDescription=TokTrak usage uploader\n\n[Service]\nType=oneshot\nExecStart=${command}\n`,
    timer: "[Unit]\nDescription=Upload TokTrak usage daily\n\n[Timer]\nOnCalendar=daily\nRandomizedDelaySec=30m\nPersistent=true\n\n[Install]\nWantedBy=timers.target\n",
  };
}

export function macPlist(nodePath, scriptPath, piSessions) {
  const fallback = piSessions
    ? `<string>--pi-path</string><string>${xml(piSessions)}</string>`
    : "";
  return `<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key><string>de.isp-insoft.toktrak</string>
  <key>ProgramArguments</key>
  <array><string>${xml(nodePath)}</string><string>${xml(scriptPath)}</string><string>daily</string><string>--scheduled</string>${fallback}</array>
  <key>StartCalendarInterval</key><dict><key>Hour</key><integer>9</integer></dict>
  <key>StandardOutPath</key><string>/dev/null</string>
  <key>StandardErrorPath</key><string>/dev/null</string>
</dict>
</plist>
`;
}

export function windowsTaskArgs(nodePath, scriptPath, piSessions) {
  if (
    /["\r\n]/.test(nodePath) ||
    /["\r\n]/.test(scriptPath) ||
    (piSessions && /["\r\n]/.test(piSessions))
  ) {
    throw new Error("scheduler path is invalid");
  }
  const fallback = piSessions ? ` --pi-path "${piSessions}"` : "";
  return [
    "/Create",
    "/SC",
    "DAILY",
    "/TN",
    "TokTrak",
    "/TR",
    `"${nodePath}" "${scriptPath}" daily --scheduled${fallback}`,
    "/ST",
    "09:00",
    "/RL",
    "LIMITED",
    "/F",
  ];
}

async function atomicWrite(destination, bytes) {
  await mkdir(path.dirname(destination), { recursive: true, mode: 0o700 });
  const temporary = `${destination}.${process.pid}.${randomBytes(6).toString("hex")}.tmp`;
  let handle;
  try {
    handle = await open(temporary, "wx", 0o600);
    await handle.writeFile(bytes);
    await handle.sync();
    await handle.close();
    handle = undefined;
    await rename(temporary, destination);
    if (process.platform !== "win32") await chmod(destination, 0o700);
  } finally {
    if (handle) await handle.close();
    await rm(temporary, { force: true });
  }
}

async function nativeCommand(command, args, tolerateFailure = false) {
  try {
    await exec(command, args, {
      timeout: 30_000,
      maxBuffer: 64 * 1_024,
      windowsHide: true,
    });
  } catch (error) {
    if (!tolerateFailure) throw error;
  }
}

async function installScheduler(scriptPath, piSessions) {
  if (process.platform === "win32") {
    log("info", "creating user scheduled task TokTrak");
    await nativeCommand("schtasks.exe", windowsTaskArgs(process.execPath, scriptPath, piSessions));
    return;
  }
  if (process.platform === "darwin") {
    const plistPath = path.join(homedir(), "Library", "LaunchAgents", "de.isp-insoft.toktrak.plist");
    log("info", `creating user LaunchAgent at ${plistPath}`);
    await atomicWrite(plistPath, Buffer.from(macPlist(process.execPath, scriptPath, piSessions)));
    const domain = `gui/${process.getuid()}`;
    await nativeCommand("launchctl", ["bootout", domain, plistPath], true);
    await nativeCommand("launchctl", ["bootstrap", domain, plistPath]);
    return;
  }
  const unitDirectory = path.join(configHome(), "systemd", "user");
  log("info", `creating systemd user timer in ${unitDirectory}`);
  const units = linuxUnits(process.execPath, scriptPath, piSessions);
  await atomicWrite(path.join(unitDirectory, "toktrak.service"), Buffer.from(units.service));
  await atomicWrite(path.join(unitDirectory, "toktrak.timer"), Buffer.from(units.timer));
  await nativeCommand("systemctl", ["--user", "daemon-reload"]);
  await nativeCommand("systemctl", ["--user", "enable", "--now", "toktrak.timer"]);
}

async function uninstallScheduler() {
  if (process.platform === "win32") {
    log("info", "removing user scheduled task TokTrak");
    await nativeCommand("schtasks.exe", ["/Delete", "/TN", "TokTrak", "/F"], true);
    return;
  }
  if (process.platform === "darwin") {
    const plistPath = path.join(homedir(), "Library", "LaunchAgents", "de.isp-insoft.toktrak.plist");
    log("info", `removing user LaunchAgent at ${plistPath}`);
    await nativeCommand("launchctl", ["bootout", `gui/${process.getuid()}`, plistPath], true);
    await rm(plistPath, { force: true });
    return;
  }
  const unitDirectory = path.join(configHome(), "systemd", "user");
  log("info", `removing systemd user timer from ${unitDirectory}`);
  await nativeCommand("systemctl", ["--user", "disable", "--now", "toktrak.timer"], true);
  await rm(path.join(unitDirectory, "toktrak.timer"), { force: true });
  await rm(path.join(unitDirectory, "toktrak.service"), { force: true });
  await nativeCommand("systemctl", ["--user", "daemon-reload"], true);
}

async function readBounded(file, bytesMax) {
  const handle = await open(file, "r");
  try {
    const buffer = Buffer.alloc(bytesMax + 1);
    let length = 0;
    for (let reads = 0; reads <= bytesMax && length < buffer.length; reads++) {
      const { bytesRead } = await handle.read(buffer, length, buffer.length - length, null);
      if (bytesRead === 0) return buffer.subarray(0, length);
      length += bytesRead;
    }
    if (length > bytesMax) throw new Error(`configuration exceeds ${bytesMax} bytes`);
    throw new Error("configuration file read made no progress");
  } finally {
    await handle.close();
  }
}

function configValue(value, name) {
  if (
    typeof value !== "string" ||
    !value ||
    value.length > CONFIG_CHARACTERS_MAX ||
    /[\u0000\r\n]/.test(value)
  ) {
    throw new Error(`${name} is invalid`);
  }
  return value;
}

function configPath(value, name) {
  const valid = configValue(value, name);
  const expanded =
    valid === "~"
      ? homedir()
      : valid.startsWith("~/") || valid.startsWith("~\\")
        ? path.join(homedir(), valid.slice(2))
        : valid;
  if (!path.isAbsolute(expanded)) throw new Error(`${name} must be absolute or start with ~`);
  return path.normalize(expanded);
}

async function piSessions(fallback) {
  if (process.env.PI_AGENT_DIR) {
    return { value: configValue(process.env.PI_AGENT_DIR, "PI_AGENT_DIR"), source: "PI_AGENT_DIR" };
  }
  if (process.env.PI_CODING_AGENT_SESSION_DIR) {
    return {
      value: configPath(
        process.env.PI_CODING_AGENT_SESSION_DIR,
        "PI_CODING_AGENT_SESSION_DIR",
      ),
      source: "PI_CODING_AGENT_SESSION_DIR",
    };
  }
  const agentDirectory = configPath(
    process.env.PI_CODING_AGENT_DIR || path.join(homedir(), ".pi", "agent"),
    "PI_CODING_AGENT_DIR",
  );
  try {
    const settings = JSON.parse(
      (await readBounded(path.join(agentDirectory, "settings.json"), SETTINGS_BYTES_MAX)).toString(
        "utf8",
      ),
    );
    if (settings?.sessionDir !== undefined) {
      return {
        value: configPath(settings.sessionDir, "Pi settings sessionDir"),
        source: "Pi settings",
      };
    }
  } catch (error) {
    if (error?.code !== "ENOENT") {
      log("warning", `Pi settings ignored: ${error?.message || "invalid settings"}`);
    }
  }
  const defaultSessions = path.join(agentDirectory, "sessions");
  if (existsSync(defaultSessions)) {
    return { value: defaultSessions, source: "Pi agent directory" };
  }
  if (fallback) {
    return {
      value: configValue(fallback, "scheduled Pi path"),
      source: "scheduler fallback",
    };
  }
  return null;
}

async function ccusageEnvironment(fallback) {
  const environment = { ...process.env, LOG_LEVEL: "0", NO_COLOR: "1" };
  const sessions = await piSessions(fallback);
  if (sessions) {
    environment.PI_AGENT_DIR = sessions.value;
    log("info", `Pi sessions: ${sessions.value} (${sessions.source})`);
  } else {
    log("info", "Pi sessions: ccusage configuration or default path");
  }
  return environment;
}

function sinceDate() {
  const date = new Date();
  date.setDate(date.getDate() - 6);
  return date.toISOString().slice(0, 10).replaceAll("-", "");
}

async function executeCcusage(commandArguments, full, environment, byAgent = false) {
  const timezone = Intl.DateTimeFormat().resolvedOptions().timeZone || "UTC";
  const args = [
    "--yes",
    `ccusage@${CCUSAGE_VERSION}`,
    ...commandArguments,
    "--json",
    "--timezone",
    timezone,
  ];
  if (byAgent) args.push("--by-agent");
  if (!full) args.push("--since", sinceDate());
  const options = {
    env: environment,
    timeout: COMMAND_TIMEOUT_MILLIS,
    maxBuffer: COMMAND_OUTPUT_BYTES_MAX,
    windowsHide: true,
  };
  let stdout;
  if (process.platform === "win32") {
    if (args.some((argument) => !/^[A-Za-z0-9@._+:/-]+$/.test(argument))) {
      throw new Error("ccusage argument is invalid");
    }
    ({ stdout } = await exec(process.env.ComSpec || "cmd.exe", [
      "/d",
      "/s",
      "/c",
      ["npx.cmd", ...args].join(" "),
    ], options));
  } else {
    ({ stdout } = await exec("npx", args, options));
  }
  return JSON.parse(stdout);
}

async function ccusage(report, full, environment) {
  const json = await executeCcusage([report], full, environment, report === "daily");
  if (!json || !Array.isArray(json[report])) throw new Error(`${report} JSON is invalid`);
  return json;
}

function detectedSources(reports) {
  const sources = new Set();
  if (reports.session?.ok) {
    for (const row of reports.session.json.session) sources.add(row.agent);
  }
  if (reports.daily?.ok) {
    for (const row of reports.daily.json.daily) {
      for (const agent of row.agents ?? []) sources.add(agent.agent);
      for (const agent of row.metadata?.agents ?? []) sources.add(agent);
    }
  }
  sources.delete("all");
  return [...sources].filter((source) => SOURCE_COMMANDS.has(source)).sort();
}

function sourceProjectPath(row) {
  for (const name of ["projectPath", "directory", "cwd", "workspacePath", "project"]) {
    const value = row[name];
    if (typeof value === "string" && value && value.length <= PROJECT_PATH_CHARACTERS_MAX) {
      return value;
    }
  }
  return null;
}

function enrichSessionRows(sessionJson, sourceReports) {
  const sourceRows = new Map();
  for (const [source, reports] of Object.entries(sourceReports)) {
    if (!reports.session.ok) continue;
    for (const row of reports.session.json.sessions) {
      sourceRows.set(`${source}\n${row.sessionId}`, row);
    }
  }
  for (const row of sessionJson.session) {
    if (row.metadata?.projectPath) continue;
    const sourceRow = sourceRows.get(`${row.agent}\n${row.period}`);
    const projectPath = sourceRow && sourceProjectPath(sourceRow);
    if (projectPath) row.metadata = { ...(row.metadata ?? {}), projectPath };
  }
}

async function collectSourceReports(reports, full, environment) {
  const sourceReports = {};
  for (const source of detectedSources(reports)) {
    const sourceResult = {};
    for (const report of ["daily", "session"]) {
      const rowsName = report === "session" ? "sessions" : "daily";
      try {
        log("info", `reading ${source} ${report} detail with ccusage@${CCUSAGE_VERSION}`);
        const json = await executeCcusage([source, report], full, environment);
        if (!json || !Array.isArray(json[rowsName])) {
          throw new Error(`${source} ${report} JSON is invalid`);
        }
        sourceResult[report] = { ok: true, json };
        log("info", `${source} ${report} detail ready (${json[rowsName].length} rows)`);
      } catch (error) {
        log(
          "warning",
          `${source} ${report} detail failed: ${error?.message || "unknown error"}`,
        );
        sourceResult[report] = { ok: false, error: `${source} ${report} detail failed` };
      }
    }
    sourceReports[source] = sourceResult;
  }
  reports.sourceReports = sourceReports;
  if (reports.session?.ok) enrichSessionRows(reports.session.json, sourceReports);
}

async function collectReports(full, fallback) {
  const reports = {};
  const environment = await ccusageEnvironment(fallback);
  for (const report of REPORTS) {
    try {
      log("info", `reading ${report} usage with ccusage@${CCUSAGE_VERSION}`);
      const json = await ccusage(report, full, environment);
      reports[report] = { ok: true, json };
      log("info", `${report} usage ready (${json[report].length} rows)`);
    } catch (error) {
      log("warning", `${report} report failed: ${error?.message || "unknown error"}`);
      reports[report] = { ok: false, error: `${report} report failed` };
    }
  }
  await collectSourceReports(reports, full, environment);
  return reports;
}

async function fetchBounded(url, options, bytesMax) {
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), HTTP_TIMEOUT_MILLIS);
  try {
    const response = await fetch(url, {
      ...options,
      redirect: "error",
      signal: controller.signal,
    });
    if (bytesMax === 0) {
      await response.body?.cancel();
      return { response, body: Buffer.alloc(0) };
    }
    const declared = Number(response.headers.get("content-length"));
    if (Number.isFinite(declared) && declared > bytesMax) throw new Error("response is too large");
    const chunks = [];
    let length = 0;
    for await (const chunk of response.body || []) {
      length += chunk.length;
      if (length > bytesMax) throw new Error("response is too large");
      chunks.push(chunk);
    }
    return { response, body: Buffer.concat(chunks, length) };
  } finally {
    clearTimeout(timeout);
  }
}

function uploadBody(payload) {
  let body = JSON.stringify(payload);
  const sourceReports = payload.reports.sourceReports;
  const sources = sourceReports ? Object.keys(sourceReports).reverse() : [];
  while (Buffer.byteLength(body) > UPLOAD_BYTES_MAX && sources.length) {
    const source = sources.shift();
    delete sourceReports[source];
    log("warning", `omitting ${source} source report to fit upload limit`);
    body = JSON.stringify(payload);
  }
  if (Buffer.byteLength(body) > UPLOAD_BYTES_MAX) {
    throw new Error(`usage upload exceeds ${UPLOAD_BYTES_MAX} bytes`);
  }
  return body;
}

async function upload(payload) {
  const body = uploadBody(payload);
  log("info", `uploading usage snapshot (${Buffer.byteLength(body)} bytes)`);
  let lastError;
  for (let attempt = 0; attempt < 2; attempt++) {
    try {
      const { response } = await fetchBounded(
        `${BASE_URL}/api/usage`,
        {
          method: "POST",
          headers: {
            Authorization: `Bearer ${TOKEN}`,
            "Content-Type": "application/json",
          },
          body,
        },
        0,
      );
      if (!response.ok) throw new Error(`upload returned HTTP ${response.status}`);
      return;
    } catch (error) {
      lastError = error;
      if (attempt === 0) log("warning", "upload failed; retrying once");
    }
  }
  throw lastError;
}

async function update(target) {
  const { response, body } = await fetchBounded(
    `${BASE_URL}/api/tracker`,
    { headers: { Authorization: `Bearer ${TOKEN}` } },
    UPDATE_BYTES_MAX,
  );
  if (!response.ok) throw new Error(`update returned HTTP ${response.status}`);
  const expected = response.headers.get("x-toktrak-sha256");
  const actual = createHash("sha256").update(body).digest("hex");
  if (!expected || !/^[0-9a-f]{64}$/.test(expected) || expected !== actual) {
    throw new Error("update SHA-256 is invalid");
  }
  const current = createHash("sha256").update(await readFile(target)).digest("hex");
  if (current === actual) return false;
  await atomicWrite(target, body);
  return true;
}

async function runUpload(full, updateTarget, fallback) {
  log("info", `collecting ${full ? "full" : "seven-day"} usage snapshot`);
  const reports = await collectReports(full, fallback);
  const successful = Object.values(reports).filter((report) => report.ok).length;
  log("info", `${successful}/${REPORTS.length} usage reports ready`);
  await upload({
    trackerVersion: TRACKER_VERSION,
    ccusageVersion: CCUSAGE_VERSION,
    clientTimeZone: Intl.DateTimeFormat().resolvedOptions().timeZone || "UTC",
    full,
    generatedAt: new Date().toISOString(),
    reports,
  });
  log("info", "usage uploaded");
  try {
    log("info", "checking for tracker update");
    const updated = await update(updateTarget);
    log("info", updated ? "tracker updated" : "tracker already current");
  } catch (error) {
    log("warning", `self-update failed: ${error?.message || "unknown error"}`);
  }
}

async function install() {
  const target = installationPath();
  const sessions = await piSessions();
  log("info", `copying tracker to ${target}`);
  await atomicWrite(target, await readFile(CURRENT_SCRIPT));
  if (sessions) {
    log("info", `recording scheduler fallback for Pi sessions at ${sessions.value}`);
  }
  await installScheduler(target, sessions?.value);
  log("info", "scheduler installed; running initial upload");
  await runUpload(true, target);
  log("info", `installation complete at ${target}`);
}

async function uninstall() {
  const target = installationPath();
  await uninstallScheduler();
  log("info", `removing installed tracker at ${target}`);
  await rm(target, { force: true });
  try {
    if ((await readdir(path.dirname(target))).length === 0) await rm(path.dirname(target), { recursive: true });
  } catch (error) {
    if (error?.code !== "ENOENT") throw error;
  }
  log("info", "uninstalled");
}

async function main() {
  const [mode = "install", ...options] = process.argv.slice(2);
  const scheduled = mode === "daily" && options[0] === "--scheduled";
  const fallback =
    scheduled && options.length === 3 && options[1] === "--pi-path" ? options[2] : undefined;
  const validOptions =
    (mode === "daily" && (options.length === 0 || (scheduled && [1, 3].includes(options.length)))) ||
    (mode !== "daily" && options.length === 0);
  if (!validOptions || (options.length === 3 && !fallback)) {
    throw new Error(
      "usage: node toktrak.mjs [install|full|daily [--scheduled [--pi-path path]]|uninstall]",
    );
  }
  log("info", `starting ${mode} mode on ${PLATFORM_NAME}`);
  switch (mode) {
    case "install":
      await install();
      break;
    case "full":
      await runUpload(true, CURRENT_SCRIPT);
      break;
    case "daily":
      if (scheduled) {
        const jitterMillis = randomInt(JITTER_MILLIS_MAX + 1);
        log("info", `scheduled run waiting ${Math.ceil(jitterMillis / 1_000)} seconds`);
        await delay(jitterMillis);
      }
      await runUpload(false, CURRENT_SCRIPT, fallback);
      break;
    case "uninstall":
      await uninstall();
      break;
    default:
      throw new Error(
        "usage: node toktrak.mjs [install|full|daily [--scheduled [--pi-path path]]|uninstall]",
      );
  }
}

if (process.argv[1] && path.resolve(process.argv[1]) === path.resolve(CURRENT_SCRIPT)) {
  main().catch((error) => {
    log("error", error?.message || "tracker failed");
    process.exitCode = 1;
  });
}
