# Runtime Assets Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use /skill:executing-plans to
> implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for
> tracking.

**Goal:** Move `main.css` into a validated JPMS resource pipeline with eager
runtime loading, fingerprinted immutable URLs, exact HTTP routing, and linked
runtime verification.

**Architecture:** `tools/Build.java` validates and copies a bounded asset tree
into the exploded `toktrak` module and generates a canonical index. A new
`Assets` class eagerly verifies that index and every resource before HTTP
binding; `Router` serves exact fingerprinted public URLs while private resources
remain unreachable.

**Tech Stack:** Java 26, JPMS, JDK `HttpServer`, `javac`, `jlink`, JUnit 6, mise

**Roadmap:** `docs/super/roadmaps/2026-07-13-toktrak-roadmap.md`

**Phase:** Phase 1: Durable Server Core

**Research:** `docs/research/2026-08-10-jpms-runtime-assets.md`

---

## File structure

- Create `sources/toktrak/assets/public/main.css`: production CSS bytes.
- Modify `tools/Build.java`: validation, canonical index, copy, cache, and jlink
  fingerprints.
- Modify `tests/tools/BuildTest.java`: deterministic build-pipeline tests.
- Create `sources/toktrak/toktrak/http/Assets.java`: eager runtime loader and
  immutable lookup maps.
- Create `tests/toktrak.tests/toktrak/tests/AssetsTest.java`: index/resource
  validation tests.
- Modify `App`, `Main`, `Router`, `ErrorPage`, and `HttpSupport`: startup
  loading, fingerprinted references, exact serving, and linked-image check.
- Modify affected HTTP/Main tests.
- Delete `tests/toktrak.tests/toktrak/tests/JlinkSmokeTest.java`: replace the
  optional placeholder with the real linked-image asset check.

## Task 1: Validate and index source assets

**Files:**

- Modify: `tests/tools/BuildTest.java`
- Modify: `tools/Build.java`

- [ ] **Step 1: Add build-test calls**

Add these calls in `BuildTest.main` after the existing tree/hash tests:

```java
given_validRuntimeAssets_when_buildingBundle_then_returnsCanonicalIndex();
given_invalidRuntimeAssets_when_buildingBundle_then_returnsActionableErrors();
given_runtimeAssetBounds_when_buildingBundle_then_rejectsExcess();
given_runtimeAssetBundle_when_writingModule_then_copiesAndVerifiesResources();
```

- [ ] **Step 2: Add valid-bundle coverage**

Create a temporary tree with:

```text
public/main.css       body{}
public/app.js         export{};
public/logo.webp      RIFF....WEBP
public/photo.avif     ....ftypavif
public/font.woff2     wOF2
public/logo.svg       <svg xmlns="http://www.w3.org/2000/svg"><path d="M0 0"/></svg>
private/tracker.mjs   export{};
```

Call `Build.assetBundleForTest(root)` and assert:

- seven records sorted by scope then logical path;
- exact media types from the approved table;
- lowercase 64-character SHA-256 values;
- index starts `toktrak-assets-v1\n`, uses tabs/LF, has no BOM, and ends in
  exactly one newline;
- repeated bundle creation returns identical index bytes and fingerprint.

Use these minimal binary fixtures:

```java
byte[] webp = "RIFF\0\0\0\0WEBP".getBytes(StandardCharsets.ISO_8859_1);
byte[] avif = "\0\0\0\20ftypavif".getBytes(StandardCharsets.ISO_8859_1);
byte[] woff2 = "wOF2".getBytes(StandardCharsets.ISO_8859_1);
```

- [ ] **Step 3: Add actionable rejection coverage**

In one temporary tree at a time, assert failures contain both the rejected path
and remediation:

```text
logo.png; convert it to optimized WebP or AVIF, or use SVG for vector artwork
main.css; save the asset as valid UTF-8
logo.webp; regenerate it as a valid WebP file
logo.avif; regenerate it as a valid AVIF file
font.woff2; regenerate it as a valid WOFF2 file
logo.svg; remove scripts, external references, and unsupported SVG features
Bad.css; rename runtime assets using lowercase ASCII
```

