#!/usr/bin/env node

import { createHash, randomUUID } from "node:crypto";
import { spawnSync } from "node:child_process";
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { javaRuntimeVersion } from "./java-runtime-version.mjs";
import { publishRelease } from "./release-publication.mjs";

const arguments_ = process.argv.slice(2);
const [operation, displayVersion] = arguments_;
const release = /^v(0|[1-9][0-9]*)$/u.exec(displayVersion ?? "");
if (
  arguments_.length !== 2 ||
  (operation !== "verify" && operation !== "release") ||
  (operation === "verify" && displayVersion !== "dev") ||
  (operation === "release" && !release)
) {
  throw new Error("usage: node tools/container-ci.mjs verify dev | release vN");
}

const version = release ? Number(release[1]) : 0;
if (!Number.isSafeInteger(version) || version > 2_147_483_647)
  throw new Error("release version exceeds Java int range");
const revision = command("git", ["rev-parse", "--verify", "HEAD"]).trim();
if (!/^[0-9a-f]{40}$/u.test(revision)) throw new Error("git HEAD is not a full SHA-1 revision");
const changelogBytes = readFileSync("output/release/CHANGELOG.md");
if (changelogBytes.length === 0 || changelogBytes.length > 65_536)
  throw new Error("generated changelog is missing or exceeds 65536 bytes");
const changelogSha256 = createHash("sha256").update(changelogBytes).digest("hex");
const containerfile = readFileSync("Containerfile", "utf8");
const builder = /^FROM \S+:([1-9][0-9]{0,2})-jdk@sha256:[0-9a-f]{64} AS build$/mu.exec(containerfile);
if (Buffer.byteLength(containerfile) > 64 * 1024 || !builder)
  throw new Error("Containerfile builder must use a feature-tagged, digest-pinned JDK image");
const hostJava = commandResult("java", ["-XshowSettings:properties", "-version"]);
if (hostJava.status !== 0 || Number.parseInt(javaRuntimeVersion(hostJava.stderr), 10) !== Number(builder[1]))
  throw new Error("Containerfile builder Java feature differs from CI build Java");
if (command("podman", ["info", "--format", "{{.Host.Security.Rootless}}"]).trim() !== "true") {
  throw new Error("container verification requires rootless Podman");
}

