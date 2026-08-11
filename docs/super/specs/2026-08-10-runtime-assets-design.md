# Runtime assets design

Date: 2026-08-10 Status: approved

## Goal

Package browser assets inside the TokTrak JPMS runtime through a minimal,
deterministic pipeline that is efficient on mobile and desktop clients.

## Asset inventory

Current runtime asset:

- `main.css`, currently embedded as `Router.MAIN_CSS`.

Expected public runtime assets:

- `main.css`;
- pinned, vendored Datastar browser JavaScript;
- optional small application or chart JavaScript;
- WOFF2 fonts;
- SVG logos, icons, and favicon;
- optimized WebP or AVIF raster images when raster artwork is necessary.

Expected private runtime assets:

- the future personalized tracker `.mjs` template.

Initials avatars remain HTML/CSS. Charts remain inline SVG or canvas. No chart
library, general image set, or other asset is currently required.

## Source layout

```text
sources/toktrak/assets/
├── public/
│   └── main.css
└── private/
```

Public files may receive HTTP URLs. Private files are loadable only by server
code and never enter the HTTP route map.

Paths must be normalized lowercase ASCII using letters, digits, `/`, `.`, `_`,
and `-`. Hidden files, symbolic links, empty segments, `.` segments, and `..`
segments are rejected.

## Build pipeline

The Java build remains the only pipeline. No bundler, minifier, asset JAR, image
converter, font tool, or generated Java byte array is introduced. Contributors
commit production-ready bytes.

During module compilation, `tools/Build.java`:

1. walks the asset tree in sorted order;
2. enforces 64 assets maximum, 4 MiB per asset, and 16 MiB total;
3. validates path, extension, content signature, and strict UTF-8 where
   applicable;
4. copies bytes unchanged into `output/modules/toktrak/assets/`;
5. generates a canonical `assets/index.tsv` containing scope, logical path, byte
   length, media type, and full SHA-256;
6. reopens and validates copied resources against the generated index;
7. writes the compile stamp only after copied resources and index validate.

Asset source names and bytes participate directly in the compilation
fingerprint. The generated index and every copied resource are required cache
outputs; missing, extra, changed, deleted, or corrupt resources invalidate the
cache hit. The jlink fingerprint covers every file in `output/modules/toktrak`,
not only `.class` files. A changed asset therefore invalidates module
compilation and production jlink output even when no Java source changed.
`jlink` embeds the copied module resources in the runtime image with the
`toktrak` module.

The index grammar is versioned and exact:

```text
toktrak-assets-v1
<scope>\t<logical-path>\t<byte-length>\t<media-type>\t<64-lowercase-hex-sha256>
```

It is strict UTF-8 without BOM, uses LF, ends with one newline, and sorts
records by scope then logical path. Scope is exactly `public` or `private`. Safe
path and fixed media-type grammars prohibit tabs or newlines, so escaping is
unnecessary. Duplicate records, extra fields, noncanonical numbers/hashes, and
unsorted input are rejected. Records map only to `assets/public/<logical-path>`
or `assets/private/<logical-path>` inside the module.

Build failures identify the rejected path, violated constraint, and exact
remediation. Examples:

```text
unsupported runtime raster asset: sources/toktrak/assets/public/logo.png;
convert it to optimized WebP or AVIF, or use SVG for vector artwork
```

```text
runtime asset exceeds 4194304 bytes: sources/toktrak/assets/public/hero.avif;
optimize the asset below 4194304 bytes
```

## Validation

Allowed extensions and media types are fixed:

| Extension | Media type                       |
| --------- | -------------------------------- |
| `.css`    | `text/css; charset=utf-8`        |
| `.js`     | `text/javascript; charset=utf-8` |
| `.mjs`    | `text/javascript; charset=utf-8` |
| `.svg`    | `image/svg+xml`                  |
| `.webp`   | `image/webp`                     |
| `.avif`   | `image/avif`                     |
| `.woff2`  | `font/woff2`                     |

