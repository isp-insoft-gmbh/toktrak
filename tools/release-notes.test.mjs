import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { copyFileSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { tmpdir } from "node:os";
import test from "node:test";
import { generate } from "./release-notes.mjs";

const SHA = "a".repeat(40);
const frozen = `# Changelog

## v2

- Two.

## v1

- One.

## v0

- Zero.
`;

const fixture = (archive = false) => {
  const root = mkdtempSync(join(tmpdir(), "toktrak-release-notes-"));
  try {
    writeFileSync(join(root, "CHANGELOG.md"), frozen);
    if (!archive) {
      const result = spawnSync("git", ["init", "--quiet", root], { encoding: "utf8", timeout: 30_000 });
      assert.equal(result.status, 0, result.stderr || result.error?.message);
    }
    return root;
  } catch (error) {
    rmSync(root, { recursive: true, force: true });
    throw error;
  }
};

const archiveCli = (root, arguments_) => {
  mkdirSync(join(root, "tools"), { recursive: true });
  copyFileSync(new URL("./release-notes.mjs", import.meta.url), join(root, "tools/release-notes.mjs"));
  return spawnSync(process.execPath, [join(root, "tools/release-notes.mjs"), ...arguments_], {
    cwd: tmpdir(),
    encoding: "utf8",
    timeout: 30_000,
    maxBuffer: 1024 * 1024,
  });
};

const git =
  (values = {}) =>
  (arguments_) => {
    const key = arguments_.join(" ");
    if (key === "tag --list v[0-9]*") return values.tags ?? "v3\n";
    if (key === "rev-parse --is-shallow-repository") return values.shallow ?? "false\n";
    if (key === "config --get remote.origin.url") return "https://github.com/acme/toktrak.git\n";
    if (key === "cat-file -t v3") return values.tagType ?? "tag\n";
    if (key === "rev-parse --verify v3^{}") return `${SHA}\n`;
    if (key.startsWith("for-each-ref --format=%(contents:subject)")) return values.subject ?? "TokTrak v3\n";
    if (key.startsWith("for-each-ref --format=%(contents:body)"))
      return values.highlight ?? "Launch [unsafe] `highlight`\n";
    if (key.startsWith("log ")) return values.log ?? `${SHA}\0Direct [unsafe] \`commit\`\n`;
    throw new Error(`unexpected git command: ${key}`);
  };

test("given_sourceArchive_when_runningDevelopmentCli_then_preservesFrozenHistoryWithoutInventingRelease", () => {
  const root = fixture(true);
  const source = readFileSync(new URL("../CHANGELOG.md", import.meta.url), "utf8");
  try {
    writeFileSync(join(root, "CHANGELOG.md"), source);
    const output = join(root, "output/CHANGELOG.md");
    const result = archiveCli(root, ["dev", output]);
    assert.equal(result.status, 0, result.stderr || result.error?.message);
    const changelog = readFileSync(output, "utf8");
    assert.deepEqual(
      [...changelog.matchAll(/^## (.*)$/gmu)].map((match) => match[1]),
      ["v2", "v1", "v0"],
    );
    const notice = changelog.match(/^- .*source archive.*history is unavailable.*\n/gmu);
    assert.equal(notice?.length, 1);
    assert.equal(changelog.replace(`${notice[0]}\n`, ""), source);
    assert.equal(readFileSync(join(root, "CHANGELOG.md"), "utf8"), source);
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});

test("given_enclosingRepository_when_runningArchiveCli_then_neverConsumesParentHistoryOrReleases", () => {
  const parent = fixture();
  const root = join(parent, "archive");
  mkdirSync(root);
  writeFileSync(join(root, "CHANGELOG.md"), frozen);
  const output = join(root, "CHANGELOG.generated.md");
  try {
    const development = archiveCli(root, ["dev", output]);
    assert.equal(development.status, 0, development.stderr || development.error?.message);
    assert.match(readFileSync(output, "utf8"), /source archive.*history is unavailable/u);
    writeFileSync(output, "Keep existing output.\n");
    const release = archiveCli(root, ["release", "v3", output]);
    assert.equal(release.status, 1, release.stderr);
    assert.match(release.stderr, /git tag failed/u);
    assert.equal(readFileSync(output, "utf8"), "Keep existing output.\n");
  } finally {
    rmSync(parent, { recursive: true, force: true });
  }
});

test("given_corruptLocalMetadataInsideRepository_when_generating_then_failsInsteadOfUsingParentOrArchive", async () => {
  const parent = fixture();
  const root = join(parent, "archive");
  mkdirSync(root);
  writeFileSync(join(root, "CHANGELOG.md"), frozen);
  try {
    for (const metadata of ["directory", "gitfile"]) {
      if (metadata === "directory") mkdirSync(join(root, ".git"));
      else writeFileSync(join(root, ".git"), "gitdir: missing-metadata\n");
      await assert.rejects(() => generate("dev", undefined, root), /git tag failed/u);
      rmSync(join(root, ".git"), { recursive: true, force: true });
    }
  } finally {
    rmSync(parent, { recursive: true, force: true });
  }
});

test("given_sourceArchive_when_baselineIsInvalidOrOutputTooLarge_then_rejects", async () => {
  const root = fixture(true);
  try {
    const cases = [
      [frozen.replace("# Changelog", "# History"), /frozen CHANGELOG.md is invalid/u],
      [frozen.replace("## v1", "## v3"), /must contain exactly v2, v1, and v0/u],
      [frozen + "é".repeat(32_768), /frozen CHANGELOG.md is invalid/u],
      [frozen + "- " + "a".repeat(65_536 - Buffer.byteLength(frozen) - 3) + "\n", /generated changelog exceeds/u],
    ];
    for (const [source, error] of cases) {
      writeFileSync(join(root, "CHANGELOG.md"), source);
      await assert.rejects(() => generate("dev", undefined, root), error);
    }
    rmSync(join(root, "CHANGELOG.md"));
    await assert.rejects(() => generate("dev", undefined, root), /ENOENT/u);
    await assert.rejects(() => generate("dev", "v3", root), /usage:/u);
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});

test("given_repository_when_gitFailsOrTagsAreMissing_then_doesNotBecomeArchive", async () => {
  const root = fixture();
  try {
    await assert.rejects(() => generate("dev", undefined, root), /development changelog requires release tags/u);
    const failure = new Error("git executable failed");
    await assert.rejects(
      () =>
        generate("dev", undefined, root, {
          execute: () => {
            throw failure;
          },
        }),
      (error) => error === failure,
    );
    await assert.rejects(
      () => generate("dev", undefined, root, { execute: git({ tags: "v4\n" }) }),
      /missing release tag v3/u,
    );
    await assert.rejects(
      () =>
        generate("dev", undefined, root, {
          execute: (arguments_) => {
            if (arguments_[0] === "log") throw failure;
            return git()(arguments_);
          },
        }),
      (error) => error === failure,
    );
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});

test("given_shallowRepository_when_tagsAreAbsentOrLogFails_then_preservesExistingFallbackBoundaries", async () => {
  const root = fixture();
  try {
    const changelog = await generate("dev", undefined, root, { execute: git({ tags: "", shallow: "true\n" }) });
    assert.match(changelog, /## v3\n\n- History is shallow; notes include only locally available commits\./u);
    const failure = new Error("unreadable shallow history");
    await assert.rejects(
      () =>
        generate("dev", undefined, root, {
          execute: (arguments_) => {
            if (arguments_[0] === "log") throw failure;
            return git({ shallow: "true\n" })(arguments_);
          },
        }),
      (error) => error === failure,
    );
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});

test("given_developmentHistory_when_generating_then_emitsStrictConsecutiveChangelog", async () => {
  const root = fixture();
  try {
    const changelog = await generate("dev", undefined, root, { execute: git() });
    assert.equal(
      changelog,
      `# Changelog

## v4

- Direct unsafe commit

## v3

- Direct unsafe commit

${frozen.replace(/^# Changelog\n\n/u, "")}`,
    );
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});

test("given_annotatedRelease_when_generating_then_usesMergedPrsAndDirectCommits", async () => {
  const root = fixture();
  const previous = process.env.GITHUB_TOKEN;
  process.env.GITHUB_TOKEN = "token";
  const requests = [];
  try {
    const changelog = await generate("release", "v3", root, {
      execute: git({ log: `${SHA}\0Merged [unsafe] \`commit\`\n${"b".repeat(40)}\0Direct [unsafe] \`commit\`\n` }),
      request: async (url) => {
        requests.push(url);
        return {
          ok: !url.endsWith("/releases/tags/v3"),
          status: url.endsWith("/releases/tags/v3") ? 404 : 200,
          json: async () =>
            url.includes(SHA)
              ? [
                  {
                    number: 7,
                    title: "Ship [unsafe] `feature`",
                    merged_at: "now",
                    base: { ref: "trunk", repo: { full_name: "acme/toktrak" } },
                  },
                ]
              : [],
        };
      },
    });
    assert.match(
      changelog,
      /- Launch unsafe highlight\n- Ship unsafe feature \[#7\]\(https:\/\/github\.com\/acme\/toktrak\/pull\/7\)\n- Direct unsafe commit/u,
    );
    assert.equal(requests.length, 4);
  } finally {
    if (previous === undefined) delete process.env.GITHUB_TOKEN;
    else process.env.GITHUB_TOKEN = previous;
    rmSync(root, { recursive: true, force: true });
  }
});

test("given_publishedRelease_when_retrying_then_reusesFrozenNotesWithoutReadingPrTitles", async () => {
  const root = fixture();
  const previous = process.env.GITHUB_TOKEN;
  process.env.GITHUB_TOKEN = "token";
  const requests = [];
  try {
    const changelog = await generate("release", "v3", root, {
      execute: git({ highlight: "Changed after publication.\n" }),
      request: async (url) => {
        requests.push(url);
        return { ok: true, status: 200, json: async () => ({ body: "- Frozen user-facing note.\n" }) };
      },
    });
    assert.match(changelog, /## v3\n\n- Frozen user-facing note\.\n/u);
    assert.doesNotMatch(changelog, /Changed after publication/u);
    assert.equal(requests.length, 1);
  } finally {
    if (previous === undefined) delete process.env.GITHUB_TOKEN;
    else process.env.GITHUB_TOKEN = previous;
    rmSync(root, { recursive: true, force: true });
  }
});

test("given_draftRelease_when_retrying_then_reusesFrozenNotesBeforeImagePublication", async () => {
  const root = fixture();
  const previous = process.env.GITHUB_TOKEN;
  process.env.GITHUB_TOKEN = "token";
  try {
    const changelog = await generate("release", "v3", root, {
      execute: git({ highlight: "Changed after draft.\n" }),
      request: async (url) =>
        url.endsWith("/releases/tags/v3")
          ? { ok: false, status: 404 }
          : {
              ok: true,
              status: 200,
              json: async () => [{ tag_name: "v3", draft: true, body: "- Frozen draft note.\n" }],
            },
    });
    assert.match(changelog, /## v3\n\n- Frozen draft note\.\n/u);
    assert.doesNotMatch(changelog, /Changed after draft/u);
  } finally {
    if (previous === undefined) delete process.env.GITHUB_TOKEN;
    else process.env.GITHUB_TOKEN = previous;
    rmSync(root, { recursive: true, force: true });
  }
});

test("given_lightweightReleaseTag_when_generating_then_rejectsManualReleaseIntent", async () => {
  const root = fixture();
  const previous = process.env.GITHUB_TOKEN;
  process.env.GITHUB_TOKEN = "token";
  try {
    await assert.rejects(
      () => generate("release", "v3", root, { execute: git({ tagType: "commit\n" }) }),
      /must be an annotated tag/u,
    );
  } finally {
    if (previous === undefined) delete process.env.GITHUB_TOKEN;
    else process.env.GITHUB_TOKEN = previous;
    rmSync(root, { recursive: true, force: true });
  }
});

test("given_shallowDevelopmentClone_when_baseIsUnavailable_then_marksFallbackHonestly", async () => {
  const root = fixture();
  let logs = 0;
  try {
    const changelog = await generate("dev", undefined, root, {
      execute: (arguments_, directory) => {
        if (arguments_[0] === "log" && logs++ === 0) throw new Error("missing base");
        return git({ shallow: "true\n" })(arguments_, directory);
      },
    });
    assert.match(changelog, /- History is shallow; notes include only locally available commits\./u);
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});
