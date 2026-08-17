#!/usr/bin/env node
import { spawnSync } from "node:child_process";
import {
  existsSync,
  mkdirSync,
  readdirSync,
  readFileSync,
  renameSync,
  statSync,
  unlinkSync,
  writeFileSync,
} from "node:fs";
import { dirname, join, resolve, sep } from "node:path";
import { fileURLToPath } from "node:url";

const scriptDirectory = dirname(fileURLToPath(import.meta.url));
const skillDirectory = resolve(scriptDirectory, "..");
const root = resolve(skillDirectory, "../../..");
const references = join(skillDirectory, "references");
const outputRoot = join(root, "output", "intellij-inspections");
const workspace = join(root, ".idea", "workspace.xml");

export function writeProfileXml(allIds, enabledIds) {
  const ids = [...new Set(allIds)].sort();
  const known = new Set(ids);
  const enabled = new Set(enabledIds);
  const unknown = enabledIds.filter((id) => !known.has(id));
  if (unknown.length > 0) throw new Error(`unknown IntelliJ inspection IDs: ${unknown.join(", ")}`);
  const lines = ['<profile version="1.0">', '  <option name="myName" value="TokTrak Triaged IntelliJ Inspections" />'];
  for (const id of ids) {
    const on = enabled.has(id);
    lines.push(`  <inspection_tool class="${xml(id)}" enabled="${on}" level="WARNING" enabled_by_default="${on}" />`);
  }
  lines.push("</profile>");
  return `${lines.join("\n")}\n`;
}

export function parseInspectionResults(directory) {
  const findings = [];
  if (!existsSync(directory)) return findings;
  for (const file of walk(directory)) {
    if (!file.endsWith(".xml") || file.endsWith(`${sep}.descriptions.xml`)) continue;
    const xmlText = readFileSync(file, "utf8");
    for (const problem of xmlText.matchAll(/<problem>([\s\S]*?)<\/problem>/g)) {
      const body = problem[1];
      const inspection = tag(body, "problem_class").match(/id="([^"]+)"/)?.[1] ?? basename(file);
      findings.push({
        inspection,
        file: normalizeFile(textTag(body, "file")),
        line: Number(textTag(body, "line") || 0),
        severity: tag(body, "problem_class").match(/severity="([^"]+)"/)?.[1] ?? "",
        description: decodeXml(textTag(body, "description")).replace(/<[^>]*>/g, ""),
      });
    }
  }
  return findings.sort(
    (a, b) => a.inspection.localeCompare(b.inspection) || a.file.localeCompare(b.file) || a.line - b.line,
  );
}

export function renderReport({ runDirectory, profile, results, findings }) {
  const counts = new Map();
  for (const finding of findings) counts.set(finding.inspection, (counts.get(finding.inspection) ?? 0) + 1);
  const sortedCounts = [...counts.entries()].sort((a, b) => b[1] - a[1] || a[0].localeCompare(b[0]));
  const lines = [
    "# TokTrak IntelliJ inspection report",
    "",
    `Run directory: ${relative(runDirectory)}`,
    `Profile: ${relative(profile)}`,
    `Raw results: ${relative(results)}`,
    `Findings: ${findings.length}`,
    "",
    "## Counts",
    "",
    "| Inspection | Findings |",
    "| --- | ---: |",
  ];
  for (const [inspection, count] of sortedCounts) lines.push(`| \`${inspection}\` | ${count} |`);
  lines.push("", "## Findings", "", "| Inspection | Location | Description |", "| --- | --- | --- |");
  for (const finding of findings) {
    lines.push(
      `| \`${finding.inspection}\` | \`${finding.file}:${finding.line}\` | ${markdownCell(finding.description)} |`,
    );
  }
  return `${lines.join("\n")}\n`;
}