Unsafe SVG fixtures must cover a DOCTYPE, `script`, an `onclick` attribute,
`href`, `style`, `foreignObject`, and an external URL. Each must fail closed.

- [ ] **Step 4: Add bound coverage**

Assert:

- 65 regular files fail with
  `runtime assets exceed 64 files; remove or combine assets`;
- one 4 MiB + 1 byte file fails with the path plus
  `optimize the asset below 4194304 bytes`;
- five 4 MiB files fail with
  `runtime assets exceed 16777216 total bytes; optimize or remove assets`;
- a symbolic file or directory fails with
  `replace the symbolic runtime asset with a regular file or directory`.

Create sparse bound files with `FileChannel.position(...)` and delete only the
created temporary root in `finally` through a small iterative test cleanup
helper.

- [ ] **Step 5: Add copy/cache coverage**

Call:

```java
Build.writeAssetsForTest(bundle, moduleRoot);
assertTrue(Build.assetsMatchForTest(bundle, moduleRoot));
```

Assert exact copied bytes and index. Then independently:

- change `main.css` output bytes;
- delete `assets/index.tsv`;
- add an unindexed output file;

Each state must make `assetsMatchForTest` return false.

- [ ] **Step 6: Run build tests and verify RED**

Run:

```text
mise run test -- tests/tools/BuildTest.java
```

Expected: compilation fails because the asset APIs do not exist.

- [ ] **Step 7: Implement the build asset model**

Add constants:

```java
private static final Path ASSET_SOURCES = APP_SOURCES.resolve("assets");
private static final int ASSET_COUNT_MAX = 64;
private static final int ASSET_BYTES_MAX = 4 * 1024 * 1024;
private static final int ASSET_BYTES_TOTAL_MAX = 16 * 1024 * 1024;
private static final int ASSET_INDEX_BYTES_MAX = 64 * 1024;
private static final Pattern ASSET_PATH =
    Pattern.compile("[a-z0-9][a-z0-9._-]*(?:/[a-z0-9][a-z0-9._-]*)*");
```

Add `java.util.regex.Pattern`, DOM `Node`, `NamedNodeMap`, and required JAXP
imports without suppressing warnings.

Add package-visible nested records:

```java
record AssetSource(
    String scope,
    String logicalPath,
    String mediaType,
    String sha256,
    byte[] bytes) {
  AssetSource {
    assert scope.equals("public") || scope.equals("private");
    assert logicalPath != null && !logicalPath.isBlank();
    assert mediaType != null && !mediaType.isBlank();
    assert sha256.matches("[0-9a-f]{64}");
    bytes = bytes.clone();
  }
}

record AssetBundle(List<AssetSource> assets, byte[] index, String fingerprint) {
  AssetBundle {
    assets = List.copyOf(assets);
    index = index.clone();
    assert assets.size() <= ASSET_COUNT_MAX;
    assert fingerprint.matches("[0-9a-f]{64}");
  }
}
```

Expose only these package-visible test delegates:

```java
static AssetBundle assetBundleForTest(Path root) throws Exception;
static void writeAssetsForTest(AssetBundle bundle, Path moduleRoot) throws Exception;
static boolean assetsMatchForTest(AssetBundle bundle, Path moduleRoot) throws Exception;
```

- [ ] **Step 8: Implement deterministic discovery and index generation**

`assetBundle(root)` must:

1. require a regular `public` directory and allow an absent or regular `private`
   directory;
2. walk at most 100 tree entries without following links;
3. reject every symbolic path and unknown top-level entry;
4. collect regular files, derive scope and `/`-separated logical path;
5. validate the approved path regex, count, file size, and exact total with
   `Math.addExact`;
