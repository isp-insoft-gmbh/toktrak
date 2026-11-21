import assert from "node:assert/strict";
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";
import { shapePaths } from "./corpus-anonymize.mjs";
import { main, selectShapes, synthesize } from "./corpus-shapes.mjs";

const SUBJECT = "synthetic-subject-a1b2c3d4e5f6";
const TOKEN = "11111111-2222-4333-8444-555555555555";

const upload = (rows) =>
  JSON.stringify({
    id: "6c914202-a641-45da-89d2-b1ce10b4ec4b",
    at: "2026-11-19T23:00:00Z",
    type: "usage-uploaded",
    schemaVersion: 1,
    actor: SUBJECT,
    data: {
      issuer: "urn:toktrak:development",
      subject: SUBJECT,
      upload: {
        trackerVersion: "12",
        ccusageVersion: "20.0.17",
        clientTimeZone: "Europe/Berlin",
        full: true,
        generatedAt: "2026-11-19T23:00:00Z",
        reports: { daily: { ok: true, json: { daily: rows } } },
      },
    },
  });

const row = (period, extra = {}) => ({
  period,
  agent: "all",
  inputTokens: 1000,
  outputTokens: 0,
  cacheCreationTokens: 0,
  cacheReadTokens: 0,
  totalTokens: 1000,
  totalCost: 0.5,
  ...extra,
});

const tokenUsed = () =>
  JSON.stringify({
    id: "fa18feaa-f470-4bea-8e32-a3e156ae82d3",
    at: "2026-11-19T22:00:00Z",
    type: "identity-tracker-token-used",
    schemaVersion: 1,
    actor: TOKEN,
    data: { tokenId: TOKEN },
  });

const tokenCreated = () =>
  JSON.stringify({
    id: "57866f12-00c1-4e47-92dd-0efbbca8e27e",
    at: "2026-11-19T21:00:00Z",
    type: "identity-tracker-token-created",
    schemaVersion: 1,
    actor: SUBJECT,
    data: {
      issuer: "urn:toktrak:development",
      subject: SUBJECT,
      label: "Synthetic tracker 0a1b2c3d4e5f",
      digest: Buffer.alloc(32, 1).toString("base64url"),
      tokenId: "99999999-2222-4333-8444-555555555555",
    },
  });

const known = shapePaths(upload([row("2026-07-01")]));

test("given_knownShapesOnly_when_selecting_then_addsNothing", () => {
  assert.deepEqual(selectShapes([upload([row("2026-07-02"), row("2026-07-03")])], known), []);
});

test("given_newShapeInOneOfManyRows_when_selecting_then_keepsOnlyTheRowCarryingIt", () => {
  const rows = [
    row("2026-11-01"),
    row("2026-11-02", { projectPath: "synthetic-project-a1b2c3d4e5f6" }),
    row("2026-11-03"),
  ];
  const [selected, ...rest] = selectShapes([upload(rows)], known);

  assert.equal(rest.length, 0);
  const keptRows = selected.data.upload.reports.daily.json.daily;
  assert.equal(keptRows.length, 1);
  assert.equal(keptRows[0].period, "2026-11-02");
});

test("given_selectedEvents_when_synthesizing_then_replacesValuesKeepsFormatsAndRepairsTokenOrder", () => {
  const rows = [
    row("2026-11-19", {
      totalCost: JSON.rawJSON("0.12345678901234567890123"),
      cacheReadTokens: 4294967296,
      outputTokens: 0,
      projectPath: "synthetic-project-a1b2c3d4e5f6",
    }),
  ];
  const selected = selectShapes([tokenCreated(), tokenUsed(), upload(rows)], known);
  const lines = synthesize(selected, 7);
  const events = lines.map((line) => JSON.parse(line));
  const text = lines.join("\n");

  assert.deepEqual(
    events.map((event) => event.type),
    [
      "identity-user-authenticated",
      "identity-tracker-token-created",
      "identity-tracker-token-created",
      "identity-tracker-token-used",
      "usage-uploaded",
    ],
  );
  assert.equal(events[0].data.subject, "synthetic-subject-7");
  assert.ok(events.every((event) => event.schemaVersion === 1));
  assert.ok(!/a1b2c3d4e5f6|0a1b2c3d4e5f|11111111-2222|99999999-2222/u.test(text), text);
  assert.equal(events[3].data.tokenId, events[2].data.tokenId);
  assert.notEqual(events[1].data.digest, events[2].data.digest);

  const synthetic = events[4].data.upload.reports.daily.json.daily[0];
  assert.match(synthetic.projectPath, /^synthetic-project-shape-\d+$/u);
  assert.match(lines[4], /"totalCost":0\.\d{23}[,}]/u);
  assert.doesNotMatch(lines[4], /0\.12345678901234567890123/u);
  assert.match(lines[4], /"cacheReadTokens":[1-9]\d?[,}]/u);
  assert.match(lines[4], /"outputTokens":0[,}]/u);

  const period = new Date(`${synthetic.period}T00:00:00Z`);
  assert.ok(period <= new Date("2026-01-31T00:00:00Z"));
  assert.ok(events.every((event) => new Date(event.at) <= new Date("2026-02-01T00:00:00Z")));
  const [, firstCreated, , used, uploaded] = events;
  assert.ok(firstCreated.at < used.at && used.at < uploaded.at, text);
  assert.equal(uploaded.data.upload.generatedAt, uploaded.at);
  assert.match(uploaded.at, /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$/u);
  for (const original of ["21:00:00", "22:00:00", "23:00:00", "2026-11-19"])
    assert.ok(!text.includes(original), original);
});

test("given_sameFieldWithAnotherJsonType_when_selecting_then_treatsItAsMissingShape", () => {
  const [selected] = selectShapes([upload([row("2026-11-02", { totalCost: null })])], known);
  assert.equal(selected.data.upload.reports.daily.json.daily[0].totalCost, null);
});

test("given_corpusFile_when_running_then_appendsOnceVerifiesCoverageAndRefusesUnsafeInputs", () => {
  const directory = mkdtempSync(join(tmpdir(), "toktrak-corpus-shapes-"));
  try {
    const corpus = join(directory, "dev.jsonl");
    const input = join(directory, "pseudonymized.jsonl");
    writeFileSync(corpus, `${upload([row("2026-07-01")])}\n`);
    writeFileSync(
      input,
      [
        tokenCreated(),
        tokenUsed(),
        upload([row("2026-11-02", { projectPath: "synthetic-project-a1b2c3d4e5f6" })]),
      ].join("\n"),
    );

    main([input], corpus);
    const appended = readFileSync(corpus, "utf8");
    assert.equal(appended.trim().split("\n").length, 6);
    assert.equal(selectShapes(readFileSync(input, "utf8").split("\n"), shapePaths(appended)).length, 0);
    main([input], corpus);
    assert.equal(readFileSync(corpus, "utf8"), appended);

    writeFileSync(corpus, upload([row("2026-07-01")]));
    assert.throws(() => main([input], corpus), /must end with a newline/u);
    assert.throws(
      () => main(["tests/corpus/dev.jsonl"], corpus),
      /read pseudonymized input from output\/ or outside the repository/u,
    );
  } finally {
    rmSync(directory, { recursive: true, force: true });
  }
});
