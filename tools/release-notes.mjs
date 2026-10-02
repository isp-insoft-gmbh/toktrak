#!/usr/bin/env node

import { spawnSync } from "node:child_process";
import { mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const ROOT = resolve(fileURLToPath(new URL("..", import.meta.url)));
const BYTES_MAX = 64 * 1024;
const RELEASES_MAX = 64;
const NOTES_MAX = 128;
const REQUESTS_MAX = 256;
const TITLE_MAX = 480;

const fail = (message) => {
  throw new Error(message);
};

const versionNumber = (tag) => {
  const match = /^v(0|[1-9][0-9]*)$/u.exec(tag);
  if (!match) fail(`invalid release tag: ${tag}`);
  const number = Number(match[1]);
  if (!Number.isSafeInteger(number) || number > 2147483647) fail(`release version exceeds Java int range: ${tag}`);
  return number;
};

const noteText = (value) => {
  const text = String(value ?? "")
    .replace(/[\u0000-\u001f\u007f]/gu, " ")
    .replace(/[\[\]()`]/gu, "")
    .replace(/\s+/gu, " ")
    .trim();
  return text.slice(0, TITLE_MAX);
};

const command = (arguments_, root) => {
  const result = spawnSync("git", arguments_, {
    cwd: root,
    encoding: "utf8",
    maxBuffer: 1024 * 1024,
    timeout: 30_000,
    windowsHide: true,
  });
  if (result.error) fail(`git ${arguments_[0]} failed: ${result.error.message}`);
  if (result.status !== 0) fail(`git ${arguments_[0]} failed: ${(result.stderr || result.stdout).trim()}`);
  return result.stdout;
};

const tags = (execute, root) =>
  execute(["tag", "--list", "v[0-9]*"], root)
    .split(/\r?\n/u)
    .filter(Boolean)
    .filter((tag) => /^v(?:0|[1-9][0-9]*)$/u.test(tag))
    .sort((left, right) => versionNumber(right) - versionNumber(left));

const requireTagSeries = (releaseTags, newest) => {
  const numbers = new Set(releaseTags.map(versionNumber));
  for (let number = newest; number >= 3; number--) {
    if (!numbers.has(number)) fail(`missing release tag v${number}`);
  }
};

const revision = (tag, execute, root) => execute(["rev-parse", "--verify", `${tag}^{}`], root).trim();

const commits = (from, to, execute, root, shallow) => {
  const range = from ? `${from}..${to}` : to;
  let lines;
  try {
    lines = execute(["log", "--format=%H%x00%s", "-n", String(NOTES_MAX + 1), range], root);
  } catch (error) {
    if (!shallow) throw error;
    lines = execute(["log", "--format=%H%x00%s", "-n", String(NOTES_MAX + 1), to], root);
  }
  const entries = lines.split(/\r?\n/u).filter(Boolean);
  if (entries.length > NOTES_MAX) fail(`release range exceeds ${NOTES_MAX} commits`);
  return entries.map((line) => {
    const [sha, subject] = line.split("\0", 2);
    if (!/^[0-9a-f]{40}$/u.test(sha) || !noteText(subject)) fail("git log returned an invalid commit");
    return { sha, subject };
  });
};

const repository = (execute, root) => {
  const remote = execute(["config", "--get", "remote.origin.url"], root).trim();
  const match = /(?:github\.com[/:])([^/\s]+)\/([^/\s]+?)(?:\.git)?$/u.exec(remote);
  if (!match) fail("origin remote is not a GitHub repository");
  return `${match[1]}/${match[2]}`;
};

const githubRequest = async (url, token, request, optional = false) => {
  const response = await request(url, {
    headers: {
      Accept: "application/vnd.github+json",
      Authorization: `Bearer ${token}`,
      "X-GitHub-Api-Version": "2022-11-28",
    },
    signal: AbortSignal.timeout(15_000),
  });
  if (optional && response.status === 404) return undefined;
  if (!response.ok) fail(`GitHub API request failed: ${response.status}`);
  if (Number(response.headers?.get("content-length") ?? 0) > 256 * 1024) fail("GitHub API response exceeds limit");
  return response.json();
};

const releaseNotes = async (commitList, repositoryName, token, request, budget) => {
  const notes = [];
  const pullRequests = new Set();
  for (const commit of commitList) {
    if (budget.remaining-- <= 0) fail("release notes exceed GitHub request limit");
    const associations = await githubRequest(
      `https://api.github.com/repos/${repositoryName}/commits/${commit.sha}/pulls`,
      token,
      request,
    );
    if (!Array.isArray(associations) || associations.length > 100) fail("GitHub returned invalid PR associations");
    const merged = associations.find(
      (pullRequest) =>
        pullRequest?.merged_at &&
        Number.isSafeInteger(pullRequest.number) &&
        pullRequest.base?.ref === "trunk" &&
        pullRequest.base.repo?.full_name?.toLowerCase() === repositoryName.toLowerCase(),
    );
    if (merged && !pullRequests.has(merged.number)) {
      pullRequests.add(merged.number);
      const title = noteText(merged.title);
      if (title) notes.push(`${title} [#${merged.number}](https://github.com/${repositoryName}/pull/${merged.number})`);
    } else if (!merged) {
      const subject = noteText(commit.subject);
      if (subject) notes.push(subject);
    }
    if (notes.length >= NOTES_MAX) break;
  }
  return notes;
};

const tagHighlight = (tag, execute, root) => {
  const subject = execute(["for-each-ref", "--format=%(contents:subject)", `refs/tags/${tag}`], root).trim();
  const body = execute(["for-each-ref", "--format=%(contents:body)", `refs/tags/${tag}`], root).trim();
  return noteText(`${subject === `TokTrak ${tag}` ? "" : subject} ${body}`);
};

const section = (tag, notes, highlight) => {
  const entries = [highlight, ...notes].filter(Boolean).slice(0, NOTES_MAX);
  if (entries.length === 0) entries.push("No release notes were available.");
  return `## ${tag}\n\n${entries.map((entry) => `- ${entry}`).join("\n")}\n`;
};

const baseline = (root) => {
  const source = readFileSync(resolve(root, "CHANGELOG.md"), "utf8");
  if (Buffer.byteLength(source) > BYTES_MAX || !source.startsWith("# Changelog\n"))
    fail("frozen CHANGELOG.md is invalid");
  const headings = [...source.matchAll(/^## (v(?:0|[1-9][0-9]*))$/gmu)].map((match) => match[1]);
  if (headings.join(",") !== "v2,v1,v0") fail("frozen CHANGELOG.md must contain exactly v2, v1, and v0");
  return source.replace(/^# Changelog\n\n/u, "").trimEnd();
};

export async function generate(mode, version, root = ROOT, options = {}) {
  const execute = options.execute ?? command;
  const request = options.request ?? fetch;
  const releaseTags = tags(execute, root);
  const shallow = execute(["rev-parse", "--is-shallow-repository"], root).trim() === "true";
  let current;
  if (mode === "dev") {
    if (version !== undefined) fail("usage: release-notes.mjs dev <output-path>");
    const latest = releaseTags.at(0);
    if (!latest && !shallow) fail("development changelog requires release tags");
    current = `v${latest ? versionNumber(latest) + 1 : 3}`;
  } else if (mode === "release") {
    if (version === undefined) fail("usage: release-notes.mjs release <vN> <output-path>");
    versionNumber(version);
    if (execute(["cat-file", "-t", version], root).trim() !== "tag") fail(`${version} must be an annotated tag`);
    current = version;
  } else {
    fail("usage: release-notes.mjs dev <output-path> | release <vN> <output-path>");
  }
  const currentNumber = versionNumber(current);
  if (currentNumber < 3) fail("generated releases begin at v3");
  const emitted =
    mode === "dev"
      ? [current, ...releaseTags.filter((tag) => versionNumber(tag) >= 3)]
      : releaseTags.filter((tag) => versionNumber(tag) <= currentNumber && versionNumber(tag) >= 3);
  if (mode === "release" && !releaseTags.includes(current)) fail(`${current} is not a release tag`);
  requireTagSeries(emitted.slice(mode === "dev" ? 1 : 0), mode === "dev" ? currentNumber - 1 : currentNumber);
  if (emitted.length > RELEASES_MAX) fail(`release count exceeds ${RELEASES_MAX}`);
  const repositoryName = mode === "release" ? repository(execute, root) : undefined;
  const token = mode === "release" ? process.env.GITHUB_TOKEN?.trim() : undefined;
  if (mode === "release" && !token) fail("release changelog requires GITHUB_TOKEN");
  const rendered = ["# Changelog\n"];
  const budget = { remaining: REQUESTS_MAX };
  for (const tag of emitted) {
    if (mode === "release") {
      if (budget.remaining-- <= 0) fail("release notes exceed GitHub request limit");
      let published = await githubRequest(
        `https://api.github.com/repos/${repositoryName}/releases/tags/${tag}`,
        token,
        request,
        tag === current,
      );
      if (!published && tag === current) {
        if (budget.remaining-- <= 0) fail("release notes exceed GitHub request limit");
        const releases = await githubRequest(
          `https://api.github.com/repos/${repositoryName}/releases?per_page=100`,
          token,
          request,
        );
        if (!Array.isArray(releases) || releases.length > 100) fail("GitHub release list is invalid");
        published = releases.find((candidate) => candidate.tag_name === tag && candidate.draft);
      }
      if (published) {
        const archived = published.body;
        if (
          typeof archived !== "string" ||
          !archived.trim() ||
          Buffer.byteLength(archived) > BYTES_MAX ||
          archived.split("\n").some((line) => line && !line.startsWith("- "))
        ) {
          fail(`published notes for ${tag} are invalid`);
        }
        rendered.push(`## ${tag}\n\n${archived.trim()}\n`);
        continue;
      }
    }
    const number = versionNumber(tag);
    const to = mode === "dev" && tag === current ? "HEAD" : revision(tag, execute, root);
    const from = `v${number - 1}`;
    const commitList =
      shallow && mode === "dev" && tag === current && !releaseTags.length
        ? []
        : commits(from, to, execute, root, shallow && mode === "dev" && tag === current);
    let notes;
    if (mode === "release") {
      notes = await releaseNotes(commitList, repositoryName, token, request, budget);
    } else {
      notes = commitList.map(({ subject }) => noteText(subject));
      if (tag === current && shallow)
        notes.unshift("History is shallow; notes include only locally available commits.");
    }
    rendered.push(section(tag, notes, mode === "release" ? tagHighlight(tag, execute, root) : ""));
  }
  rendered.push(baseline(root));
  const changelog = `${rendered.join("\n").trimEnd()}\n`;
  if (Buffer.byteLength(changelog) > BYTES_MAX) fail("generated changelog exceeds 65536 bytes");
  return changelog;
}

export async function main(arguments_ = process.argv.slice(2)) {
  const [mode, first, second, extra] = arguments_;
  const version = mode === "release" ? first : undefined;
  const output = mode === "release" ? second : first;
  if (extra || !output) fail("usage: release-notes.mjs dev <output-path> | release <vN> <output-path>");
  const changelog = await generate(mode, version, ROOT);
  const destination = resolve(output);
  mkdirSync(dirname(destination), { recursive: true });
  writeFileSync(destination, changelog, "utf8");
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main().catch((error) => {
    console.error(error.message);
    process.exitCode = 1;
  });
}