6. read each bounded file once and reject size changes during reading;
7. derive and validate media type/content;
8. calculate full lowercase SHA-256;
9. sort by `scope + "\t" + logicalPath` and reject duplicate keys;
10. generate exact canonical index bytes;
11. calculate bundle fingerprint from canonical index followed by each asset's
    scope, logical path, and bytes.

Index records are exactly:

```java
scope
    + "\t"
    + logicalPath
    + "\t"
    + bytes.length
    + "\t"
    + mediaType
    + "\t"
    + sha256
    + "\n"
```

Fail if index bytes exceed 64 KiB.

- [ ] **Step 9: Implement format validation**

Use one extension switch returning the fixed media type. Decode CSS, JS, MJS,
and SVG with a UTF-8 decoder configured with `CodingErrorAction.REPORT`.

Signatures are exact:

```text
WebP: bytes 0..3 = RIFF and 8..11 = WEBP
AVIF: bytes 4..7 = ftyp and avif or avis appears in bytes 8..31
WOFF2: bytes 0..3 = wOF2
```

For SVG, configure `DocumentBuilderFactory` with:

```java
factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
factory.setXIncludeAware(false);
factory.setExpandEntityReferences(false);
```

Any unsupported feature/attribute is a build failure naming the SVG and advising
use of a supported JDK 26 XML parser. Iteratively inspect at most 100,000 nodes
and attributes. Enforce the exact deny list from the design, SVG namespace only,
and no processing instructions. Permit only the root namespace declaration
`xmlns="http://www.w3.org/2000/svg"`; exempt that declaration from URI-value
rejection and reject every other namespace declaration.

- [ ] **Step 10: Implement copy and output verification**

`writeAssets` deletes only `<moduleRoot>/assets`, writes each file under
`assets/<scope>/<logicalPath>` with `CREATE_NEW`, writes `assets/index.tsv`,
then calls `assetsMatch` and fails if verification is false.

`assetsMatch` must return false for a missing/symbolic/extra output, wrong
index, wrong size, or wrong SHA-256. Compare the complete output asset path set
with the index plus indexed resources; never accept prefix matches.

- [ ] **Step 11: Run build tests and verify GREEN**

Run:

```text
mise run test -- tests/tools/BuildTest.java
```

Expected: build-tool tests pass.

## Task 2: Integrate assets with compile and jlink caching

**Files:**

- Modify: `tools/Build.java`
- Modify: `tests/tools/BuildTest.java`

- [ ] **Step 1: Add compile/runtime fingerprint tests**

Add package-visible delegates with these intended signatures, but write tests
before implementing them:

```java
static String applicationCompilationFingerprintForTest(
    List<Path> sources,
    List<Path> dependencyDirectories,
    List<String> arguments,
    Path assetRoot)
    throws Exception;

static String applicationRuntimeFingerprintForTest(Path appModule) throws Exception;
```

Create two otherwise identical source trees whose `main.css` differs by one
byte. Assert `applicationCompilationFingerprintForTest` differs while Java
sources, dependencies, and arguments remain identical.

Create two otherwise identical exploded app-module directories containing the
same `module-info.class` and different `assets/public/main.css` bytes. Assert
`applicationRuntimeFingerprintForTest` differs. Also write the first bundle into
a module output, assert a match, then assert it does not match the second
bundle.

The production `compile()` and runtime `fingerprint(...)` methods must call the
same private helpers used by these delegates; tests may not duplicate the
fingerprint algorithm.

- [ ] **Step 2: Run build tests and verify RED**

Run:

```text
mise run test -- tests/tools/BuildTest.java
```

Expected: compilation fails because both fingerprint delegates are absent.

- [ ] **Step 3: Integrate compilation**

At the beginning of `compile()`, after dependencies and before fingerprinting:

```java
AssetBundle assetBundle = assetBundle(ASSET_SOURCES);
```

