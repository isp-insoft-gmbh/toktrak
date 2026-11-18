#!/usr/bin/env node

import { createHash, createHmac, randomBytes } from "node:crypto";
import { createReadStream, lstatSync, realpathSync } from "node:fs";
import { basename, extname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const BUCKET = "agent-artifacts";
const UPLOAD_TIMEOUT_MILLIS = 10 * 60 * 1000;

const fail = (message, status = 1) => {
  console.error(message);
  process.exit(status);
};

const sha256 = (value) => createHash("sha256").update(value).digest("hex");
const hmac = (key, value) => createHmac("sha256", key).update(value).digest();
const encodePathSegment = (value) =>
  encodeURIComponent(value).replace(
    /[!'()*]/g,
    (character) => `%${character.charCodeAt(0).toString(16).toUpperCase()}`,
  );

export const createS3PutRequest = ({
  endpoint,
  accessKeyId,
  secretAccessKey,
  key,
  contentType,
  contentLength,
  payloadHash,
  now,
}) => {
  const base = new URL(endpoint);
  if (base.protocol !== "https:" || base.username || base.password || base.search || base.hash) {
    throw new Error("GATEBRIDGE_R2_ENDPOINT must be a plain HTTPS origin");
  }
  if (base.pathname !== "/") {
    throw new Error("GATEBRIDGE_R2_ENDPOINT must not contain a path");
  }
  const amzDate = now.toISOString().replace(/[:-]|\.\d{3}/g, "");
  const date = amzDate.slice(0, 8);
  const canonicalUri = `/${[BUCKET, ...key.split("/")].map(encodePathSegment).join("/")}`;
  const canonicalHeaders = `host:${base.host}\n` + `x-amz-content-sha256:${payloadHash}\n` + `x-amz-date:${amzDate}\n`;
  const signedHeaders = "host;x-amz-content-sha256;x-amz-date";
  const canonicalRequest = ["PUT", canonicalUri, "", canonicalHeaders, signedHeaders, payloadHash].join("\n");
  const scope = `${date}/auto/s3/aws4_request`;
  const stringToSign = ["AWS4-HMAC-SHA256", amzDate, scope, sha256(canonicalRequest)].join("\n");
  const dateKey = hmac(`AWS4${secretAccessKey}`, date);
  const regionKey = hmac(dateKey, "auto");
  const serviceKey = hmac(regionKey, "s3");
  const signingKey = hmac(serviceKey, "aws4_request");
  const signature = createHmac("sha256", signingKey).update(stringToSign).digest("hex");
  return {
    url: `${base.origin}${canonicalUri}`,
    headers: {
      Authorization:
        `AWS4-HMAC-SHA256 Credential=${accessKeyId}/${scope}, ` +
        `SignedHeaders=${signedHeaders}, Signature=${signature}`,
      "Content-Disposition": "inline",
      "Content-Length": String(contentLength),
      "Content-Type": contentType,
      "X-Amz-Content-Sha256": payloadHash,
      "X-Amz-Date": amzDate,
    },
  };
};

const hashFile = async (file) => {
  const hash = createHash("sha256");
  for await (const chunk of createReadStream(file)) hash.update(chunk);
  return hash.digest("hex");
};

const contentTypes = {
  ".cast": "application/x-asciicast",
  ".css": "text/css;charset=utf-8",
  ".gif": "image/gif",
  ".htm": "text/html;charset=utf-8",
  ".html": "text/html;charset=utf-8",
  ".jpeg": "image/jpeg",
  ".jpg": "image/jpeg",
  ".js": "text/javascript;charset=utf-8",
  ".json": "application/json;charset=utf-8",
  ".m4v": "video/mp4",
  ".md": "text/markdown;charset=utf-8",
  ".mov": "video/quicktime",
  ".mp3": "audio/mpeg",
  ".mp4": "video/mp4",
  ".ogg": "audio/ogg",
  ".pdf": "application/pdf",
  ".png": "image/png",
  ".svg": "image/svg+xml",
  ".txt": "text/plain;charset=utf-8",
  ".wav": "audio/wav",
  ".webm": "video/webm",
  ".webp": "image/webp",
};

const main = async () => {
  const [input, retention = "1y", confirmation] = process.argv.slice(2);
  const retentions = new Set(["7d", "1y", "keep"]);
  const invalidConfirmation = confirmation && (retention !== "keep" || confirmation !== "--confirm-keep");
  if (!input || !retentions.has(retention) || invalidConfirmation) {
    fail("Usage: upload.mjs <file> [7d|1y|keep] [--confirm-keep]", 2);
  }
  if (retention === "keep" && confirmation !== "--confirm-keep") {
    fail("Permanent retention requires user confirmation; then pass --confirm-keep", 2);
  }
  const environmentNames = ["GATEBRIDGE_R2_ACCESS_KEY_ID", "GATEBRIDGE_R2_SECRET_ACCESS_KEY", "GATEBRIDGE_R2_ENDPOINT"];
  for (const name of environmentNames) {
    if (!process.env[name]?.trim()) fail(`Missing ${name}. Set it before uploading.`);
  }

  const file = resolve(input);
  let stat;
  try {
    stat = lstatSync(file);
  } catch {
    fail(`File not found: ${file}`);
  }
  if (!stat.isFile()) fail(`Not a regular file: ${file}`);
  if (stat.size > 315 * 1024 * 1024) fail("Uploads are limited to 315 MiB");

  const safeName =
    basename(file)
      .replace(/[^A-Za-z0-9._-]+/g, "-")
      .replace(/^-+|-+$/g, "") || "artifact";
  const stamp = new Date().toISOString().replace(/[-:.]/g, "");
  const key = `${retention}/${stamp}-${randomBytes(4).toString("hex")}-${safeName}`;
  const request = createS3PutRequest({
    endpoint: process.env.GATEBRIDGE_R2_ENDPOINT,
    accessKeyId: process.env.GATEBRIDGE_R2_ACCESS_KEY_ID,
    secretAccessKey: process.env.GATEBRIDGE_R2_SECRET_ACCESS_KEY,
    key,
    contentType: contentTypes[extname(file).toLowerCase()] ?? "application/octet-stream",
    contentLength: stat.size,
    payloadHash: await hashFile(file),
    now: new Date(),
  });
  const response = await fetch(request.url, {
    method: "PUT",
    headers: request.headers,
    body: createReadStream(file),
    duplex: "half",
    redirect: "error",
    signal: AbortSignal.timeout(UPLOAD_TIMEOUT_MILLIS),
  });
  if (!response.ok) {
    const details = (await response.text()).slice(0, 4096).trim();
    throw new Error(`R2 upload failed: ${response.status} ${response.statusText}${details ? `: ${details}` : ""}`);
  }
  console.log(`https://gatebridge.link/${key}`);
};

if (process.argv[1] && realpathSync(resolve(process.argv[1])) === realpathSync(fileURLToPath(import.meta.url))) {
  main().catch((error) => fail(error instanceof Error ? error.message : String(error)));
}