const suffix = randomUUID().replaceAll("-", "");
const image = `localhost/toktrak-ci:${suffix}`;
const container = `toktrak-verify-${suffix}`;
const volume = `toktrak-verify-${suffix}`;
const publication = operation === "release" ? publicationConfig(displayVersion) : undefined;
const environmentDirectory = mkdtempSync(join(tmpdir(), "toktrak-container-ci-"));
const environment = join(environmentDirectory, "environment");
let failure;
let imageBuilt = false;
let registryLoggedIn = false;
let containerCreated = false;
let volumeCreated = false;
try {
  let existingImageId;
  if (publication) {
    command("podman", ["login", publication.host, "--username", publication.username, "--password-stdin"], {
      input: publication.password,
    });
    registryLoggedIn = true;
    const existing = commandResult("podman", [
      "pull",
      "--policy",
      "always",
      `${publication.repository}:${displayVersion}`,
    ]);
    if (existing.status === 0) {
      existingImageId = canonicalImageId(
        command("podman", [
          "image",
          "inspect",
          "--format",
          "{{.Id}}",
          `${publication.repository}:${displayVersion}`,
        ]).trim(),
      );
    } else if (!/manifest unknown|name unknown|manifest[^\n]*not found|manifest[^\n]*404/iu.test(existing.stderr)) {
      throw new Error("cannot determine whether versioned image already exists");
    }
  }
  if (!existingImageId) {
    command("podman", [
      "build",
      "--no-cache",
      "--timestamp",
      "0",
      "--file",
      "Containerfile",
      "--tag",
      image,
      "--build-arg",
      `VERSION=${version}`,
      "--build-arg",
      `REVISION=${revision}`,
      "--build-arg",
      `DISPLAY_VERSION=${displayVersion}`,
      "--build-arg",
      `CHANGELOG_SHA256=${changelogSha256}`,
      ".",
    ]);
    imageBuilt = true;
  }
  const imageId =
    existingImageId ?? canonicalImageId(command("podman", ["image", "inspect", "--format", "{{.Id}}", image]).trim());
  inspectImage(imageId, version, revision, displayVersion);
  const linkedRuntime = commandResult("podman", [
    "run",
    "--rm",
    "--entrypoint",
    "/opt/toktrak/bin/java",
    imageId,
    "-XshowSettings:properties",
    "-version",
  ]);
  if (linkedRuntime.status !== 0) throw new Error("linked Java runtime did not start");
  if (javaRuntimeVersion(linkedRuntime.stderr) !== javaRuntimeVersion(hostJava.stderr))
    throw new Error("linked Java runtime differs from the CI build runtime");
  command("podman", [
    "run",
    "--rm",
    "--entrypoint",
    "/opt/toktrak/bin/java",
    imageId,
    "-ea",
    "-m",
    "toktrak/toktrak.Main",
    "--check-assets",
  ]);
  command("podman", ["run", "--rm", "--entrypoint", "/opt/toktrak/bin/jcmd", imageId, "-h"]);
  command("podman", ["run", "--rm", "--entrypoint", "/opt/toktrak/bin/jfr", imageId, "help"]);

  writeFileSync(environment, verificationEnvironment(), { mode: 0o600 });
  command("podman", ["volume", "create", volume]);
  volumeCreated = true;
  let port = start(imageId);
  await awaitHealthy(port);
  command("podman", ["exec", container, "/bin/sh", "-c", "printf toktrak-container-verify > /data/.container-verify"]);
  command("podman", ["rm", "--force", container]);
  containerCreated = false;
  port = start(imageId);
  await awaitHealthy(port);
  if (command("podman", ["exec", container, "cat", "/data/.container-verify"]) !== "toktrak-container-verify") {
    throw new Error("container volume did not preserve data across restart");
  }

  if (publication) await publish(imageId, displayVersion, publication, Boolean(existingImageId));
} catch (error) {
  failure = error;
} finally {
  for (const [exists, args] of [
    [containerCreated, ["rm", "--force", container]],
    [volumeCreated, ["volume", "rm", "--force", volume]],
    [imageBuilt, ["image", "rm", "--force", image]],
    [registryLoggedIn, ["logout", publication?.host]],
  ]) {
    if (!exists) continue;
    try {
      command("podman", args);
    } catch (error) {
      failure = failure ? new AggregateError([failure, error], "verification and cleanup both failed") : error;
    }
  }
  try {
    rmSync(environmentDirectory, { recursive: true, force: true });
  } catch (error) {
    failure = failure ? new AggregateError([failure, error], "verification and cleanup both failed") : error;
  }
}
if (failure) throw failure;

function commandResult(file, args, options = {}) {
  const result = spawnSync(file, args, {
    encoding: "utf8",
    timeout: options.timeout ?? 1_200_000,
    maxBuffer: 4 * 1024 * 1024,
    input: options.input,
  });
  if (result.error) throw result.error;
  return result;
}

function command(file, args, options = {}) {
  const result = commandResult(file, args, options);
  if (result.status !== 0) throw new Error(`${file} ${args[0]} failed with exit code ${result.status}`);
  return result.stdout;
}

function canonicalImageId(value) {
  if (/^[0-9a-f]{64}$/u.test(value)) return `sha256:${value}`;
  if (/^sha256:[0-9a-f]{64}$/u.test(value)) return value;
  throw new Error("Podman returned an invalid image ID");
}

function inspectImage(imageId, imageVersion, imageRevision, imageDisplayVersion) {
  const images = JSON.parse(command("podman", ["image", "inspect", imageId, "--format", "json"]));
  if (!Array.isArray(images) || images.length !== 1) throw new Error("Podman returned invalid image inspection data");
  const inspected = images[0];
  if (
    inspected.Os !== "linux" ||
    String(inspected.Config?.User) !== "0" ||
    !Object.hasOwn(inspected.Config?.ExposedPorts ?? {}, "8080/tcp") ||
    !Object.hasOwn(inspected.Config?.Volumes ?? {}, "/data") ||
    inspected.Config?.Labels?.["org.opencontainers.image.version"] !== String(imageVersion) ||
    inspected.Config?.Labels?.["org.opencontainers.image.changelog-sha256"] !== changelogSha256 ||
    inspected.Config?.Labels?.["org.opencontainers.image.revision"] !== imageRevision ||
    !inspected.Config?.Env?.includes(`TOKTRAK_VERSION=${imageDisplayVersion}`) ||
    !inspected.Config?.Env?.includes(`TOKTRAK_REVISION=${imageRevision}`)
  ) {
    throw new Error("TokTrak image metadata is invalid");
  }
}