Factor `applicationCompilationFingerprint(...)` around the existing
`compilationFingerprint(...)`; it appends
`"assets=" + assetBundle.fingerprint()` to a defensive copy of fingerprint
arguments. Use this helper from both `compile()` and the test delegate. Require
`APP_MODULE.resolve("assets/index.tsv")` in cache outputs and make a cache hit
conditional on `assetsMatch(assetBundle, APP_MODULE)`.

On a cache miss, run javac first, then:

```java
writeAssets(assetBundle, APP_MODULE);
```

Only then write `.compile-fingerprint`. Asset validation therefore runs before
cached or fresh compilation, while resource output mutation occurs only after a
successful javac.

- [ ] **Step 4: Integrate jlink fingerprinting**

Change the app-module runtime fingerprint from:

```java
updateTree(digest, APP_MODULE, ".class");
```

to every regular module file:

```java
updateTree(digest, APP_MODULE, "");
```

Before using an empty suffix, update `updateTree` to filter
`Files::isRegularFile` before suffix matching; otherwise directories would be
passed to the file hasher. Factor the app-module tree contribution into a helper
that accepts the app-module path; production passes `APP_MODULE` and the test
delegate passes its temporary module. It continues hashing each relative name
and bytes in sorted order.

- [ ] **Step 5: Run clean build tests**

Run:

```text
mise run clean
mise run test -- tests/tools/BuildTest.java
```

Expected: assets are regenerated after clean and build tests pass.

## Task 3: Add eager runtime asset loading

**Files:**

- Create: `sources/toktrak/assets/public/main.css`
- Create: `sources/toktrak/toktrak/http/Assets.java`
- Create: `tests/toktrak.tests/toktrak/tests/AssetsTest.java`

- [ ] **Step 1: Move CSS source bytes**

Create `main.css` with the exact current production CSS, unchanged and minified:

```css
.environment-banner{background:#b00020;color:white;padding:.5rem;font-weight:800}.error-page{font:16px/1.4 ui-monospace,monospace;max-width:760px;margin:48px auto;border:4px solid #111;padding:28px;background:#fff;color:#111}.error-page h1{font-size:64px;line-height:1;margin:24px 0 8px}.error-page p{font-family:system-ui,sans-serif}.error-page dl{display:grid;grid-template-columns:max-content 1fr;border-top:3px solid #111;margin-top:28px}.error-page dt,.error-page dd{padding:10px;border-bottom:2px solid #111;margin:0}.error-page dt{font-weight:800}.error-page code{overflow-wrap:anywhere}
```

- [ ] **Step 2: Write `AssetsTest`**

Add tests named:

```text
given_packagedAssets_when_loadingModule_then_verifiesAndIndexesAssets
given_corruptIndex_when_loadingAssets_then_rejectsIndex
given_missingOrChangedResource_when_loadingAssets_then_rejectsResource
given_privateAsset_when_loadingAssets_then_exposesNoPublicUrl
given_staleFingerprint_when_lookingUpPublicAsset_then_returnsEmpty
```

The packaged-assets test calls `Assets.load()`, asserts `main.css` maps to:

```text
/assets/main.<32 lowercase hex>.css
```

and asserts the loaded bytes equal the committed file content.

For malformed cases, use:

```java
Assets.loadForTest(indexBytes, Map<String, byte[]> resources)
```

Supply exact in-memory resource names such as `assets/public/main.css`. Test
wrong version/header, BOM, CRLF, missing final newline, extra fields,
unsorted/duplicate records, noncanonical lengths/hashes, wrong size, and wrong
SHA-256. Keep each test's inputs below 64 KiB and 64 entries.

- [ ] **Step 3: Run asset tests and verify RED**

Run:

```text
mise run test -- tests/toktrak.tests/toktrak/tests/AssetsTest.java
```

Expected: compilation fails because `Assets` does not exist.

- [ ] **Step 4: Implement `Assets` API**

Create a public final class with:

