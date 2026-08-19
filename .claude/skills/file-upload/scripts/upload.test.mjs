import assert from "node:assert/strict";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { spawnSync } from "node:child_process";
import test from "node:test";
import { createS3PutRequest } from "./upload.mjs";

const directory = dirname(fileURLToPath(import.meta.url));
const upload = join(directory, "upload.mjs");

const run = (args, environment = {}) => {
  const env = { ...process.env };
  delete env.GATEBRIDGE_R2_ACCESS_KEY_ID;
  delete env.GATEBRIDGE_R2_SECRET_ACCESS_KEY;
  delete env.GATEBRIDGE_R2_ENDPOINT;
  Object.assign(env, environment);
  return spawnSync(process.execPath, [upload, ...args], {
    encoding: "utf8",
    env,
  });
};

test("requires Gatebridge S3 environment", () => {
  const result = run(["artifact.png"]);
  assert.equal(result.status, 1);
  assert.match(result.stderr, /Missing GATEBRIDGE_R2_ACCESS_KEY_ID/);
});

test("creates deterministic R2 S3 PUT signature", () => {
  const payloadHash = "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824";
  const request = createS3PutRequest({
    endpoint: "https://example.r2.cloudflarestorage.com",
    accessKeyId: "test-access",
    secretAccessKey: "test-secret",
    key: "1y/example file.txt",
    contentType: "text/plain;charset=utf-8",
    contentLength: 5,
    payloadHash,
    now: new Date("2026-08-15T12:34:56Z"),
  });
  assert.equal(request.url, "https://example.r2.cloudflarestorage.com/agent-artifacts/1y/example%20file.txt");
  assert.deepEqual(request.headers, {
    Authorization:
      "AWS4-HMAC-SHA256 Credential=test-access/20260815/auto/s3/aws4_request, " +
      "SignedHeaders=host;x-amz-content-sha256;x-amz-date, " +
      "Signature=45c1f4adf83e558eaf486ce0da0019a6f236da0b1211e02377bbf8eefbbce3ea",
    "Content-Disposition": "inline",
    "Content-Length": "5",
    "Content-Type": "text/plain;charset=utf-8",
    "X-Amz-Content-Sha256": payloadHash,
    "X-Amz-Date": "20260815T123456Z",
  });
});

test("rejects non-origin endpoints", () => {
  assert.throws(
    () =>
      createS3PutRequest({
        endpoint: "https://example.invalid/path",
        accessKeyId: "test-access",
        secretAccessKey: "test-secret",
        key: "7d/file.txt",
        contentType: "text/plain",
        contentLength: 0,
        payloadHash: "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
        now: new Date("2026-08-15T12:34:56Z"),
      }),
    /must not contain a path/,
  );
});
