#!/usr/bin/env node

import { createHash } from "node:crypto";
import { readFile, writeFile } from "node:fs/promises";
import { resolve } from "node:path";
import { fileURLToPath } from "node:url";

const CONFIG = new URL("../dprint.json", import.meta.url);
const PLUGINS = [
  { name: "markdown", owner: "dprint", extension: "wasm" },
  { name: "biome", owner: "dprint", extension: "wasm" },
  { name: "exec", owner: "dprint", extension: "json" },
  { name: "pretty_yaml", owner: "g-plane", extension: "wasm" },
  { name: "toml", owner: "dprint", extension: "wasm" },
  { name: "malva", owner: "g-plane", extension: "wasm" },
  { name: "markup_fmt", owner: "g-plane", extension: "wasm" },
  { name: "dockerfile", owner: "dprint", extension: "wasm" },
];
const HASH = /^[0-9a-f]{64}$/;
const VERSION = /^\d+\.\d+\.\d+$/;

const pluginUrl = (plugin, version) =>
  `https://plugins.dprint.dev/${plugin.owner === "dprint" ? "" : `${plugin.owner}/`}${plugin.name}-${plugin.owner === "dprint" ? "" : "v"}${version}.${plugin.extension}`;

const versionParts = (value) => {
  if (!VERSION.test(value)) throw new Error(`invalid dprint plugin version: ${value}`);
  const parts = value.split(".").map(Number);
  if (!parts.every(Number.isSafeInteger)) throw new Error(`unsafe dprint plugin version: ${value}`);
  return parts;
};

export function updatePlugins(configuration, releases) {
  if (!Array.isArray(configuration.plugins) || configuration.plugins.length !== PLUGINS.length) {
    throw new Error(`expected exactly ${PLUGINS.length} dprint plugins`);
  }
  const plugins = PLUGINS.map((plugin, index) => {
    const { name } = plugin;
    const current = configuration.plugins[index];
    const match = /(\d+\.\d+\.\d+)\.(?:wasm|json)@([0-9a-f]{64})$/.exec(current);
    if (!match || current !== `${pluginUrl(plugin, match[1])}@${match[2]}`) {
      throw new Error(`unexpected or unpinned dprint plugin: ${current}`);
    }
    const [, currentVersion, currentChecksum] = match;
    const release = releases[name];
    if (
      release?.schemaVersion !== 1 ||
      typeof release.version !== "string" ||
      release.url !== pluginUrl(plugin, release.version) ||
      !HASH.test(release.checksum)
    ) {
      throw new Error(`invalid ${name} plugin metadata`);
    }
    const oldParts = versionParts(currentVersion);
    const newParts = versionParts(release.version);
    for (let part = 0; part < oldParts.length; part++) {
      if (newParts[part] < oldParts[part]) throw new Error(`${name} plugin downgrade`);
      if (newParts[part] > oldParts[part]) break;
      if (part === oldParts.length - 1 && release.checksum !== currentChecksum) {
        throw new Error(`${name} plugin checksum changed without version bump`);
      }
    }
    return `${release.url}@${release.checksum}`;
  });
  return { ...configuration, plugins };
}

async function download(url, bytesMax, hash) {
  const response = await fetch(url, { signal: AbortSignal.timeout(120_000) });
  if (!response.ok || !response.body) throw new Error(`dprint plugin fetch failed: ${url} (${response.status})`);
  let bytes = 0;
  const chunks = [];
  for await (const chunk of response.body) {
    bytes += chunk.length;
    if (bytes > bytesMax) throw new Error(`dprint plugin response too large: ${url}`);
    if (hash) hash.update(chunk);
    else chunks.push(chunk);
  }
  return hash ? hash.digest("hex") : Buffer.concat(chunks).toString("utf8");
}

async function main() {
  if (process.argv.length > 3 || (process.argv[2] && process.argv[2] !== "--dry-run")) {
    throw new Error("usage: node tools/update-dprint.mjs [--dry-run]");
  }
  const source = await readFile(CONFIG, "utf8");
  const configuration = JSON.parse(source);
  const releases = {};
  for (const plugin of PLUGINS) {
    const { name } = plugin;
    const url = `https://plugins.dprint.dev/${plugin.owner}/${name}/latest.json`;
    const release = JSON.parse(await download(url, 16 * 1024));
    // Validate metadata before following any URL it supplies.
    if (
      release?.schemaVersion !== 1 ||
      typeof release.version !== "string" ||
      !VERSION.test(release.version) ||
      release.url !== pluginUrl(plugin, release.version) ||
      !HASH.test(release.checksum)
    ) {
      throw new Error(`invalid ${name} plugin metadata`);
    }
    const digest = await download(release.url, 64 * 1024 * 1024, createHash("sha256"));
    if (digest !== release.checksum) throw new Error(`${name} plugin checksum mismatch`);
    releases[name] = release;
  }
  const updated = updatePlugins(configuration, releases);
  const changed = updated.plugins.some((value, index) => value !== configuration.plugins[index]);
  if (!changed) {
    console.log("dprint plugins already up to date");
  } else if (process.argv[2] === "--dry-run") {
    console.log(`Would update: ${updated.plugins.join(", ")}`);
  } else {
    await writeFile(CONFIG, `${JSON.stringify(updated, null, 2)}\n`);
    console.log(`Updated: ${updated.plugins.join(", ")}`);
  }
}

if (process.argv[1] && fileURLToPath(import.meta.url) === resolve(process.argv[1])) {
  await main();
}
