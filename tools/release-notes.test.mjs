import assert from "node:assert/strict";
import { mkdtempSync, rmSync, writeFileSync } from "node:fs";
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

const fixture = () => {
  const root = mkdtempSync(join(tmpdir(), "toktrak-release-notes-"));
  writeFileSync(join(root, "CHANGELOG.md"), frozen);
  return root;
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