```java
public static Assets load();
public static Assets loadForTest(byte[] index, Map<String, byte[]> resources);
public String publicUrl(String logicalName);
public byte[] privateBytes(String logicalName);
Optional<PublicAsset> publicAsset(String rawPath);
record PublicAsset(String mediaType, byte[] body) {}
```

Keep `publicAsset` and `PublicAsset` package-private for `Router`. Clone private
bytes returned across the public boundary. Public response arrays remain
unexposed outside `toktrak.http` and are never mutated. Unknown logical names
throw `IllegalArgumentException("runtime asset not found: " + logicalName)`;
unknown public request paths return `Optional.empty()`.

`load()` reads `assets/index.tsv` and indexed resources from:

```java
Assets.class.getModule().getResourceAsStream(resourceName)
```

using try-with-resources. A missing resource throws an actionable
`IllegalStateException` naming the resource and advising `mise run clean`.

- [ ] **Step 5: Implement canonical parsing and eager verification**

Duplicate the runtime safety bounds from the design. Parse strict UTF-8 with no
BOM, exact LF/final newline, exact header, five tab-separated fields, exact
scope/media/path/hash grammars, strict sort order, count, per-file, and total
bounds.

Read each resource with a limit of indexed length + 1, require exact length, and
verify SHA-256 with `MessageDigest.isEqual` on decoded hex bytes. Generate
public URLs by inserting the first 32 hash characters before the final extension
while preserving logical subdirectories. Reject duplicate logical names or
generated URLs.

Build immutable maps with `Map.copyOf`. Fail startup on any inconsistency.

- [ ] **Step 6: Run asset tests and verify GREEN**

Run:

```text
mise run test -- tests/toktrak.tests/toktrak/tests/AssetsTest.java
```

Expected: all `AssetsTest` tests pass.

## Task 4: Serve exact fingerprinted asset URLs

**Files:**

- Modify: `sources/toktrak/toktrak/App.java`
- Modify: `sources/toktrak/toktrak/http/Router.java`
- Modify: `sources/toktrak/toktrak/http/ErrorPage.java`
- Modify: `sources/toktrak/toktrak/http/HttpSupport.java`
- Modify: `tests/toktrak.tests/toktrak/tests/HealthModeTest.java`
- Modify: `tests/toktrak.tests/toktrak/tests/HttpAdmissionTest.java`
- Modify: `tests/toktrak.tests/toktrak/tests/ErrorPageTest.java`

- [ ] **Step 1: Write HTTP asset tests**

Update `HealthModeTest` to load the root page, extract the quoted stylesheet
URL, and assert it matches `/assets/main.<32 lowercase hex>.css`. Request that
URL and assert:

```text
status 200
exact committed CSS bytes
Content-Type: text/css; charset=utf-8
Cache-Control: public, max-age=31536000, immutable
Cross-Origin-Resource-Policy: same-origin
X-Content-Type-Options: nosniff
Content-Length equals UTF-8 byte length
```

Add named tests proving these all return the normal useful browser 404:

```text
/assets/main.css
/assets/main.<wrong hash>.css
/assets/main.<valid hash>.css?cache-bust=1
/assets/main.<valid hash>.css?
GET percent-encoded, dot-segment, or duplicate-slash alternatives
POST exact fingerprinted URL
/assets/private/tracker.mjs
```

For canonicalization, add
`given_noncanonicalAssetTargets_when_requestingRawHttp_then_returnsNotFound`.
Use `java.net.Socket` rather than URI/HTTP client builders, which may normalize
targets before transmission. Send exact bounded ASCII requests:

```text
GET <target> HTTP/1.1\r\n
Host: 127.0.0.1\r\n
Connection: close\r\n
\r\n
```

Read at most 128 KiB, require EOF, and assert status 404 for:

```text
/assets/%6dain.<hash>.css
/assets/./main.<hash>.css
/assets//main.<hash>.css
/assets/main.<hash>.css?cache-bust=1
/assets/main.<hash>.css?
```

A control request with the exact generated raw path must return 200 through the
same socket helper, proving the harness did not alter the target.