function verificationEnvironment() {
  const secret = randomUUID().replaceAll("-", "") + randomUUID().replaceAll("-", "");
  return `TOKTRAK_BASE_URL=https://toktrak.test\nTOKTRAK_PORT=8080\nTOKTRAK_DATA_DIR=/data\nTOKTRAK_OIDC_DISCOVERY_URL=https://accounts.example/.well-known/openid-configuration\nTOKTRAK_OIDC_CLIENT_ID=container-verify\nTOKTRAK_OIDC_CLIENT_SECRET=container-verify\nTOKTRAK_ALLOWED_DOMAIN=example.com\nTOKTRAK_SESSION_SECRET=${secret}\nTOKTRAK_TOKEN_PEPPER=${secret}\n`;
}

function start(imageId) {
  command("podman", [
    "run",
    "--detach",
    "--name",
    container,
    "--env-file",
    environment,
    "--volume",
    `${volume}:/data`,
    "--publish",
    "127.0.0.1::8080",
    imageId,
  ]);
  containerCreated = true;
  const mapping = command("podman", ["port", container, "8080/tcp"]).trim();
  const port = Number(mapping.slice(mapping.lastIndexOf(":") + 1));
  if (!Number.isInteger(port) || port < 1 || port > 65_535)
    throw new Error("Podman returned an invalid published port");
  return port;
}

async function awaitHealthy(port) {
  const deadline = Date.now() + 60_000;
  while (Date.now() < deadline) {
    try {
      const response = await fetch(`http://127.0.0.1:${port}/health`, { signal: AbortSignal.timeout(2_000) });
      if (response.status === 200 && (await response.text()) === '{"status":"ok"}') return;
    } catch {}
    await new Promise((resolve) => setTimeout(resolve, 250));
  }
  throw new Error("container did not become healthy within 60 seconds");
}

