---
id: TT-SPEC-ZWZWS6FD
type: spec
title: Runtime assets
research:
  - TT-RESEARCH-XDTYLCXF
---

## Intent

Package deterministic public/private browser and tracker assets inside the
TokTrak JPMS runtime without a bundler, asset JAR, runtime filesystem server, or
generated Java bytes. Commit production-ready bytes.

## Source and build

Assets live below `sources/toktrak/assets/public` or `private`. Paths are
normalized lowercase ASCII without hidden/symbolic/traversal segments. The build
walks sorted input, enforces count/per-file/total limits, validates extension,
media type, signature, secure SVG profile, and strict UTF-8, then copies exact
bytes into the exploded module.

Generate and revalidate one canonical versioned index containing scope, logical
path, byte length, media type, and full SHA-256. Asset bytes/names participate
in compile and jlink fingerprints. Missing, extra, stale, or corrupt copied
output invalidates cache success. Errors identify asset, violated constraint,
and exact remediation.

Allowed formats are CSS, JS/MJS, SVG, WebP, AVIF, and WOFF2. Raster PNG/JPEG/GIF
and peers are rejected; vectors use safe SVG and raster uses optimized
WebP/AVIF. The build validates but never rewrites assets.

## Runtime and HTTP

Load and hash-check the complete bounded index before HTTP binding. Public
logical names map to exact URLs containing the first 128 SHA-256 bits. Serve
only exact raw-path GET requests without query strings, decoding, normalization,
or fallback lookup. Responses use fixed MIME, exact length, immutable one-year
caching, nosniff, and same-origin resource policy.

Private assets have no route. Personalized tracker output uses no-store.
Compression and responsive image variants belong to proxy/HTML concerns.

## Acceptance

Build tests cover paths, bounds, formats, SVG safety, diagnostics, deterministic
index/copy/fingerprint/cache behavior. Application tests cover eager loading,
hash verification, private isolation, exact routing, MIME/cache/security
headers, and stale URLs. The linked production runtime performs an explicit
asset check.
