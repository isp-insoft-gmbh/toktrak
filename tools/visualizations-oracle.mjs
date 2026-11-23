#!/usr/bin/env node

// Independent oracle for the visualizations page. It recomputes every figure from raw
// `events.ndjson` with exact decimal arithmetic, without sharing code with the server, and compares
// the result with the exact `data-usd` values a running development server renders.

import { readFileSync, statSync } from "node:fs";
import { resolve } from "node:path";
import { fileURLToPath } from "node:url";

const SCALE = 40n;
const ONE = 10n ** SCALE;
const SLACK = 10n ** (SCALE - 6n);
const RANGE_DAYS_MAX = 3660;
const SERIES_MAX = 6;
const AGENTS_PER_ROW_MAX = 64;
const AGENT_NAME_MAX = 64;
const INPUT_BYTES_MAX = 256 * 1024 * 1024;
const DAY_MILLIS = 86_400_000;
const NOT_REPORTED = "Not reported";
const OTHER_TOOLS = "Other tools";

const fail = (message) => {
  throw new Error(message);
};

/// Parses a JSON number's source text into a fixed-point BigInt with SCALE fractional digits.
export const decimal = (text) => {
  const match = /^(-?)(\d+)(?:\.(\d+))?(?:[eE]([+-]?\d+))?$/u.exec(String(text));
  if (!match) fail(`not a number: ${text}`);
  const [, sign, integer, fraction = "", exponent = "0"] = match;
  const scale = BigInt(fraction.length) - BigInt(exponent);
  let value = BigInt(integer + fraction);
  value = scale <= SCALE ? value * 10n ** (SCALE - scale) : value / 10n ** (scale - SCALE);
  return sign ? -value : value;
};

export const format = (value) => {
  const negative = value < 0n;
  const digits = (negative ? -value : value).toString().padStart(Number(SCALE) + 1, "0");
  const fraction = digits.slice(-Number(SCALE)).replace(/0+$/u, "");
  return `${negative ? "-" : ""}${digits.slice(0, -Number(SCALE))}${fraction ? `.${fraction}` : ""}`;
};

const parseEvents = (text) =>
  text
    .split("\n")
    .filter((line) => line.trim())
    .map((line) => JSON.parse(line, (key, value, context) => (typeof value === "number" ? context.source : value)));

/// Daily row per owner, period, and row agent from the newest upload, like the server: a row
/// replaces an earlier one unless its upload was generated before it.
export const dailyRows = (events) => {
  const rows = new Map();
  for (const event of events) {
    if (event.type !== "usage-uploaded") continue;
    const report = event.data?.upload?.reports?.daily;
    if (!report?.ok) continue;
    const owner = `${event.data.issuer}\u0000${event.data.subject}`;
    const generatedAt = Date.parse(event.data.upload.generatedAt);
    if (Number.isNaN(generatedAt)) fail(`invalid generatedAt in event ${event.id}`);
    for (const row of report.json.daily) {
      const key = `${owner}\u0000${row.period}\u0000${row.agent}`;
      const existing = rows.get(key);
      if (!existing || generatedAt >= existing.generatedAt) rows.set(key, { generatedAt, row: { owner, ...row } });
    }
  }
  return [...rows.values()].map((entry) => entry.row);
};

/// Splits one row's cost across tools; the parts always sum exactly to the row cost.
export const toolCosts = (row) => {
  const cost = decimal(row.totalCost);
  const reported = new Map();
  for (const agent of (Array.isArray(row.agents) ? row.agents : []).slice(0, AGENTS_PER_ROW_MAX)) {
    if (!agent || typeof agent.agent !== "string" || !agent.agent.trim() || agent.agent.length > AGENT_NAME_MAX)
      continue;
    if (typeof agent.totalCost !== "string") continue;
    const part = decimal(agent.totalCost);
    if (part > 0n) reported.set(agent.agent, (reported.get(agent.agent) ?? 0n) + part);
  }
  const reportedTotal = [...reported.values()].reduce((sum, part) => sum + part, 0n);
  const result = new Map();
  let attributed = 0n;
  if (reportedTotal - cost <= SLACK) {
    for (const [name, part] of reported) {
      const taken = part < cost - attributed ? part : cost - attributed;
      if (taken <= 0n) continue;
      result.set(name, taken);
      attributed += taken;
    }
  }
  const names = row.metadata?.agents;
  if (
    result.size === 0 &&
    cost > 0n &&
    Array.isArray(names) &&
    names.length === 1 &&
    typeof names[0] === "string" &&
    names[0].trim() &&
    names[0].length <= AGENT_NAME_MAX
  ) {
    result.set(names[0], cost);
    attributed = cost;
  }
  const rest = cost - attributed;
  if (rest > 0n && result.size > 0 && rest <= SLACK) {
    const largest = [...result.entries()].reduce((best, entry) => (entry[1] > best[1] ? entry : best))[0];
    result.set(largest, result.get(largest) + rest);
  } else if (rest > 0n) {
    result.set(NOT_REPORTED, (result.get(NOT_REPORTED) ?? 0n) + rest);
  }
  return result;
};