- [ ] **Step 2: Update pure error-page tests**

Add a stylesheet URL parameter to every `ErrorPage.render` call. Assert the
result contains the escaped exact URL and rejects blank or over-256-character
stylesheet URLs.

- [ ] **Step 3: Run focused tests and verify RED**

Run:

```text
mise run test -- tests/toktrak.tests/toktrak/tests/HealthModeTest.java tests/toktrak.tests/toktrak/tests/ErrorPageTest.java
```

Expected: stable URL routing and old renderer signatures fail.

- [ ] **Step 4: Load assets before binding**

In `App.start`, after config validation and before acquiring `DataLock`:

```java
Assets assets = Assets.load();
```

Pass the same instance into `Router`. Update the direct `Router` construction in
`HttpAdmissionTest` with `Assets.load()`.

Change the `Router` constructor to require non-null `Assets`. Remove `MAIN_CSS`
and the stable `/assets/main.css` branch.

- [ ] **Step 5: Add exact asset routing**

Before application routes:

```java
String rawPath = exchange.getRequestURI().getRawPath();
if (exchange.getRequestMethod().equals("GET")
    && exchange.getRequestURI().getRawQuery() == null) {
  var asset = assets.publicAsset(rawPath);
  if (asset.isPresent()) {
    HttpSupport.asset(exchange, asset.get().mediaType(), asset.get().body());
    return;
  }
}
```

No path transformation occurs. Nonmatching requests fall through to the normal
browser/API routing.

Root HTML uses `assets.publicUrl("main.css")`.

- [ ] **Step 6: Add immutable asset response support**

Add:

```java
public static void asset(HttpExchange exchange, String contentType, byte[] body)
    throws IOException {
  assert exchange != null;
  assert contentType != null && !contentType.isBlank();
  assert body != null;
  exchange.getResponseHeaders().set(
      "Cache-Control", "public, max-age=31536000, immutable");
  exchange.getResponseHeaders().set("Cross-Origin-Resource-Policy", "same-origin");
  send(exchange, 200, contentType, body);
}
```

Keep existing CSP, framing, referrer, and nosniff headers from `send`.

- [ ] **Step 7: Parameterize `ErrorPage` stylesheet URL**

Add `String stylesheetUrl` before the failure/debug parameters, validate it as a
nonblank maximum-256-character value, and HTML-escape it in the link `href`.
`Router.respondError` passes `assets.publicUrl("main.css")`.

- [ ] **Step 8: Run focused tests and verify GREEN**

Run:

```text
mise run test -- tests/toktrak.tests/toktrak/tests/AssetsTest.java tests/toktrak.tests/toktrak/tests/HealthModeTest.java tests/toktrak.tests/toktrak/tests/HttpServerTest.java tests/toktrak.tests/toktrak/tests/HttpAdmissionTest.java tests/toktrak.tests/toktrak/tests/ErrorPageTest.java
```

Expected: all selected tests pass.

- [ ] **Step 9: Run PIT for changed application code**

Run:

```text
mise run pit --history -- sources/toktrak/toktrak/http/Assets.java sources/toktrak/toktrak/http/Router.java sources/toktrak/toktrak/http/ErrorPage.java sources/toktrak/toktrak/http/HttpSupport.java
```

Inspect survivors and improve focused tests when they expose missing required
behavior. If PIT history errors or results are inconsistent, delete
`output/pit.history` and rerun without `--history`.

## Task 5: Prove linked-runtime inclusion

**Files:**

- Modify: `sources/toktrak/toktrak/Main.java`
- Modify: `tests/toktrak.tests/toktrak/tests/MainTest.java`
- Modify: `tools/Build.java`
- Delete: `tests/toktrak.tests/toktrak/tests/JlinkSmokeTest.java`

- [ ] **Step 1: Write CLI asset-check test**

Add:

