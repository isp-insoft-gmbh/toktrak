import assert from "node:assert/strict";
import test from "node:test";
import { compare, dailyRows, decimal, expected, format, rendered, toolCosts } from "./visualizations-oracle.mjs";

const row = (period, totalCost, agents, metadataAgent) => ({
  owner: "issuer\u0000subject",
  period,
  agent: "all",
  totalCost,
  ...(agents ? { agents: agents.map(([agent, cost]) => ({ agent, totalCost: cost })) } : {}),
  ...(metadataAgent ? { metadata: { agents: [metadataAgent] } } : {}),
});

const costs = (map) => Object.fromEntries([...map].map(([name, value]) => [name, format(value)]));

test("given_decimalSourceText_when_parsing_then_keepsExactValue", () => {
  assert.equal(format(decimal("0.12345678901234567890123")), "0.12345678901234567890123");
  assert.equal(format(decimal("1e3")), "1000");
  assert.equal(format(decimal("2.5E-2")), "0.025");
  assert.equal(format(decimal("0.1") + decimal("0.2")), "0.3");
});

test("given_toolCostsAroundTheSlack_when_attributing_then_partsAlwaysSumToRow", () => {
  assert.deepEqual(
    costs(
      toolCosts(
        row("2026-07-01", "10", [
          ["a", "4"],
          ["b", "6"],
        ]),
      ),
    ),
    { a: "4", b: "6" },
  );
  assert.deepEqual(costs(toolCosts(row("2026-07-01", "1", [["a", "1.000001"]]))), { a: "1" });
  assert.deepEqual(costs(toolCosts(row("2026-07-01", "1", [["a", "1.0000011"]], "c"))), { c: "1" });
  assert.deepEqual(costs(toolCosts(row("2026-07-01", "1", [["a", "0.999999"]]))), { a: "1" });
  assert.deepEqual(costs(toolCosts(row("2026-07-01", "1", [["a", "0.9999989"]]))), {
    a: "0.9999989",
    "Not reported": "0.0000011",
  });
  assert.deepEqual(costs(toolCosts(row("2026-07-01", "0", [], "c"))), {});
  assert.deepEqual(costs(toolCosts(row("2026-07-01", "2", [["x".repeat(65), "1"]], "y"))), { y: "2" });
});

test("given_rowsAcrossRanges_when_computingExpectedFigures_then_bucketsFoldsAndCounts", () => {
  const rows = [
    row("2026-06-30", "1", [["alpha", "1"]]),
    row("2026-07-01", "2", [["beta", "2"]]),
    ...["t1", "t2", "t3", "t4", "t5", "t6", "t7"].map((name, index) => row("2026-07-02", "1", [[name, "1"]])),
  ];
  const july = expected(rows, "2026-07-01", "2026-07-31", "2026-07-15");
  assert.equal(format(july.total), "9");
  assert.equal(july.elapsed, 15);
  assert.equal(july.daysWithUsage, 2);
  assert.equal(july.people, 1);
  assert.deepEqual(
    july.legend.map(([name, value]) => `${name}=${format(value)}`),
    ["beta=2", "t1=1", "t2=1", "t3=1", "t4=1", "Other tools=3"],
  );
  const year = expected(rows, "2026-01-01", "2026-12-31", "2026-07-15");
  assert.deepEqual(
    [...year.buckets.entries()].map(([start, value]) => `${start}=${format(value)}`),
    ["2026-06-29=10"],
  );
});

test("given_renderedPage_when_comparing_then_detectsEveryPerturbedFigure", () => {
  const html = `
    <dl class="chart-kpis">
      <div><dt>Estimated cost</dt><dd data-usd="3">$3.00</dd></div>
      <div><dt>Days with usage</dt><dd data-usd="">2 of 15</dd></div>
      <div><dt>People with usage</dt><dd data-usd="">1</dd></div>
    </dl>
    <li data-usd="2"><span class="swatch series-1" aria-hidden="true"></span>beta <strong>$2.00</strong></li>
    <li data-usd="1"><span class="swatch series-2" aria-hidden="true"></span>alpha <strong>$1.00</strong></li>
    <tr data-usd="1"><td>1 Jul 2026</td><td>$1.00</td></tr>
    <tr data-usd="2"><td>2 Jul 2026</td><td>$2.00</td></tr>`;
  const rows = [row("2026-07-01", "1", [["alpha", "1"]]), row("2026-07-02", "2", [["beta", "2"]])];
  const want = expected(rows, "2026-07-01", "2026-07-31", "2026-07-15");
  assert.deepEqual(compare(want, rendered(html)), []);
  for (const [from, to] of [
    ['data-usd="3"', 'data-usd="3.01"'],
    ["2 of 15", "3 of 15"],
    ['<dd data-usd="">1</dd>', '<dd data-usd="">2</dd>'],
    ["alpha <strong>", "gamma <strong>"],
    ['<tr data-usd="2">', '<tr data-usd="2.0000001">'],
  ]) {
    assert.notDeepEqual(compare(want, rendered(html.replace(from, to))), [], to);
  }
});

test("given_repeatedUploads_when_readingEvents_then_keepsLatestRowPerOwnerPeriodAndAgent", () => {
  const upload = (cost) => ({
    type: "usage-uploaded",
    data: {
      issuer: "i",
      subject: "s",
      upload: {
        reports: { daily: { ok: true, json: { daily: [{ period: "2026-07-01", agent: "all", totalCost: cost }] } } },
      },
    },
  });
  const rows = dailyRows([
    upload("1"),
    upload("2"),
    { type: "usage-uploaded", data: { upload: { reports: { daily: { ok: false } } } } },
  ]);
  assert.equal(rows.length, 1);
  assert.equal(rows[0].totalCost, "2");
});
