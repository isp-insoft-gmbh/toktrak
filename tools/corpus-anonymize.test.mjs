import assert from "node:assert/strict";
import { existsSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";
import { anonymize, main } from "./corpus-anonymize.mjs";

const KEY = "a".repeat(64);
const OTHER_KEY = "b".repeat(64);
const SUBJECT = "109876543210987654321";
const TOKEN = "3f2a4c1e-5b6d-4e7f-8a9b-0c1d2e3f4a5b";
const DIGEST = Buffer.alloc(32, 7).toString("base64url");
const PROJECT = "C:\\Users\\alovelace\\Workspace\\analytical-engine";
const ENCODED_PROJECT = "--C--Users-alovelace-Workspace-analytical-engine--";

const user = (displayName = "Ada Lovelace", subject = SUBJECT) =>
  JSON.stringify({
    id: "6c914202-a641-45da-89d2-b1ce10b4ec4b",
    at: "2026-07-14T14:14:39Z",
    type: "identity-user-authenticated",
    schemaVersion: 1,
    actor: subject,
    data: {
      issuer: "https://accounts.google.com",
      subject,
      email: "ada.lovelace@example.com",
      displayName,
      color: "#a8dadc",
    },
  });

const token = () => [
  JSON.stringify({
    id: "fa18feaa-f470-4bea-8e32-a3e156ae82d3",
    at: "2026-07-14T14:15:00Z",
    type: "identity-tracker-token-created",
    schemaVersion: 1,
    actor: SUBJECT,
    data: {
      issuer: "https://accounts.google.com",
      subject: SUBJECT,
      label: "ada-laptop",
      digest: DIGEST,
      tokenId: TOKEN,
    },
  }),
  JSON.stringify({
    id: "57866f12-00c1-4e47-92dd-0efbbca8e27e",
    at: "2026-07-14T14:16:00Z",
    type: "identity-tracker-token-used",
    schemaVersion: 1,
    actor: TOKEN,
    data: { tokenId: TOKEN },
  }),
];

const upload = (modelName = "[pi] openrouter/free", subject = SUBJECT) =>
  `{"id":"49c1884e-af1f-4f05-99ca-26b2f042f6fd","at":"2026-07-14T23:00:00Z","type":"usage-uploaded","schemaVersion":1,"actor":"${subject}",` +
  `"data":{"issuer":"https://accounts.google.com","subject":"${subject}","upload":{"trackerVersion":"12","ccusageVersion":"20.0.17",` +
  `"clientTimeZone":"Europe/Berlin","full":true,"generatedAt":"2026-07-14T23:00:00Z","reports":{` +
  `"daily":{"ok":true,"json":{"daily":[{"period":"2026-07-14","agent":"all","totalCost":0.12345678901234567890123,"totalTokens":1e3,` +
  `"modelBreakdowns":[{"modelName":"${modelName}","cost":0.12345678901234567890123}]}]}},` +
  `"session":{"ok":true,"json":{"session":[{"period":${JSON.stringify(PROJECT)},"agent":"pi","totalCost":1,` +
  `"metadata":{"projectPath":${JSON.stringify(ENCODED_PROJECT)},"lastActivity":"2026-07-14T22:00:00Z"}}]}},` +
  `"blocks":{"ok":false,"error":"ccusage failed in ${PROJECT.replaceAll("\\", "\\\\")}"},` +
  `"sourceReports":{"codex":{"session":{"ok":true,"json":{"sessions":[{"sessionId":"2026/07/14/rollout-1","directory":${JSON.stringify(PROJECT)},` +
  `"sessionFile":"rollout-1.jsonl","lastActivity":"2026-07-14T22:00:00Z","models":{"gpt-5.5":{"isFallback":false}}}]}}}}}}}}`;

const events = (...lines) => lines.flat().join("\n");

test("given_productionShapedEvents_when_anonymizing_then_removesIdentitiesAndKeepsReferencesAndExactNumbers", () => {
  const source = events(user(), token(), upload());
  const result = anonymize(source, KEY);
  const output = result.lines.join("\n");

  for (const original of [
    SUBJECT,
    "ada.lovelace",
    "lovelace",
    "alovelace",
    "accounts.google.com",
    "ada-laptop",
    DIGEST,
    TOKEN,
    "rollout-1",
    "analytical-engine",
  ]) {
    assert.equal(output.toLowerCase().includes(original.toLowerCase()), false, original);
  }
  assert.ok(output.includes("0.12345678901234567890123"));
  assert.ok(output.includes('"totalTokens":1e3'));

  const [authenticated, created, used, uploaded] = result.lines.map((line) => JSON.parse(line));
  assert.equal(authenticated.data.subject, "viewer");
  assert.equal(authenticated.data.issuer, "urn:toktrak:development");
  const [, ownerTag] = /^Synthetic User ([0-9a-f]{12})$/u.exec(authenticated.data.displayName);
  assert.equal(authenticated.data.email, `synthetic-${ownerTag}@example.invalid`);
  assert.equal(authenticated.actor, authenticated.data.subject);
  assert.equal(uploaded.data.subject, authenticated.data.subject);
  assert.equal(used.actor, created.data.tokenId);
  assert.equal(used.data.tokenId, created.data.tokenId);
  assert.equal(Buffer.from(created.data.digest, "base64url").length, 32);
  assert.equal(uploaded.data.upload.reports.blocks.error, "synthetic report failure");
  assert.equal(uploaded.data.upload.reports.daily.json.daily[0].modelBreakdowns[0].modelName, "[pi] openrouter/free");
  assert.match(
    uploaded.data.upload.reports.sourceReports.codex.session.json.sessions[0].sessionFile,
    /^synthetic-session-[0-9a-f]{12}\.jsonl$/u,
  );
  assert.match(uploaded.data.upload.reports.session.json.session[0].period, /^synthetic-session-[0-9a-f]{12}$/u);
  assert.match(
    uploaded.data.upload.reports.session.json.session[0].metadata.projectPath,
    /^synthetic-project-[0-9a-f]{12}$/u,
  );

  assert.deepEqual(anonymize(source, KEY).lines, result.lines);
  assert.notDeepEqual(anonymize(source, OTHER_KEY).lines, result.lines);
});

test("given_severalPeople_when_anonymizing_then_mapsExactlyOneUploaderToDevelopmentViewer", () => {
  const uploaders = ["subject-one", "subject-two", "subject-three"];
  const source = events(
    user("Never Uploads", "subject-idle"),
    uploaders.flatMap((subject) => [user(`Person ${subject}`, subject), upload("gpt-5.5", subject)]),
  );
  const subjects = (lines) => lines.map((line) => JSON.parse(line).data.subject);
  const result = subjects(anonymize(source, KEY).lines);

  assert.notEqual(result[0], "viewer");
  const viewerIndexes = result.flatMap((subject, index) => (subject === "viewer" ? [index] : []));
  assert.equal(viewerIndexes.length, 2);
  assert.equal(viewerIndexes[1], viewerIndexes[0] + 1);
  assert.deepEqual(subjects(anonymize(source, KEY).lines), result);
  assert.equal(
    subjects(anonymize(events(user("Never Uploads", "subject-idle")), KEY).lines)[0].startsWith("synthetic-subject-"),
    true,
  );
});

test("given_missingOrNewKey_when_importing_then_explainsSetupAndCreatesKeyOnlyOnRequest", () => {
  const directory = mkdtempSync(join(tmpdir(), "toktrak-corpus-key-"));
  try {
    const snapshot = join(directory, "events.ndjson");
    const key = join(directory, "team.key");
    const output = join(directory, "anonymized.jsonl");
    writeFileSync(snapshot, events(user(), upload()));
    const run = (...extra) => main([snapshot, "--key", key, "--output", output, ...extra]);

    assert.throws(run, /anonymization key not found[\s\S]*secret manager[\s\S]*--new-key/u);
    assert.equal(existsSync(key), false);

    run("--new-key");
    assert.match(readFileSync(key, "utf8"), /^[0-9a-f]{64}\n$/u);
    assert.ok(existsSync(output));
    assert.throws(() => run("--new-key"), /anonymization key already exists/u);
    run();
  } finally {
    rmSync(directory, { recursive: true, force: true });
  }
});

test("given_unknownStringField_when_anonymizing_then_failsClosedWithFieldPath", () => {
  const event = JSON.stringify({
    id: "6c914202-a641-45da-89d2-b1ce10b4ec4b",
    type: "usage-uploaded",
    data: { hostname: "ada-workstation" },
  });
  assert.throws(() => anonymize(event, KEY), /usage-uploaded\.data\.hostname is an unsupported string field/u);
});

test("given_modelNameShapes_when_anonymizing_then_acceptsHarnessPrefixOnlyInCanonicalForm", () => {
  for (const modelName of ["[pi] openrouter/free", "[pi] Qwen/Qwen3.6-35B-A3B-FP8", "claude-opus-4-8", "gpt-5.5"]) {
    assert.doesNotThrow(() => anonymize(events(user(), upload(modelName)), KEY), modelName);
  }
  for (const modelName of ["[Pi] x", "[pi]x", "[", "[pi] ", "model name with spaces"]) {
    assert.throws(
      () => anonymize(events(user(), upload(modelName)), KEY),
      /modelName has an unexpected shape/u,
      modelName,
    );
  }
});

test("given_identifyingWordInKeptModelName_when_anonymizing_then_refusesOutput", () => {
  assert.throws(() => anonymize(events(user(), upload("lovelace-local")), KEY), /identifying word/u);
});

test("given_developmentCorpus_when_anonymizing_then_preservesEveryEventAndNumber", () => {
  const source = readFileSync(new URL("../tests/corpus/dev.jsonl", import.meta.url), "utf8");
  const result = anonymize(source, KEY);
  assert.equal(result.lines.length, source.trim().split("\n").length);
  assert.ok(result.numbers > 0);
});

test("given_snapshotInsideRepository_when_running_then_refusesToReadIt", () => {
  assert.throws(() => main(["tests/corpus/dev.jsonl"]), /production snapshots must stay outside the repository/u);
});