function publicationConfig(tag) {
  const repository = process.env.TOKTRAK_IMAGE_REPOSITORY;
  const username = process.env.TOKTRAK_REGISTRY_USERNAME;
  const password = process.env.TOKTRAK_REGISTRY_PASSWORD;
  const token = process.env.GITHUB_TOKEN;
  const githubRepository = process.env.GITHUB_REPOSITORY;
  if (
    !/^[a-z0-9][a-z0-9.-]*(?::[1-9][0-9]{0,4})?\/[a-z0-9][a-z0-9._/-]*$/u.test(repository ?? "") ||
    repository.includes("//")
  )
    throw new Error("TOKTRAK_IMAGE_REPOSITORY must name a canonical OCI repository");
  if (!username || !password || !token || !/^[a-z0-9-]+\/[a-z0-9-]+$/iu.test(githubRepository ?? ""))
    throw new Error("registry credentials and GitHub release credentials are required");
  const notes = readFileSync("output/release/CHANGELOG.md", "utf8");
  const currentNotes = notes.split(/^## v[0-9]+$/mu)[1]?.trim();
  if (!notes.startsWith(`# Changelog\n\n## ${tag}\n`) || !currentNotes || Buffer.byteLength(notes) > 65_536)
    throw new Error("generated release notes do not match the requested release");
  return { repository, host: repository.split("/")[0], username, password, token, githubRepository, currentNotes };
}

async function publish(imageId, tag, { repository, token, githubRepository, currentNotes }, existing) {
  await publishRelease({
    existing,
    version,
    ensureDraft: (allowCreate) => ensureDraft(tag, currentNotes, token, githubRepository, revision, allowCreate),
    pushVersioned: () => command("podman", ["push", imageId, `docker://${repository}:${tag}`]),
    latestTag: () => latestReleaseVersion(token, githubRepository),
    publishDraft: (record) => publishDraft(record, tag, currentNotes, token, githubRepository),
    pushLatest: () => command("podman", ["push", imageId, `docker://${repository}:latest`]),
    verifyLatest: () => {
      command("podman", ["pull", "--policy", "always", `${repository}:latest`]);
      const latestImageId = canonicalImageId(
        command("podman", ["image", "inspect", "--format", "{{.Id}}", `${repository}:latest`]).trim(),
      );
      if (latestImageId !== imageId) throw new Error("registry latest does not contain the verified versioned image");
    },
    markLatest: (record) => markLatest(record, tag, currentNotes, token, githubRepository),
  });
}

async function latestReleaseVersion(token, githubRepository) {
  const response = await fetch(`https://api.github.com/repos/${githubRepository}/git/matching-refs/tags/v`, {
    headers: { Accept: "application/vnd.github+json", Authorization: `Bearer ${token}` },
    signal: AbortSignal.timeout(15_000),
  });
  if (!response.ok) throw new Error(`GitHub tag lookup failed: ${response.status}`);
  const refs = await response.json();
  if (!Array.isArray(refs) || refs.length > 256) throw new Error("remote release tag list is invalid");
  const versions = refs
    .map(({ ref }) => /^refs\/tags\/v(0|[1-9][0-9]*)$/u.exec(ref ?? ""))
    .filter(Boolean)
    .map((match) => Number(match[1]));
  if (versions.length === 0) throw new Error("no remote release tags found");
  return Math.max(...versions);
}

async function ensureDraft(tag, notes, token, githubRepository, commit, allowCreate) {
  const url = `https://api.github.com/repos/${githubRepository}/releases`;
  const headers = { Accept: "application/vnd.github+json", Authorization: `Bearer ${token}` };
  const existing = await fetch(`${url}/tags/${tag}`, { headers, signal: AbortSignal.timeout(15_000) });
  if (existing.ok) {
    const published = await existing.json();
    requireReleaseNotes(published, tag, notes);
    return { id: published.id, draft: false };
  }
  if (existing.status !== 404) throw new Error(`GitHub Release lookup failed: ${existing.status}`);
  const list = await fetch(`${url}?per_page=100`, { headers, signal: AbortSignal.timeout(15_000) });
  if (!list.ok) throw new Error(`GitHub draft lookup failed: ${list.status}`);
  const releases = await list.json();
  if (!Array.isArray(releases) || releases.length > 100) throw new Error("GitHub release list is invalid");
  const draft = releases.find((candidate) => candidate.tag_name === tag && candidate.draft);
  if (draft) {
    requireReleaseNotes(draft, tag, notes);
    return { id: draft.id, draft: true };
  }
  if (!allowCreate) throw new Error("versioned image exists without frozen GitHub Release notes");
  const created = await fetch(url, {
    method: "POST",
    headers: { ...headers, "Content-Type": "application/json" },
    signal: AbortSignal.timeout(15_000),
    body: JSON.stringify({ tag_name: tag, target_commitish: commit, name: `TokTrak ${tag}`, body: notes, draft: true }),
  });
  if (!created.ok) throw new Error(`GitHub draft creation failed: ${created.status}`);
  const result = await created.json();
  requireReleaseNotes(result, tag, notes);
  if (!result.draft) throw new Error("GitHub created a published release instead of a draft");
  return { id: result.id, draft: true };
}

function requireReleaseNotes(candidate, tag, notes) {
  if (!Number.isSafeInteger(candidate.id) || candidate.tag_name !== tag || candidate.body !== notes)
    throw new Error("GitHub Release differs from requested release");
}

async function publishDraft(record, tag, notes, token, githubRepository) {
  if (!record.draft) return;
  const published = await updateRelease(record, tag, notes, token, githubRepository, {
    draft: false,
    make_latest: "false",
  });
  if (published.draft) throw new Error("GitHub Release remained a draft after publication");
}

async function markLatest(record, tag, notes, token, githubRepository) {
  const published = await updateRelease(record, tag, notes, token, githubRepository, { make_latest: "true" });
  if (published.draft) throw new Error("GitHub latest Release is still a draft");
  const response = await fetch(`https://api.github.com/repos/${githubRepository}/releases/latest`, {
    headers: { Accept: "application/vnd.github+json", Authorization: `Bearer ${token}` },
    signal: AbortSignal.timeout(15_000),
  });
  if (!response.ok || (await response.json()).id !== record.id)
    throw new Error("GitHub latest Release does not match the verified versioned image");
}

async function updateRelease(record, tag, notes, token, githubRepository, changes) {
  const response = await fetch(`https://api.github.com/repos/${githubRepository}/releases/${record.id}`, {
    method: "PATCH",
    headers: {
      Accept: "application/vnd.github+json",
      Authorization: `Bearer ${token}`,
      "Content-Type": "application/json",
    },
    signal: AbortSignal.timeout(15_000),
    body: JSON.stringify(changes),
  });
  if (!response.ok) throw new Error(`GitHub Release update failed: ${response.status}`);
  const result = await response.json();
  requireReleaseNotes(result, tag, notes);
  return result;
}