```java
@Test
void given_checkAssetsOption_when_runningMain_then_verifiesPackagedAssets() throws Exception {
  // capture stdout using the existing helper shape
  Main.main(new String[] {"--check-assets"});
  assertEquals("TokTrak assets ok: 1" + System.lineSeparator(), capturedOutput);
}
```

Refactor only the existing stdout-capture duplication into one private helper if
needed. Keep both tests independently named.

- [ ] **Step 2: Run Main tests and verify RED**

Run:

```text
mise run test -- tests/toktrak.tests/toktrak/tests/MainTest.java
```

Expected: `--check-assets` reaches config parsing and fails.

- [ ] **Step 3: Implement `--check-assets`**

Before `App.start`:

```java
if (args.length == 1 && args[0].equals("--check-assets")) {
  Assets assets = Assets.load();
  assets.publicUrl("main.css");
  System.out.println("TokTrak assets ok: " + assets.publicCount());
  return;
}
```

Add bounded `publicCount()` to `Assets` and import it into `Main`.

- [ ] **Step 4: Replace the placeholder tagged test**

Delete `JlinkSmokeTest.java`. Its optional filesystem check and placeholder
comment are fully replaced by the real linked-runtime command. Zero tagged tests
are already a supported `TestLauncher` outcome.

- [ ] **Step 5: Update production smoke command**

In `jlinkProd`, replace linked `--help` execution with:

```java
List.of("-ea", "-m", "toktrak/toktrak.Main", "--check-assets")
```

The expected linked output is `TokTrak assets ok: 1`.

- [ ] **Step 6: Run production verification**

Run:

```text
mise run prod
```

Expected: jlink builds a new image and its Java executable loads/verifies the
embedded index and `main.css`.

## Task 6: Full verification and human check

- [ ] **Step 1: Format changed Java and Markdown**

Run `mise run fmt` on all changed Java paths, then `mise run verify`.

Expected:

- no formatting, compiler, lint, or static-analysis warnings;
- all build-tool and JUnit tests pass;
- tagged test group contains zero tests without failure.

- [ ] **Step 2: Run production verification again**

Run `mise run prod` and confirm linked asset verification succeeds from cache or
fresh output.

- [ ] **Step 3: Inspect generated module output**

Confirm only these initial resources exist:

```text
output/modules/toktrak/assets/index.tsv
output/modules/toktrak/assets/public/main.css
```

Use the JDK `jimage list output/runtimes/prod/lib/modules` command to confirm
the same two resource paths under module `toktrak`.

- [ ] **Step 4: Commit implementation**

```text
git add tools/Build.java tests/tools/BuildTest.java sources/toktrak/assets/public/main.css sources/toktrak/toktrak/App.java sources/toktrak/toktrak/Main.java sources/toktrak/toktrak/http/Assets.java sources/toktrak/toktrak/http/Router.java sources/toktrak/toktrak/http/ErrorPage.java sources/toktrak/toktrak/http/HttpSupport.java tests/toktrak.tests/toktrak/tests/AssetsTest.java tests/toktrak.tests/toktrak/tests/HealthModeTest.java tests/toktrak.tests/toktrak/tests/HttpServerTest.java tests/toktrak.tests/toktrak/tests/HttpAdmissionTest.java tests/toktrak.tests/toktrak/tests/ErrorPageTest.java tests/toktrak.tests/toktrak/tests/MainTest.java tests/toktrak.tests/toktrak/tests/JlinkSmokeTest.java
git commit -m "feat: package fingerprinted runtime assets"
```

- [ ] **Step 5: Human verification**

Run `mise run dev`, open `/`, `/nope`, and `/debug/error`, and confirm CSS loads
from a hash-named URL. In browser network tools confirm one-year immutable cache
headers, exact CSS MIME type, no CSP violations, and no request to stable
`/assets/main.css`.

This plan intentionally stops at Phase 1: Durable Server Core. Datastar, custom
fonts, branding images, tracker templates, bundling, and later roadmap phases
remain separate work.
