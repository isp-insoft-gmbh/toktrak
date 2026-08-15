---
name: file-upload
description: "Upload and publicly share human-facing artifacts such as HTML plans, specs, reviews, mockups, videos, screenshots, PDFs, audio, and asciinema recordings. Use only when the user asks to upload, publish, or share a file."
---

# File upload

Uploads are public at `https://gatebridge.link` from Cloudflare R2 bucket
`agent-artifacts`.

## Upload

```bash
node .claude/skills/file-upload/scripts/upload.mjs <file> [7d|1y|keep] [--confirm-keep]
```

Retention:

- `7d`: only explicitly ephemeral, temporary, or throwaway artifacts.
- `1y`: default, including PR artifacts and demos.
- `keep`: ask the user first, then pass `--confirm-keep`.

Use this script only; never create another prefix or upload through another
client. It uses Node's standard library and the bucket's S3-compatible endpoint;
no Wrangler, npm package, or global tool is needed. R2 prefixes are virtual and
need no setup. On success, print the script's only output: the public URL.

The host must provide `GATEBRIDGE_R2_ACCESS_KEY_ID`,
`GATEBRIDGE_R2_SECRET_ACCESS_KEY`, and `GATEBRIDGE_R2_ENDPOINT`. Never print
credential values.

Never upload directories, secrets, private data, or unrequested files. Prefer
self-contained HTML. Files above 315 MiB are rejected.

## Missing setup

- Missing `node`: install Node.js using the host package manager, then stop.
- Missing `GATEBRIDGE_R2_ACCESS_KEY_ID`: configure the bucket-scoped R2 Access
  Key ID, then stop.
- Missing `GATEBRIDGE_R2_SECRET_ACCESS_KEY`: configure the matching R2 Secret
  Access Key, then stop.
- Missing `GATEBRIDGE_R2_ENDPOINT`: configure the account and jurisdiction's
  HTTPS S3 endpoint, then stop.

Credentials require write access to R2 bucket `agent-artifacts`.

`7d/` expires after 7 days; `1y/` after 365 days; `keep/` does not.
