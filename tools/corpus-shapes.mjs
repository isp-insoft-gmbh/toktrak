#!/usr/bin/env node

// Adds the field shapes of pseudonymized production data that `tests/corpus/dev.jsonl` lacks.
// It picks the fewest events covering every missing shape (path plus JSON type), prunes their
// arrays to the rows that carry those shapes, and synthesizes their values:
// - identities, tokens, projects, and sessions become fresh synthetic identifiers;
// - timestamps and dates are regenerated: only their order, equality, and text format survive;
// - numbers keep sign, zero, fraction length, and exponent, but get random digits and at most
//   two integer digits, so related numbers no longer add up;
// - model, tool, version, and time-zone names, booleans, and nulls stay as they are.

import { createHash } from "node:crypto";
import { appendFileSync, readFileSync, statSync } from "node:fs";
import { isAbsolute, join, relative, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { childPath, parseLine, shapePath, shapePaths } from "./corpus-anonymize.mjs";

const ROOT = resolve(fileURLToPath(new URL("..", import.meta.url)));
const DEFAULT_INPUT = join(ROOT, "output", "corpus", "production-anonymized.jsonl");
const DEV_CORPUS = join(ROOT, "tests", "corpus", "dev.jsonl");
const INPUT_BYTES_MAX = 256 * 1024 * 1024;
const LINES_MAX = 4_000_000;
const SELECTIONS_MAX = 256;
const NODES_MAX = 20_000_000;
const MINUTE_MILLIS = 60_000;
const DAY_MILLIS = 86_400_000;
// Shape events end before the hand-written history starting 2026-02-11, so they never change the
// displayed month, recent trend, or ingestion status that corpus tests pin; only all-time totals.
const LATEST_INSTANT_MILLIS = Date.UTC(2026, 0, 31, 20);
const LATEST_DATE_MILLIS = Date.UTC(2026, 0, 31);
const INSTANT_GAP_MINUTES_MAX = 360;
const DATE_GAP_DAYS_MAX = 3;
// Small integer parts keep corpus totals readable while fractions and exponents keep their format.
const INTEGER_DIGITS_MAX = 2;
const DEVELOPMENT_ISSUER = "urn:toktrak:development";
const SHAPE_USER_COLOR = "#ffd6a5";

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/iu;
const DATE = /^\d{4}-\d{2}-\d{2}$/u;
const INSTANT = /^(\d{4}-\d{2}-\d{2})T(\d{2}):(\d{2})(?::(\d{2})(?:\.(\d{1,9}))?)?(Z|[+-]\d{2}:?\d{2})?$/u;
const PSEUDONYM = /\bsynthetic-(project|session)-[0-9a-f]{12}/gu;
const NUMBER = /^(-?)(\d+)(?:\.(\d+))?([eE][+-]?\d+)?$/u;
const TOKEN_EVENT = /^identity-tracker-token-(created|used|revoked)$/u;

const fail = (message) => {
  throw new Error(message);
};

const isContainer = (value) => value !== null && typeof value === "object" && !JSON.isRawJSON(value);

/// Leaf shape paths below `value`, which itself sits at `path` under field `field`.
const subtreePaths = (value, path, field) => {
  const paths = new Set();
  if (!isContainer(value)) return paths.add(shapePath(path, value));
  const pending = [{ container: value, path, field }];
  let nodes = 0;
  while (pending.length) {
    const current = pending.pop();
    for (const key of Object.keys(current.container)) {
      if (++nodes > NODES_MAX) fail(`${path} exceeds ${NODES_MAX} values`);
      const child = current.container[key];
      const childPathValue = childPath(current.path, key, current.field, current.container);
      const childField = Array.isArray(current.container) ? current.field : key;
      if (isContainer(child)) pending.push({ container: child, path: childPathValue, field: childField });
      else paths.add(shapePath(childPathValue, child));
    }
  }
  return paths;
};

const intersection = (values, wanted) => new Set([...values].filter((value) => wanted.has(value)));

/// Greedy set cover: indexes whose sets together contain every wanted path.
const cover = (sets) => {
  const uncovered = new Set(sets.flatMap((set) => [...set]));
  const chosen = [];
  while (uncovered.size) {
    let best = -1;
    let bestCount = 0;
    sets.forEach((set, index) => {
      let count = 0;
      for (const path of set) if (uncovered.has(path)) count++;
      if (count > bestCount) {
        best = index;
        bestCount = count;
      }
    });
    assert(best >= 0, "set cover made no progress");
    chosen.push(best);
    for (const path of sets[best]) uncovered.delete(path);
  }
  return chosen.sort((left, right) => left - right);
};

const assert = (condition, message) => {
  if (!condition) fail(`internal error: ${message}`);
};

/// Keeps only array rows needed for the missing shapes, or the first row when none is needed.
const prune = (event, missing) => {
  const pending = [{ container: event, path: String(event.type), field: "" }];
  while (pending.length) {
    const current = pending.pop();
    for (const key of Object.keys(current.container)) {
      const value = current.container[key];
      if (!isContainer(value)) continue;
      const path = childPath(current.path, key, current.field, current.container);
      const field = Array.isArray(current.container) ? current.field : key;
      if (Array.isArray(value) && value.length > 1) {
        const wanted = value.map((element) => intersection(subtreePaths(element, `${path}[]`, field), missing));
        const chosen = cover(wanted);
        current.container[key] = (chosen.length ? chosen : [0]).map((index) => value[index]);
      }
      pending.push({ container: current.container[key], path, field });
    }
  }
  return event;
};

/// Picks the fewest pruned events covering every shape path missing from the known corpus.
export function selectShapes(lines, known) {
  if (lines.length > LINES_MAX) fail(`input exceeds ${LINES_MAX} lines`);
  const entries = [];
  lines.forEach((line, index) => {
    if (!line.trim()) return;
    const event = parseLine(line, index + 1);
    const paths = [...subtreePaths(event, String(event.type), "")].filter((path) => !known.has(path));
    if (paths.length) entries.push({ line, number: index + 1, paths: new Set(paths) });
  });
  const missing = new Set(entries.flatMap((entry) => [...entry.paths]));
  const selected = [];
  while (missing.size) {
    if (selected.length >= SELECTIONS_MAX) fail(`shape selection exceeds ${SELECTIONS_MAX} events`);
    let best;
    let bestCount = 0;
    for (const entry of entries) {
      let count = 0;
      for (const path of entry.paths) if (missing.has(path)) count++;
      if (count > bestCount) {
        best = entry;
        bestCount = count;
      }
    }
    assert(best !== undefined, "missing shape has no source event");
    const event = prune(parseLine(best.line, best.number), missing);
    const covered = intersection(subtreePaths(event, String(event.type), ""), missing);
    assert(covered.size > 0, "pruning removed every missing shape");
    for (const path of covered) missing.delete(path);
    selected.push({ number: best.number, event });
  }
  return selected.sort((left, right) => left.number - right.number).map((entry) => entry.event);
}

/// Deterministic xorshift32 so the same input always yields the same synthetic corpus lines.
const randomSource = (seed) => {
  let state = seed >>> 0 || 1;
  return (limit) => {
    state ^= state << 13;
    state ^= state >>> 17;
    state ^= state << 5;
    return (state >>> 0) % limit;
  };
};

const syntheticNumber = (raw, random) => {
  const match = NUMBER.exec(raw);
  if (!match) fail(`unsupported number format: ${raw}`);
  const [, sign, integer, fraction = "", exponent = ""] = match;
  if (/^0+$/u.test(integer) && /^0*$/u.test(fraction)) return raw;
  const digits = (count) => Array.from({ length: count }, () => String(random(10))).join("");
  const integerDigits = Math.min(integer.length, INTEGER_DIGITS_MAX);
  const syntheticInteger = /^0+$/u.test(integer) ? integer : String(1 + random(9)) + digits(integerDigits - 1);
  return `${sign}${syntheticInteger}${fraction ? `.${digits(fraction.length)}` : ""}${exponent}`;
};

const offsetMillis = (zone) => {
  if (!zone || zone === "Z") return 0;
  const [, sign, hours, minutes] = /^([+-])(\d{2}):?(\d{2})$/u.exec(zone);
  return (sign === "-" ? -1 : 1) * (Number(hours) * 60 + Number(minutes)) * MINUTE_MILLIS;
};

/// Sortable instant in nanoseconds-of-precision text order, or undefined for non-instants.
const instantKey = (value) => {
  const match = INSTANT.exec(value);
  if (!match) return undefined;
  const [, date, hours, minutes, seconds = "00", fraction = "", zone] = match;
  const millis = Date.parse(`${date}T${hours}:${minutes}:${seconds}Z`) - offsetMillis(zone);
  if (Number.isNaN(millis)) fail("unsupported timestamp");
  return `${String(millis).padStart(16, "0")}.${fraction.padEnd(9, "0")}`;
};

/// Same text format as `original`, describing the synthetic instant.
const formatInstant = (original, millis, random) => {
  const [, , , , seconds, fraction, zone] = INSTANT.exec(original);
  const local = new Date(millis + offsetMillis(zone)).toISOString();
  const extra = Array.from({ length: Math.max(0, (fraction?.length ?? 0) - 3) }, () => String(random(10))).join("");
  const fractionText = fraction === undefined ? "" : `.${(local.slice(20, 23) + extra).slice(0, fraction.length)}`;
  return `${local.slice(0, 16)}${seconds === undefined ? "" : `:${local.slice(17, 19)}${fractionText}`}${zone ?? ""}`;
};

/// Replaces every timestamp and date with synthetic ones that keep only order and equality.
const timeline = (events, random) => {
  const instants = new Set();
  const dates = new Set();
  for (const event of events) {
    forEachValue(
      event,
      (value) => {
        if (DATE.test(value)) dates.add(value);
        else if (instantKey(value) !== undefined) instants.add(instantKey(value));
        return value;
      },
      (raw) => raw,
    );
  }
  const syntheticInstants = new Map();
  let instant = LATEST_INSTANT_MILLIS;
  for (const key of [...instants].sort().reverse()) {
    syntheticInstants.set(key, instant);
    instant -= (1 + random(INSTANT_GAP_MINUTES_MAX)) * MINUTE_MILLIS + random(1000);
  }
  const syntheticDates = new Map();
  let date = LATEST_DATE_MILLIS;
  for (const value of [...dates].sort().reverse()) {
    syntheticDates.set(value, new Date(date).toISOString().slice(0, 10));
    date -= (1 + random(DATE_GAP_DAYS_MAX)) * DAY_MILLIS;
  }
  return (value) => {
    if (DATE.test(value)) return syntheticDates.get(value);
    const key = instantKey(value);
    return key === undefined ? undefined : formatInstant(value, syntheticInstants.get(key), random);
  };
};

const forEachValue = (event, onString, onNumber) => {
  const pending = [{ container: event, field: "" }];
  while (pending.length) {
    const current = pending.pop();
    for (const key of Object.keys(current.container)) {
      const value = current.container[key];
      const field = Array.isArray(current.container) ? current.field : key;
      if (JSON.isRawJSON(value)) current.container[key] = JSON.rawJSON(onNumber(value.rawJSON, field));
      else if (typeof value === "string") current.container[key] = onString(value, field);
      else if (isContainer(value)) pending.push({ container: value, field });
    }
  }
};

const nextSubjectNumber = (corpus) => {
  let highest = 1;
  for (const match of corpus.matchAll(/synthetic-subject-(\d+)/gu)) highest = Math.max(highest, Number(match[1]));
  return highest + 1;
};

/// Rewrites selected events into synthetic corpus events owned by one new development user.
export function synthesize(events, subjectNumber) {
  const subject = `synthetic-subject-${subjectNumber}`;
  const random = randomSource(0x70c7a4);
  const uuids = new Map();
  const pseudonyms = new Map();
  const labels = new Map();
  const uuid = (value) => {
    const key = value.toLowerCase();
    if (!uuids.has(key)) {
      const hex = createHash("sha256").update(`toktrak-corpus-shape\0${subjectNumber}\0${uuids.size}`).digest("hex");
      uuids.set(
        key,
        `${hex.slice(0, 8)}-${hex.slice(8, 12)}-4${hex.slice(13, 16)}-8${hex.slice(17, 20)}-${hex.slice(20, 32)}`,
      );
    }
    return uuids.get(key);
  };
  const pseudonym = (value) =>
    value.replace(PSEUDONYM, (whole, kind) => {
      if (!pseudonyms.has(whole)) pseudonyms.set(whole, `synthetic-${kind}-shape-${pseudonyms.size + 1}`);
      return pseudonyms.get(whole);
    });

  const time = timeline(events, random);

  const synthetic = events.map((event) => {
    forEachValue(
      event,
      (value, field) => {
        switch (field) {
          case "subject":
            return subject;
          case "issuer":
            return DEVELOPMENT_ISSUER;
          case "email":
            return `synthetic-${subjectNumber}@example.invalid`;
          case "displayName":
            return `Synthetic User ${subjectNumber}`;
          case "label":
            if (!labels.has(value)) labels.set(value, `Synthetic tracker ${labels.size + 1}`);
            return labels.get(value);
          case "digest":
            return createHash("sha256").update(`toktrak-corpus-shape-digest\0${value}`).digest("base64url");
          case "actor":
            if (!UUID.test(value) && value !== "system") return subject;
        }
        if (UUID.test(value)) return uuid(value);
        const syntheticTime = time(value);
        if (syntheticTime !== undefined) return syntheticTime;
        return pseudonym(value);
      },
      // Envelope schema versions select replay rules; they stay exact.
      (raw, field) => (field === "schemaVersion" ? raw : syntheticNumber(raw, random)),
    );
    return event;
  });

  const tokens = new Set(
    synthetic.filter((event) => event.type === "identity-tracker-token-created").map((event) => event.data.tokenId),
  );
  const template = synthetic.find((event) => event.type === "identity-tracker-token-created");
  const sequence = [];
  for (const event of synthetic) {
    const tokenId = TOKEN_EVENT.test(event.type) ? event.data.tokenId : undefined;
    if (tokenId !== undefined && !tokens.has(tokenId)) {
      if (!template) fail(`${event.type} needs a token-created event to copy`);
      sequence.push({
        ...template,
        id: uuid(`created-for-${tokenId}`),
        at: event.at,
        data: {
          ...template.data,
          tokenId,
          digest: createHash("sha256").update(`toktrak-corpus-shape-digest\0${tokenId}`).digest("base64url"),
        },
      });
      tokens.add(tokenId);
    }
    sequence.push(event);
  }
  const earliest = sequence.map((event) => event.at).sort()[0];
  const user = {
    id: uuid(`user-${subject}`),
    at: earliest,
    type: "identity-user-authenticated",
    schemaVersion: JSON.rawJSON("1"),
    actor: subject,
    data: {
      issuer: DEVELOPMENT_ISSUER,
      subject,
      email: `synthetic-${subjectNumber}@example.invalid`,
      displayName: `Synthetic User ${subjectNumber}`,
      color: SHAPE_USER_COLOR,
    },
  };
  const lines = [user, ...sequence].map((event) => JSON.stringify(event));
  for (const line of lines) {
    if (/synthetic-(?:project|session)-[0-9a-f]{12}|Synthetic (?:User|tracker) [0-9a-f]{12}/u.test(line))
      fail("a production pseudonym survived synthesis");
  }
  return lines;
}

const inside = (parent, child) => {
  const path = relative(parent, child);
  return path === "" || (!path.startsWith("..") && !isAbsolute(path));
};

export function main(arguments_ = process.argv.slice(2), corpusPath = DEV_CORPUS) {
  if (arguments_.includes("--help")) {
    console.log("usage: mise run corpus-shapes [pseudonymized.jsonl]");
    return;
  }
  if (arguments_.length > 1) fail("usage: mise run corpus-shapes [pseudonymized.jsonl]");
  const input = resolve(arguments_[0] ?? DEFAULT_INPUT);
  if (inside(ROOT, input) && !inside(join(ROOT, "output"), input))
    fail("read pseudonymized input from output/ or outside the repository");
  if (statSync(input).size > INPUT_BYTES_MAX) fail(`input exceeds ${INPUT_BYTES_MAX} bytes`);

  const corpus = readFileSync(corpusPath, "utf8");
  if (!corpus.endsWith("\n")) fail(`${corpusPath} must end with a newline`);
  const known = shapePaths(corpus);
  const selected = selectShapes(readFileSync(input, "utf8").split("\n"), known);
  if (!selected.length) {
    console.log(`${corpusPath} already covers every field shape`);
    return;
  }
  const lines = synthesize(selected, nextSubjectNumber(corpus));
  const after = shapePaths(`${corpus}${lines.join("\n")}\n`);
  const remaining = [...shapePaths(readFileSync(input, "utf8"))].filter((path) => !after.has(path));
  if (remaining.length) fail(`synthesis lost ${remaining.length} shapes, for example ${remaining[0]}`);
  appendFileSync(corpusPath, `${lines.join("\n")}\n`);
  console.log(`appended ${lines.length} synthetic events covering every missing shape to ${corpusPath}`);
  console.log("review the diff and update corpus test expectations deliberately");
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try {
    main();
  } catch (error) {
    console.error(error.message);
    process.exitCode = 1;
  }
}
