import assert from "node:assert/strict";
import test from "node:test";
import { updatePlugins } from "./update-dprint.mjs";

const names = ["markdown", "biome", "exec", "pretty_yaml", "toml", "malva", "markup_fmt", "dockerfile"];
const community = new Set(["pretty_yaml", "malva", "markup_fmt"]);
const hashes = names.map((_, index) => String(index).repeat(64));
const url = (name, version) =>
  `https://plugins.dprint.dev/${community.has(name) ? "g-plane/" : ""}${name}-${community.has(name) ? "v" : ""}${version}.${name === "exec" ? "json" : "wasm"}`;
const config = () => ({
  lineWidth: 80,
  plugins: names.map((name, index) => `${url(name, "1.2.3")}@${hashes[index]}`),
});
const releases = () =>
  Object.fromEntries(
    names.map((name, index) => [
      name,
      { schemaVersion: 1, url: url(name, "1.2.3"), version: "1.2.3", checksum: hashes[index] },
    ]),
  );

test("given_pinnedPlugins_when_updatingToNewReleases_then_preservesConfigAndPairsVersionsWithHashes", () => {
  const original = config();
  const latest = releases();
  latest.markdown = {
    schemaVersion: 1,
    url: url("markdown", "1.3.0"),
    version: "1.3.0",
    checksum: "d".repeat(64),
  };
  const updated = updatePlugins(original, latest);
  assert.equal(updated.lineWidth, 80);
  assert.equal(updated.plugins[0], `${latest.markdown.url}@${latest.markdown.checksum}`);
  assert.deepEqual(updated.plugins.slice(1), original.plugins.slice(1));
  assert.equal(updated.plugins.length, original.plugins.length);
  assert.notEqual(updated, original);
});

test("given_sameVersionWithDifferentHash_when_updating_then_rejectsMutableRelease", () => {
  const latest = releases();
  latest.exec.checksum = "d".repeat(64);
  assert.throws(() => updatePlugins(config(), latest), /exec plugin checksum changed/);
});

test("given_communityRelease_when_updating_then_preservesCanonicalUrlAndChecksum", () => {
  const latest = releases();
  latest.markup_fmt.version = "1.2.4";
  latest.markup_fmt.url = url("markup_fmt", "1.2.4");
  latest.markup_fmt.checksum = "9".repeat(64);
  const updated = updatePlugins(config(), latest);
  assert.equal(updated.plugins[6], `${latest.markup_fmt.url}@${latest.markup_fmt.checksum}`);
});

test("given_olderRelease_when_updating_then_rejectsDowngrade", () => {
  const latest = releases();
  latest.biome.version = "1.1.9";
  latest.biome.url = url("biome", "1.1.9");
  assert.throws(() => updatePlugins(config(), latest), /biome plugin downgrade/);
});

test("given_untrustedRelease_when_updating_then_rejectsWrongOriginOrMissingHash", () => {
  const latest = releases();
  latest.markdown.url = "https://example.com/markdown-1.2.3.wasm";
  assert.throws(() => updatePlugins(config(), latest), /invalid markdown plugin metadata/);
  latest.markdown.url = url("markdown", "1.2.3");
  latest.markdown.checksum = "invalid";
  assert.throws(() => updatePlugins(config(), latest), /invalid markdown plugin metadata/);
});

test("given_unpinnedOrUnexpectedPlugin_when_updating_then_rejectsConfig", () => {
  const current = config();
  current.plugins[0] = url("markdown", "1.2.3");
  assert.throws(() => updatePlugins(current, releases()), /unexpected or unpinned/);
  current.plugins.push(`${url("markdown", "1.2.3")}@${hashes[0]}`);
  assert.throws(() => updatePlugins(current, releases()), /expected exactly 8/);
});