const day = (text) => Date.parse(`${text}T00:00:00Z`);
const iso = (millis) => new Date(millis).toISOString().slice(0, 10);

/// Expected figures for one inclusive date range.
export const expected = (rows, from, to, today) => {
  const days = (day(to) - day(from)) / DAY_MILLIS + 1;
  const bucket = days <= 92 ? "day" : days <= 731 ? "week" : "month";
  const bucketOf = (date) => {
    if (bucket === "day") return date;
    if (bucket === "month") return `${date.slice(0, 7)}-01`;
    const millis = day(date);
    return iso(millis - ((new Date(millis).getUTCDay() + 6) % 7) * DAY_MILLIS);
  };
  let total = 0n;
  const tools = new Map();
  const buckets = new Map();
  const personDays = new Map();
  for (const row of rows) {
    if (row.period < from || row.period > to) continue;
    const cost = decimal(row.totalCost);
    total += cost;
    const key = `${row.owner}\u0000${row.period}`;
    personDays.set(key, (personDays.get(key) ?? 0n) + cost);
    const start = bucketOf(row.period);
    buckets.set(start, (buckets.get(start) ?? 0n) + cost);
    for (const [name, part] of toolCosts(row)) tools.set(name, (tools.get(name) ?? 0n) + part);
  }
  const ordered = [...tools.entries()].sort((left, right) =>
    left[1] === right[1] ? (left[0] < right[0] ? -1 : 1) : left[1] > right[1] ? -1 : 1,
  );
  const legend =
    ordered.length <= SERIES_MAX
      ? ordered
      : [
          ...ordered.slice(0, SERIES_MAX - 1),
          [OTHER_TOOLS, ordered.slice(SERIES_MAX - 1).reduce((sum, entry) => sum + entry[1], 0n)],
        ];
  const used = [...personDays.entries()].filter(([, cost]) => cost > 0n);
  const end = to < today ? to : today;
  const elapsed = end < from ? 0 : (day(end) - day(from)) / DAY_MILLIS + 1;
  return {
    total,
    legend,
    buckets,
    daysWithUsage: new Set(used.map(([key]) => key.split("\u0000")[2])).size,
    people: new Set(used.map(([key]) => key.split("\u0000").slice(0, 2).join("\u0000"))).size,
    elapsed,
  };
};

/// Exact figures rendered by the page.
export const rendered = (html) => {
  const compact = html.replace(/>\s+</gu, "><");
  const kpis = Object.fromEntries(
    [...compact.matchAll(/<dt>([^<]+)<\/dt><dd data-usd="([^"]*)">([^<]+)<\/dd>/gu)].map((match) => [match[1], match]),
  );
  const days = /^(\d+) of (\d+)$/u.exec(kpis["Days with usage"]?.[3] ?? "") ?? fail("days with usage missing");
  return {
    total: decimal(kpis["Estimated cost"]?.[2] ?? fail("estimated cost missing")),
    people: Number(kpis["People with usage"]?.[3]),
    daysWithUsage: Number(days[1]),
    elapsed: Number(days[2]),
    legend: [
      ...compact.matchAll(
        /<li data-usd="([^"]+)"><span class="swatch series-\d" aria-hidden="true"><\/span>([^<]+) <strong>/gu,
      ),
    ].map((match) => [match[2], decimal(match[1])]),
    rows: [...compact.matchAll(/<tr data-usd="([^"]+)">/gu)].map((match) => decimal(match[1])),
  };
};