export function findInspectExecutable(environment = process.env, platform = process.platform) {
  if (isFile(environment.TOKTRAK_INTELLIJ_INSPECT)) return environment.TOKTRAK_INTELLIJ_INSPECT;

  const candidates = [];
  if (platform === "win32") {
    addWindowsCandidates(candidates, environment.LOCALAPPDATA);
    addWindowsCandidates(candidates, environment.LOCALAPPDATA && join(environment.LOCALAPPDATA, "Programs"));
    addWindowsCandidates(candidates, environment.ProgramFiles);
    addWindowsCandidates(candidates, environment["ProgramFiles(x86)"]);
    addRecursiveCandidates(
      candidates,
      environment.LOCALAPPDATA && join(environment.LOCALAPPDATA, "JetBrains", "Toolbox", "apps"),
      "inspect.bat",
    );
  } else if (platform === "darwin") {
    candidates.push(
      "/Applications/IntelliJ IDEA.app/Contents/bin/inspect.sh",
      "/Applications/IntelliJ IDEA CE.app/Contents/bin/inspect.sh",
      join(environment.HOME ?? "", "Applications", "IntelliJ IDEA.app", "Contents", "bin", "inspect.sh"),
    );
    addRecursiveCandidates(
      candidates,
      join(environment.HOME ?? "", "Library", "Application Support", "JetBrains", "Toolbox", "apps"),
      "inspect.sh",
    );
  } else {
    candidates.push("/opt/idea/bin/inspect.sh", "/opt/intellij-idea/bin/inspect.sh");
    for (const parent of ["/opt", "/usr/local", join(environment.HOME ?? "", ".local", "share")]) {
      if (!existsSync(parent)) continue;
      for (const child of boundedEntries(parent, 128)) {
        if (/idea|intellij/i.test(child)) candidates.push(join(parent, child, "bin", "inspect.sh"));
      }
    }
    addRecursiveCandidates(
      candidates,
      join(environment.HOME ?? "", ".local", "share", "JetBrains", "Toolbox", "apps"),
      "inspect.sh",
    );
  }
  for (const candidate of candidates) if (isFile(candidate)) return candidate;
  return null;
}

function main() {
  const commandArguments = process.argv.slice(2);
  if (commandArguments.length > 0) {
    if (commandArguments.length === 1 && commandArguments[0] === "--help") {
      console.log("usage: node .claude/skills/toktrak-intellij-inspection/scripts/run.mjs");
      return;
    }
    throw new Error(`unexpected arguments: ${commandArguments.join(" ")}`);
  }
  const inspect = findInspectExecutable();
  if (inspect == null) {
    throw new Error("IntelliJ inspect executable not found; install IntelliJ IDEA or set TOKTRAK_INTELLIJ_INSPECT");
  }

  const timestamp = timestampText(new Date());
  const runDirectory = join(outputRoot, `run-${timestamp}-triaged`);
  const results = join(runDirectory, "results");
  const profileDirectory = join(outputRoot, `profile-${timestamp}-triaged`);
  const profile = join(profileDirectory, "TokTrakTriaged.xml");
  mkdirSync(results, { recursive: true });
  mkdirSync(profileDirectory, { recursive: true });

  const allInspectionIds = readIds("intellij-inspection-ids.txt");
  const enabledIds = readIds("triaged-inspection-ids.txt");
  writeFileSync(profile, writeProfileXml(allInspectionIds, enabledIds));

  const log = join(runDirectory, "run.log");
  append(log, `inspect=${inspect}\nprofile=${profile}\nresults=${results}\n\n`);
  run(log, "mise", ["run", "ide", "intellij"]);
  const restoreWorkspace = suspendWorkspace(runDirectory);
  try {
    for (const scope of inspectionScopes()) runInspectionScope(log, inspect, profile, results, scope);
  } finally {
    restoreWorkspace();
  }

  const findings = parseInspectionResults(results);
  const report = join(runDirectory, "report.md");
  const tsv = join(runDirectory, "findings.tsv");
  writeFileSync(report, renderReport({ runDirectory, profile, results, findings }));
  writeFileSync(tsv, findingsTsv(findings));
  writeFileSync(join(outputRoot, "latest-triaged-run.txt"), `${runDirectory}\n`);
  console.log(report);
}

function runInspectionScope(log, inspect, profile, results, scope) {
  if (isFile(workspace)) unlinkSync(workspace);
  const scopeResults = join(results, scope.name);
  mkdirSync(scopeResults, { recursive: true });
  const output = run(log, inspect, [root, profile, scopeResults, "-d", join(root, scope.path), "-v2"]);
  if (!output.includes("Scanning scope")) {
    throw new Error(`IntelliJ did not scan inspection scope: ${scope.name}`);
  }
}

function run(log, executable, args) {
  append(log, `$ ${[executable, ...args].map(quote).join(" ")}\n`);
  const command = windowsBatch(executable)
    ? ["cmd.exe", ["/d", "/c", "call", executable, ...args]]
    : [executable, args];
  const result = spawnSync(command[0], command[1], {
    cwd: root,
    encoding: "utf8",
    maxBuffer: 64 * 1024 * 1024,
  });
  append(log, result.stdout ?? "");
  append(log, result.stderr ?? "");
  append(log, `\nexit=${result.status}\n\n`);
  if (result.error) throw result.error;
  const output = `${result.stdout ?? ""}\n${result.stderr ?? ""}`;
  if (result.status !== 0) {
    if (output.includes("Only one instance of IDEA can be run at a time")) {
      throw new Error("IntelliJ is already running; close it before running offline inspections");
    }
    throw new Error(`${executable} failed with exit code ${result.status}; see ${log}`);
  }
  return output;
}

function readIds(name) {
  return readFileSync(join(references, name), "utf8").split(/\r?\n/).filter(Boolean);
}