CSS, JavaScript, and SVG must be strict UTF-8. WebP, AVIF, and WOFF2 must match
their container signatures; an extension alone is insufficient. Runtime PNG,
JPEG, GIF, BMP, TIFF, and other raster formats are rejected with conversion
guidance.

SVG is securely parsed with JDK XML APIs in a deliberately small static profile.
The build requires secure processing, disables DTDs and external general and
parameter entities, disables external DTD/schema access, and fails if the JAXP
implementation cannot enforce those controls. It rejects processing
instructions; non-SVG element namespaces; `script`, `style`, `foreignObject`,
`iframe`, `object`, `embed`, `audio`, `video`, `image`, and `use` elements;
event-handler, `style`, `href`, `xlink:href`, and `src` attributes; and
attribute values containing `url(`, `@import`, `javascript:`, `data:`, `://`, or
`//`. SVG remains available for compact self-contained vector logos, icons, and
favicons.

The build validates assets; it never silently rewrites, compresses, or repairs
them.

## Runtime loading

`toktrak.http.Assets` loads `assets/index.tsv` and every indexed resource before
HTTP binding. Loading is eager and bounded. Startup fails before binding on a
missing resource, duplicate path, size mismatch, hash mismatch, unsupported
media type, or exceeded bound.

The loaded data is immutable and exposes:

- logical public name to fingerprinted URL;
- exact fingerprinted URL to public response bytes and media type;
- logical private name to private bytes.

For a logical public name such as `main.css`, the URL inserts the first 128 bits
of SHA-256 before the extension:

```text
/assets/main.0123456789abcdef0123456789abcdef.css
```

`Router` receives one `Assets` instance from `App`. Root and error HTML ask it
for the current `main.css` URL; no Java source hard-codes a generated URL.

## HTTP behavior

Only `GET` serves an asset. Matching uses the ASCII raw request path exactly;
there is no decoding, normalization, prefix lookup, or filesystem/module lookup.
A nonempty query is rejected, preventing duplicate immutable cache entries.
Unknown fingerprints, private names, alternate encodings, other methods, and
noncanonical paths receive the normal browser 404.

Successful public asset responses include:

```text
Cache-Control: public, max-age=31536000, immutable
Content-Type: <fixed indexed media type>
X-Content-Type-Options: nosniff
Cross-Origin-Resource-Policy: same-origin
```

Content length is exact. Hash-named URLs eliminate revalidation requests; ETags
are unnecessary. The deployment reverse proxy remains responsible for Brotli or
gzip compression. HTML decides whether future images are lazy-loaded and which
WebP/AVIF variants are offered; the asset pipeline does not invent responsive
variants.

Private assets have no URL and no cache policy. A personalized tracker download
will render from the private `.mjs` template and use `Cache-Control: no-store`.

## Verification

Build tests prove path, count, per-file, total-size, UTF-8, format signature,
raster rejection, SVG safety, actionable errors, deterministic ordering, copy,
index, and fingerprint behavior.

Application tests prove eager loading, hash verification, logical lookup,
private isolation, exact route matching, MIME types, immutable caching, security
headers, and useful 404 behavior for stale or unknown asset URLs.

Production verification adds `Main --check-assets`. It loads and verifies the
index and `main.css` without server configuration, then exits zero. The
production mise task invokes that command with the linked runtime, proving jlink
retained usable resources rather than merely compiling them.

Black-box HTTP tests cover raw-path and query rejection, GET-only routing, exact
length/MIME/cache/security headers, private 404 isolation, and stale
fingerprints. Build tests cover missing or corrupted copied outputs and stale
cache prevention. Full verification remains warning-free with assertions
enabled.

## Scope

This design moves `main.css` out of Java and establishes the runtime asset
pipeline. It does not add Datastar, custom fonts, branding artwork, tracker
functionality, a bundler, proxy compression, or later roadmap UI behavior.