/// Differences between expected and rendered figures; empty when they agree exactly.
export const compare = (want, got) => {
  const problems = [];
  if (want.total !== got.total) problems.push(`total ${format(want.total)} != ${format(got.total)}`);
  if (want.people !== got.people) problems.push(`people ${want.people} != ${got.people}`);
  if (want.daysWithUsage !== got.daysWithUsage) problems.push(`days ${want.daysWithUsage} != ${got.daysWithUsage}`);
  if (want.elapsed !== got.elapsed) problems.push(`elapsed ${want.elapsed} != ${got.elapsed}`);
  const legend = (entries) => entries.map(([name, value]) => `${name}=${format(value)}`).join(", ");
  if (legend(want.legend) !== legend(got.legend))
    problems.push(`tools [${legend(want.legend)}] != [${legend(got.legend)}]`);
  const wantRows = [...want.buckets.values()].filter((value) => value !== 0n).sort();
  const gotRows = got.rows.filter((value) => value !== 0n).sort();
  if (wantRows.join() !== gotRows.join()) problems.push("period totals differ");
  return problems;
};

const ranges = (rows, today, count) => {
  const dates = rows
    .map((row) => row.period)
    .filter((date) => date <= today)
    .sort();
  if (!dates.length) return [];
  const first = dates[0];
  const last = dates.at(-1);
  const result = [];
  for (let month = first.slice(0, 7); month <= last.slice(0, 7); ) {
    const [year, number] = month.split("-").map(Number);
    const end = iso(Date.UTC(year, number, 0));
    result.push({ query: `range=month&month=${month}`, from: `${month}-01`, to: end });
    month = iso(Date.UTC(year, number, 1)).slice(0, 7);
  }
  for (let year = Number(first.slice(0, 4)); year <= Number(last.slice(0, 4)); year++) {
    result.push({ query: `range=year&year=${year}`, from: `${year}-01-01`, to: `${year}-12-31` });
  }
  const floor = iso(day(last) - (RANGE_DAYS_MAX - 1) * DAY_MILLIS);
  result.push({ query: "range=all", from: first < floor ? floor : first, to: last });
  let seed = 20260715;
  const random = (limit) => {
    seed = (seed * 1103515245 + 12345) % 2147483648;
    return seed % limit;
  };
  const span = (day(last) - day(first)) / DAY_MILLIS;
  for (let index = 0; index < count; index++) {
    const from = iso(day(first) + random(span + 1) * DAY_MILLIS);
    const to = iso(day(from) + random((day(last) - day(from)) / DAY_MILLIS + 1) * DAY_MILLIS);
    result.push({ query: `range=custom&from=${from}&to=${to}`, from, to });
  }
  return result;
};

const login = async (base) => {
  const response = await fetch(new URL("/login", base), { redirect: "manual" });
  const cookie = response.headers.get("set-cookie");
  if (!cookie) fail(`login failed with HTTP ${response.status}; is the server running with development login?`);
  return cookie.split(";")[0];
};

export async function main(arguments_ = process.argv.slice(2)) {
  const usage = "usage: mise run viz-oracle <events.ndjson> [server-url] [random-ranges]";
  if (!arguments_.length || arguments_.length > 3 || arguments_.includes("--help")) fail(usage);
  const [file, base = "http://127.0.0.1:8080", countText = "200"] = arguments_;
  if (statSync(file).size > INPUT_BYTES_MAX) fail(`events exceed ${INPUT_BYTES_MAX} bytes`);
  const rows = dailyRows(parseEvents(readFileSync(file, "utf8")));
  const today = iso(Date.now());
  const cookie = await login(base);
  let failures = 0;
  const checked = ranges(rows, today, Number(countText));
  for (const range of checked) {
    const response = await fetch(new URL(`/visualizations?${range.query}`, base), { headers: { cookie } });
    if (!response.ok) fail(`${range.query}: HTTP ${response.status}`);
    const problems = compare(expected(rows, range.from, range.to, today), rendered(await response.text()));
    if (problems.length) {
      failures++;
      console.log(`${range.query}\n  ${problems.join("\n  ")}`);
    }
  }
  console.log(`${checked.length - failures} of ${checked.length} ranges match the independent recomputation`);
  if (failures) process.exitCode = 1;
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main().catch((error) => {
    console.error(error.message);
    process.exitCode = 1;
  });
}