function suspendWorkspace(runDirectory) {
  if (!isFile(workspace)) return () => {};
  const backup = join(runDirectory, "workspace.xml.backup");
  renameSync(workspace, backup);
  return () => {
    if (!isFile(backup)) return;
    if (isFile(workspace)) unlinkSync(workspace);
    renameSync(backup, workspace);
  };
}

function inspectionScopes() {
  return [
    { name: "app", path: join("sources", "toktrak") },
    { name: "testmod", path: join("tests", "toktrak.tests") },
    { name: "buildtests", path: join("tests", "tools") },
    { name: "tools", path: "tools" },
  ];
}

function addWindowsCandidates(candidates, parent) {
  if (!parent || !existsSync(parent)) return;
  for (const child of boundedEntries(parent, 256)) {
    if (/IntelliJ IDEA/i.test(child)) candidates.push(join(parent, child, "bin", "inspect.bat"));
    const jetbrains = join(parent, child);
    if (child === "JetBrains" && existsSync(jetbrains)) {
      for (const product of boundedEntries(jetbrains, 256)) {
        if (/IntelliJ IDEA/i.test(product)) candidates.push(join(jetbrains, product, "bin", "inspect.bat"));
      }
    }
  }
}

function addRecursiveCandidates(candidates, root, executableName) {
  if (!root || !existsSync(root)) return;
  const pending = [{ directory: root, depth: 0 }];
  let visited = 0;
  while (pending.length > 0 && visited < 4096) {
    const { directory, depth } = pending.pop();
    visited += 1;
    for (const name of boundedEntries(directory, 256)) {
      const path = join(directory, name);
      if (name.toLowerCase() === executableName.toLowerCase()) candidates.push(path);
      if (depth < 8 && isDirectory(path)) pending.push({ directory: path, depth: depth + 1 });
    }
  }
}

function boundedEntries(directory, limit) {
  try {
    return readdirSync(directory).slice(0, limit);
  } catch {
    return [];
  }
}

function isDirectory(path) {
  try {
    return statSync(path).isDirectory();
  } catch {
    return false;
  }
}

function walk(directory) {
  const pending = [directory];
  const files = [];
  while (pending.length > 0) {
    const current = pending.pop();
    for (const name of boundedEntries(current, 10000)) {
      const path = join(current, name);
      const stat = statSync(path);
      if (stat.isDirectory()) pending.push(path);
      else if (stat.isFile()) files.push(path);
      if (files.length > 100000) throw new Error("IntelliJ result tree exceeds 100000 files");
    }
  }
  return files;
}

function textTag(source, name) {
  return decodeXml(source.match(new RegExp(`<${name}>([\\s\\S]*?)<\\/${name}>`))?.[1] ?? "");
}

function tag(source, name) {
  return source.match(new RegExp(`<${name}([\\s\\S]*?)<\\/${name}>`))?.[0] ?? "";
}

function basename(file) {
  return file.substring(file.lastIndexOf(sep) + 1).replace(/\.xml$/, "");
}

function normalizeFile(value) {
  return value.replace("file://$PROJECT_DIR$/", "").replace(/^file:\/\//, "");
}

function findingsTsv(findings) {
  const lines = ["inspection\tfile\tline\tseverity\tdescription"];
  for (const finding of findings) {
    lines.push([finding.inspection, finding.file, finding.line, finding.severity, finding.description].join("\t"));
  }
  return `${lines.join("\n")}\n`;
}

function timestampText(date) {
  const pad = (number) => String(number).padStart(2, "0");
  return `${date.getFullYear()}${pad(date.getMonth() + 1)}${pad(date.getDate())}-${pad(date.getHours())}${pad(date.getMinutes())}${pad(date.getSeconds())}`;
}

function relative(path) {
  return path.startsWith(root) ? path.slice(root.length + 1) : path;
}

function xml(value) {
  return value.replaceAll("&", "&amp;").replaceAll('"', "&quot;").replaceAll("<", "&lt;").replaceAll(">", "&gt;");
}

function decodeXml(value) {
  return value
    .replaceAll("&lt;", "<")
    .replaceAll("&gt;", ">")
    .replaceAll("&quot;", '"')
    .replaceAll("&apos;", "'")
    .replaceAll("&amp;", "&");
}

function markdownCell(value) {
  return value.replaceAll("|", "\\|").replace(/\s+/g, " ");
}

function quote(value) {
  return /\s/.test(value) ? `"${value.replaceAll('"', '\\"')}"` : value;
}

function windowsBatch(value) {
  return process.platform === "win32" && /\.(?:bat|cmd)$/i.test(value);
}

function append(file, text) {
  writeFileSync(file, text, { flag: "a" });
}

function isFile(path) {
  try {
    return statSync(path).isFile();
  } catch {
    return false;
  }
}

if (resolve(fileURLToPath(import.meta.url)) === resolve(process.argv[1] ?? "")) main();
