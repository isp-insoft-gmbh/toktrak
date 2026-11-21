#!/usr/bin/env node

// Turns a production `events.ndjson` snapshot into a pseudonymous development corpus.
// Every string field is either kept after strict shape validation or replaced by a keyed,
// deterministic pseudonym; unknown string fields fail closed. Numbers keep their exact source text.

import { createHmac, randomBytes } from "node:crypto";
import {
  closeSync,
  existsSync,
  mkdirSync,
  openSync,
  readFileSync,
  renameSync,
  rmSync,
  statSync,
  writeSync,
} from "node:fs";
import { homedir } from "node:os";
import { dirname, isAbsolute, join, relative, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const ROOT = resolve(fileURLToPath(new URL("..", import.meta.url)));
const DEFAULT_OUTPUT = join(ROOT, "output", "corpus", "production-anonymized.jsonl");
const DEV_CORPUS = join(ROOT, "tests", "corpus", "dev.jsonl");
const INPUT_BYTES_MAX = 256 * 1024 * 1024;
const LINES_MAX = 4_000_000;
const NODES_PER_EVENT_MAX = 5_000_000;
const DEPTH_MAX = 64;
const MISSING_SHAPES_SHOWN_MAX = 64;
const DEVELOPMENT_ISSUER = "urn:toktrak:development";
// Development login always signs in as this subject; one uploading person becomes it.
const DEVELOPMENT_VIEWER = "viewer";

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/iu;
const DATE = /^\d{4}-\d{2}-\d{2}$/u;
const TIMESTAMP = /^\d{4}-\d{2}-\d{2}(?:[T ]\d{2}:\d{2}(?::\d{2}(?:\.\d{1,9})?)?(?:Z|[+-]\d{2}:?\d{2})?)?$/u;
const SCHEMA_KEY = /^[A-Za-z][A-Za-z0-9]{0,63}$/u;
const MODEL = /^(?:\[[a-z][a-z0-9-]{0,31}\] )?[A-Za-z0-9][\w.:/@+-]{0,127}$/u;
const AGENT = /^[a-z][a-z0-9-]{0,31}$/u;
const VERSION = /^[\w.+-]{1,64}$/u;
const TIME_ZONE = /^(?:UTC|[A-Za-z_]+(?:\/[A-Za-z0-9_+-]+){1,2})$/u;
const DECIMAL = /^\d{1,32}(?:\.\d{1,32})?$/u;
const COLOR = /^#[0-9a-f]{6}$/u;
const EVENT_TYPE = /^[a-z][a-z-]{0,63}$/u;
const STOP_WORDS = new Set([
  "users",
  "home",
  "workspace",
  "projects",
  "documents",
  "desktop",
  "source",
  "repos",
  "code",
]);

const fail = (message) => {
  throw new Error(message);
};

const keep = (pattern) => (value, field) => {
  if (!pattern.test(value)) fail(`${field.path} has an unexpected shape`);
  return value;
};

const timestampOrPseudonym = (value, field, context) => (TIMESTAMP.test(value) ? value : context.session(value));

/// String rules by field name. Array elements use the nearest object key.
const RULES = {
  type: keep(EVENT_TYPE),
  at: keep(TIMESTAMP),
  generatedAt: keep(TIMESTAMP),
  latestUsageAt: keep(TIMESTAMP),
  lastActivity: keep(TIMESTAMP),
  firstActivity: keep(TIMESTAMP),
  startTime: keep(TIMESTAMP),
  endTime: keep(TIMESTAMP),
  actualEndTime: keep(TIMESTAMP),
  date: keep(DATE),
  period: timestampOrPseudonym,
  id: timestampOrPseudonym,
  sessionId: timestampOrPseudonym,
  actor: (value, field, context) =>
    value === "system" ? value : UUID.test(value) ? context.uuid(value) : context.subject(value),
  subject: (value, field, context) => context.subject(value),
  issuer: () => DEVELOPMENT_ISSUER,
  email: (value, field, context) => `synthetic-${context.owner(field)}@example.invalid`,
  displayName: (value, field, context) => `Synthetic User ${context.owner(field)}`,
  color: keep(COLOR),
  label: (value, field, context) => `Synthetic tracker ${context.tag("label", value)}`,
  digest: (value, field, context) => context.hash("digest", value),
  tokenId: (value, field, context) => context.uuid(value),
  trackerVersion: keep(VERSION),
  ccusageVersion: keep(VERSION),
  clientTimeZone: keep(TIME_ZONE),
  agent: keep(AGENT),
  agents: keep(AGENT),
  sourceReports: keep(AGENT),
  modelName: keep(MODEL),
  modelsUsed: keep(MODEL),
  models: keep(MODEL),
  projectPath: (value, field, context) => `synthetic-project-${context.tag("project", value)}`,
  directory: (value, field, context) => `synthetic-project-${context.tag("project", value)}`,
  sessionFile: (value, field, context) => `synthetic-session-${context.tag("session", value)}.jsonl`,
  error: () => "synthetic report failure",
  eurPerUsd: keep(DECIMAL),
};

/// Kept free-text fields that could accidentally carry an identifying word.
const KEPT_TEXT_FIELDS = new Set([
  "modelName",
  "modelsUsed",
  "models",
  "agent",
  "agents",
  "sourceReports",
  "trackerVersion",
  "ccusageVersion",
  "clientTimeZone",
]);

/// Fields whose original values identify people, machines, or projects.
const SENSITIVE_FIELDS = new Set([
  "actor",
  "subject",
  "email",
  "displayName",
  "label",
  "digest",
  "projectPath",
  "directory",
  "sessionFile",
  "sessionId",
  "period",
  "id",
  "error",
]);

export const parseLine = (line, number) => {
  try {
    return JSON.parse(line, (key, value, source) => (typeof value === "number" ? JSON.rawJSON(source.source) : value));
  } catch (error) {
    fail(`line ${number} is not valid JSON: ${error.message}`);
  }
};

/// A leaf shape: its normalized path plus JSON type, so `null` and `0` are different shapes.
export const shapePath = (path, value) => {
  if (value === null) return `${path}:null`;
  if (JSON.isRawJSON(value)) return `${path}:number`;
  return `${path}:${typeof value}`;
};

export const childPath = (path, key, parentKey, container) => {
  if (Array.isArray(container)) return `${path}[]`;
  if (parentKey === "models") return `${path}.*`;
  return `${path}.${key}`;
};

/// Visits every value iteratively, rewriting strings in place.
const visit = (event, lineNumber, onString, onNumber, onPath, onModelKey = () => {}) => {
  if (!event || typeof event !== "object" || Array.isArray(event)) fail(`line ${lineNumber} is not an event object`);
  const pending = [{ container: event, path: String(event.type ?? "event"), field: "", owner: undefined, depth: 0 }];
  let nodes = 0;
  while (pending.length) {
    const { container, path, field, owner, depth } = pending.pop();
    if (depth > DEPTH_MAX) fail(`${path} exceeds nesting depth ${DEPTH_MAX}`);
    const ownerHere = !Array.isArray(container) && typeof container.subject === "string" ? container.subject : owner;
    for (const key of Object.keys(container)) {
      nodes++;
      if (nodes > NODES_PER_EVENT_MAX) fail(`line ${lineNumber} exceeds ${NODES_PER_EVENT_MAX} values`);
      const value = container[key];
      const isArray = Array.isArray(container);
      if (!isArray) {
        if (field === "models") {
          if (!MODEL.test(key)) fail(`${path} has an unexpected model key shape`);
          onModelKey(key);
        } else if (!SCHEMA_KEY.test(key)) {
          fail(`${path} has an unexpected key shape`);
        }
      }
      const fieldName = isArray ? field : key;
      const nextPath = childPath(path, key, field, container);
      if (value === null || typeof value === "boolean") {
        onPath(shapePath(nextPath, value));
      } else if (JSON.isRawJSON(value)) {
        onNumber(value.rawJSON);
        onPath(shapePath(nextPath, value));
      } else if (typeof value === "string") {
        container[key] = onString(value, { name: fieldName, path: nextPath, owner: ownerHere });
        onPath(shapePath(nextPath, value));
      } else if (typeof value === "object") {
        pending.push({ container: value, path: nextPath, field: fieldName, owner: ownerHere, depth: depth + 1 });
      } else {
        fail(`${nextPath} has unsupported JSON type`);
      }
    }
  }
};

export const shapePaths = (text) => {
  const paths = new Set();
  text.split("\n").forEach((line, index) => {
    if (!line.trim()) return;
    visit(
      parseLine(line, index + 1),
      index + 1,
      (value) => value,
      () => {},
      (path) => paths.add(path),
    );
  });
  return paths;
};

const pseudonymizer = (key, viewerSubject) => {
  const byOutput = new Map();
  const digest = (kind, value) => createHmac("sha256", key).update(`${kind}\0${value}`).digest("hex");
  const remember = (kind, value, output) => {
    const scoped = `${kind}\0${output}`;
    const previous = byOutput.get(scoped);
    if (previous !== undefined && previous !== value) fail(`pseudonym collision in ${kind}; rerun with a new key`);
    byOutput.set(scoped, value);
    return output;
  };
  const tag = (kind, value) => remember(kind, value, digest(kind, value).slice(0, 12));
  const uuid = (value) => {
    const hex = digest("uuid", value.toLowerCase());
    return remember(
      "uuid",
      value.toLowerCase(),
      `${hex.slice(0, 8)}-${hex.slice(8, 12)}-4${hex.slice(13, 16)}-8${hex.slice(17, 20)}-${hex.slice(20, 32)}`,
    );
  };
  return {
    tag,
    uuid,
    // Tracker token digests are unpadded Base64URL SHA-256 values.
    hash: (kind, value) => remember(kind, value, Buffer.from(digest(kind, value), "hex").toString("base64url")),
    subject: (value) => (value === viewerSubject ? DEVELOPMENT_VIEWER : `synthetic-subject-${tag("subject", value)}`),
    owner: (field) => {
      if (field.owner === undefined) fail(`${field.path} has no subject to derive a pseudonym from`);
      return tag("subject", field.owner);
    },
    session: (value) => {
      if (UUID.test(value)) return uuid(value);
      return `synthetic-session-${tag("session", value)}${value.endsWith(".jsonl") ? ".jsonl" : ""}`;
    },
  };
};

/// Words that must never survive anonymization: identities and path segments near home directories.
const sensitiveWords = (value, field) => {
  const words = new Set([value.toLowerCase()]);
  if (field === "email") words.add(value.split("@")[0].toLowerCase());
  if (field === "displayName" || field === "label") {
    for (const word of value.toLowerCase().split(/[^\p{L}\p{N}]+/u)) if (word.length >= 3) words.add(word);
  }
  const segments = value.split(/[\\/]+/u).map((segment) => segment.toLowerCase());
  segments.forEach((segment, index) => {
    if ((segments[index - 1] === "users" || segments[index - 1] === "home") && segment.length >= 2) words.add(segment);
  });
  return [...words].filter((word) => word.length >= 2 && !STOP_WORDS.has(word));
};

const containsWord = (text, word) => {
  let index = text.indexOf(word);
  while (index >= 0) {
    const before = text[index - 1];
    const after = text[index + word.length];
    const boundary = (character) => character === undefined || !/[\p{L}\p{N}]/u.test(character);
    if (boundary(before) && boundary(after)) return true;
    index = text.indexOf(word, index + 1);
  }
  return false;
};

/// Pseudonymizes NDJSON events. Throws on unsupported string fields, unexpected kept-value shapes,
/// pseudonym collisions, identifying words inside kept free-text fields, or changed numbers.
export function anonymize(text, key) {
  if (typeof JSON.rawJSON !== "function")
    fail("Node.js with JSON.rawJSON support is required to preserve exact numbers");
  if (typeof key !== "string" || !/^[0-9a-f]{64}$/u.test(key))
    fail("anonymization key must be 64 lowercase hex characters");
  const lines = text.split("\n");
  if (lines.length > LINES_MAX) fail(`snapshot exceeds ${LINES_MAX} lines`);
  const context = pseudonymizer(key, viewerSubject(lines, key));
  const sensitive = new Set();
  const outputs = [];
  const sourceNumbers = [];
  const keptStrings = new Set();
  lines.forEach((line, index) => {
    if (!line.trim()) return;
    const event = parseLine(line, index + 1);
    visit(
      event,
      index + 1,
      (value, field) => {
        const rule = RULES[field.name];
        if (!rule) fail(`${field.path} is an unsupported string field; add an explicit anonymization rule`);
        const output = rule(value, field, context);
        if (SENSITIVE_FIELDS.has(field.name) && output !== value) {
          for (const word of sensitiveWords(value, field.name)) sensitive.add(word);
        } else if (KEPT_TEXT_FIELDS.has(field.name)) {
          keptStrings.add(value.toLowerCase());
        }
        return output;
      },
      (number) => sourceNumbers.push(number),
      () => {},
      (modelKey) => keptStrings.add(modelKey.toLowerCase()),
    );
    outputs.push(JSON.stringify(event));
  });

  for (const kept of keptStrings) {
    for (const word of sensitive) {
      if (word.length >= 3 && containsWord(kept, word))
        fail("a kept field contains an identifying word; extend the anonymization rules");
    }
  }
  const outputNumbers = [];
  outputs.forEach((line, index) =>
    visit(
      parseLine(line, index + 1),
      index + 1,
      (value) => value,
      (number) => outputNumbers.push(number),
      () => {},
    ),
  );
  if (
    outputNumbers.length !== sourceNumbers.length ||
    outputNumbers.some((number, index) => number !== sourceNumbers[index])
  ) {
    fail("numeric values changed during anonymization");
  }
  return { lines: outputs, numbers: sourceNumbers.length };
}

/// Picks a key-dependent, stable, effectively random uploading subject for the development login.
const viewerSubject = (lines, key) => {
  let chosen;
  let chosenRank;
  for (const line of lines) {
    if (!line.includes('"usage-uploaded"')) continue;
    const event = JSON.parse(line);
    const subject = event.type === "usage-uploaded" ? event.data?.subject : undefined;
    if (typeof subject !== "string") continue;
    const rank = createHmac("sha256", key).update(`viewer\0${subject}`).digest("hex");
    if (chosenRank === undefined || rank < chosenRank) {
      chosen = subject;
      chosenRank = rank;
    }
  }
  return chosen;
};

const inside = (parent, child) => {
  const path = relative(parent, child);
  return path === "" || (!path.startsWith("..") && !isAbsolute(path));
};

const defaultKeyPath = () => {
  // LOCALAPPDATA, unlike APPDATA, never roams to other Windows machines.
  const base = process.env.LOCALAPPDATA || process.env.XDG_CONFIG_HOME || join(homedir(), ".config");
  return join(base, "toktrak", "corpus-anonymization.key");
};

const missingKey = (path) =>
  [
    `anonymization key not found: ${path}`,
    "Every import of the same deployment must use the same key, or pseudonyms will not line up.",
    "Existing deployment: copy its key from your team's secret manager into that file,",
    "  or point TOKTRAK_CORPUS_KEY_FILE at it (for example in gitignored mise.local.toml under [env]).",
    "First import ever: rerun with --new-key, then store the created key in your team's secret manager.",
  ].join("\n");

const createKey = (path) => {
  if (existsSync(path)) fail(`anonymization key already exists: ${path}; drop --new-key to use it`);
  mkdirSync(dirname(path), { recursive: true });
  const descriptor = openSync(path, "wx", 0o600);
  try {
    writeSync(descriptor, `${randomBytes(32).toString("hex")}\n`);
  } finally {
    closeSync(descriptor);
  }
  console.log(`created anonymization key ${path}; store it in your team's secret manager now`);
};

const readKey = (path, create) => {
  if (inside(ROOT, path)) fail("anonymization key must live outside the repository");
  if (create) createKey(path);
  else if (!existsSync(path)) fail(missingKey(path));
  const key = readFileSync(path, "utf8").trim();
  if (!/^[0-9a-f]{64}$/u.test(key)) fail(`anonymization key ${path} must contain 64 lowercase hex characters`);
  return key;
};

const writeAtomically = (path, lines) => {
  mkdirSync(dirname(path), { recursive: true });
  const temporary = `${path}.tmp`;
  rmSync(temporary, { force: true });
  const descriptor = openSync(temporary, "wx", 0o600);
  try {
    for (const line of lines) writeSync(descriptor, `${line}\n`);
  } finally {
    closeSync(descriptor);
  }
  renameSync(temporary, path);
};

const usage = "usage: mise run corpus-import <events.ndjson> [--output <path>] [--key <path>] [--new-key]";

export function main(arguments_ = process.argv.slice(2)) {
  let input;
  let output = DEFAULT_OUTPUT;
  let keyPath = process.env.TOKTRAK_CORPUS_KEY_FILE || defaultKeyPath();
  let newKey = false;
  for (let index = 0; index < arguments_.length; index++) {
    const argument = arguments_[index];
    if (argument === "--help") {
      console.log(usage);
      return;
    }
    if (argument === "--new-key") {
      newKey = true;
      continue;
    }
    if (argument === "--output" || argument === "--key") {
      const value = arguments_[++index];
      if (!value) fail(usage);
      if (argument === "--output") output = resolve(value);
      else keyPath = resolve(value);
    } else if (!input) {
      input = resolve(argument);
    } else {
      fail(usage);
    }
  }
  if (!input) fail(usage);
  if (inside(ROOT, input)) fail("production snapshots must stay outside the repository");
  if (inside(ROOT, output) && !inside(join(ROOT, "output"), output))
    fail("anonymized output inside the repository must stay under output/");
  if (statSync(input).size > INPUT_BYTES_MAX) fail(`snapshot exceeds ${INPUT_BYTES_MAX} bytes`);

  const result = anonymize(readFileSync(input, "utf8"), readKey(keyPath, newKey));
  writeAtomically(output, result.lines);

  const known = shapePaths(readFileSync(DEV_CORPUS, "utf8"));
  const missing = [...shapePaths(result.lines.join("\n"))].filter((path) => !known.has(path)).sort();
  console.log(`wrote ${result.lines.length} events (${result.numbers} numbers preserved) to ${output}`);
  console.log(`run it: mise run dev --corpus "${output}"`);
  if (missing.length) {
    console.log(`${missing.length} field shapes are missing from tests/corpus/dev.jsonl:`);
    for (const path of missing.slice(0, MISSING_SHAPES_SHOWN_MAX)) console.log(`  ${path}`);
    if (missing.length > MISSING_SHAPES_SHOWN_MAX) console.log(`  … ${missing.length - MISSING_SHAPES_SHOWN_MAX} more`);
  } else {
    console.log("tests/corpus/dev.jsonl already covers every field shape");
  }
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try {
    main();
  } catch (error) {
    console.error(error.message);
    process.exitCode = 1;
  }
}
