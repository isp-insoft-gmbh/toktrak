---
id: TT-PLAN-3Q6A7MPP
type: plan
title: Implement JStachio templating, critique revision
spec: TT-SPEC-GUMOSCIP
phase: TT-PHASE-H0TZ1VHT
status: done
---

This plan supersedes `TT-PLAN-AJUX9158` after its blocking critique.

## 1. Pin the real JStachio surface

- Add `io.jstach:jstachio-annotation@1.3.7` and `io.jstach:jstachio@1.3.7` to
  production dependencies and `io.jstach:jstachio-apt@1.3.7` to build
  dependencies. The runtime modules are `io.jstach.jstache` and
  `io.jstach.jstachio`; the build-only processor module is `io.jstach.apt`.
- Reject automatic runtime modules. Assert the processor manifest release is
  `1.3.7` while explicitly documenting its known module-version value,
  `1.4.0-SNAPSHOT`.
- Pass only supported processor options: the isolated template root and
  `-Ajstache.incremental=true`. Pre-encoding is already the default; do not pass
  the unsupported `jstache.pre_encode_disable` option.
- Prove every generated renderer implements `Template.EncodedTemplate`, reports
  UTF-8, and emits no template catalog or
  `META-INF/services/io.jstach.jstachio.spi.TemplateProvider` file.

## 2. Validate and isolate template inputs

- Establish `sources/toktrak/templates` as the sole template root. Accept only
  deterministic lowercase relative `.mustache` files with regular parents,
  strict UTF-8, no BOM, LF line endings, and explicit file-count, per-file, and
  aggregate-byte limits.
- Copy only the validated inventory into a fresh staging sandbox and pass that
  copy as `jstache.resourcesPath`; templates never enter assets, class output,
  linked inputs, or runtime images.
- Use the JDK compiler tree API to inspect authored `@JStache` annotations.
  Require an explicit literal `path`, canonical containment in the validated
  inventory, and one expected model per top-level template. Do not trust
  `jstache.resourcesPath` as a sandbox.
- Use no partials or parent/block layouts for these three small pages. Reject
  partial, inheritance, dynamic-name, raw-variable, and delimiter-change syntax
  unequivocally; add static partial support only when real duplication warrants
  the additional reference validation.

## 3. Build generated output as one verified artifact

- Compile from a `target/classes`-shaped staging layout to remove JStachio's
  custom-output warning without suppression. Keep module classes and bounded
  `target/generated-sources/annotations` beneath one compilation artifact.
- Invoke the pinned processor explicitly with Error Prone and retain
  `-Xlint:all -Werror`. Validate generated paths, counts, sizes,
  renderer/source/ class counterparts, service-file absence, and stale or
  unexpected files.
- Keep two distinct integrity layers:
  - an input key over authored Java, canonical template bytes, processor
    options, tool/platform identity, and all dependency JAR bytes;
  - a post-build manifest containing path, length, and SHA-256 for every class,
    generated source, and generated metadata file.
- Admit a cache hit only when the input key matches and every output hash and
  inventory entry matches. Missing, extra, partial, or same-length-corrupt
  output forces regeneration.
- Refactor shared artifact publication instead of deleting the current output:
  finish and validate staging, atomically rename any complete output to a unique
  sibling backup, atomically rename staging to output, validate it, then delete
  the backup. Both moves remain on one filesystem.
- On Windows, where a non-empty directory cannot be atomically replaced, hold
  the build lock across both renames. If publication fails, restore the backup;
  startup recovery restores a sole valid backup before deleting stale stages.
  Never invalidate the old marker first.
- Add failpoints before and after backup, publication, validation, restoration,
  and cleanup. Tests must prove failures during invalidation, deletion, and
  publication preserve or recover the last complete artifact. `clean` removes
  generated sources, stages, and backups.

## 4. Keep authored tooling separate

- Formatting continues to select repository-authored Java only. Generated Java
  is never formatted, snapshotted, Refaster-patched, mutation-tested, or counted
  by JaCoCo.
- Run Refaster with annotation processing disabled and patch the already-built
  renderer classes into `toktrak` with `--patch-module`; pass only authored
  source files as patch targets. Add a real JPMS compilation test proving direct
  renderer references resolve under this setup.
- Enumerate PIT targets from authored production classes instead of `toktrak.*`.
  Feed JaCoCo a bounded filtered copy containing only authored class
  counterparts. Test each exclusion boundary.

## 5. Add bounded view models and pages

- Add immutable `HomeView`, `TokenListView`, and `CreatedTokenView` records in
  the HTTP presentation package. Defensively copy collections and expose only
  bounded display strings, booleans, and Java-validated root-relative URLs.
- Add `home.mustache`, `tokens.mustache`, and `created-token.mustache` using
  only escaped variables and ordinary/inverted sections. Keep `ErrorPage` in
  bounded Java and document its common-mode-failure role with `///` Markdown
  Javadoc.
- Replace `Router` page concatenation with direct generated renderer calls; do
  not use `JStachio.render`, service loading, registries, reflection, runtime
  template lookup, or JPMS openings.

## 6. Bound token-list rendering without valid-state failures

- Parse an optional positive `page` query parameter and render at most 100
  tokens per page, newest first with UUID tie-breaking. Reject duplicate,
  malformed, zero, overflowed, and out-of-range page values as bad requests.
- Add a bounded projection/service page query returning at most 100 immutable
  rows plus total count. The view model carries validated previous/next URLs and
  an empty state; no valid owner state can produce a 4 MiB token page or a
  permanent `/tokens` 500 response.
- Test empty, first, middle, last, and shrinking-last-page states, including the
  global 100,000-token projection ceiling.

## 7. Render completely before headers

- Add a 4 MiB bounded copying byte sink to `HttpSupport`. Render directly into
  it, reject overflow during writes, freeze the final bytes, then set security
  headers and exact `Content-Length`. Never retain or mutate processor-owned
  arrays.
- Keep the existing string HTML path only for `ErrorPage`. Rendering or model
  failure before headers routes to that fallback with no partial response;
  transport failure after headers only closes the exchange.
- Represent template failures with a stackless, causeless carrier containing
  only hardcoded route metadata and a normalized failure kind. Never retain a
  model, renderer, source exception, stack trace, or arbitrary
  `Throwable.getMessage()`.
- Dev diagnostics may show allowlisted template/model/renderer identifiers and
  either `renderer_failure` or `output_limit`; production remains generic. All
  fields and the complete fallback remain independently bounded.

## 8. Preserve one-time token delivery

- Split token creation into preparation and commit. Preparation validates the
  owner/label and creates an immutable pending credential; render its one-time
  plaintext into the bounded response before submitting the creation event.
- Commit only after rendering succeeds, then send the already-frozen bytes. A
  render failure creates no token; a write failure sends no plaintext and
  persists no token.
- Do not compensate with revocation: response failure after commit has ambiguous
  delivery, and a second write may also fail. Tests prove pre-commit render
  failure issues neither create nor revoke commands, covering the revocation-
  failure hazard by construction.

## 9. Configure IDEs without packaging templates

- Configure Eclipse and IntelliJ APT with the pinned processor path, supported
  options, and IDE-owned generated-source directories.
- Link the template directory only for processor path resolution. Do not mark it
  as a Java source or resource root; explicitly exclude `.mustache` from normal
  resource copying.
- Include processor JARs, options, and template bytes in IDE fingerprints. Test
  generated metadata for APT linkage, generated roots, and the absence of any
  template resource/output entry. Document IDE clean/rebuild for template-only
  edits.

## 10. Make skill validation reproducible

- Add focused `mustache` and `toktrak-jstachio` skills with official references,
  version-matched examples, and TokTrak restrictions.
- Add an offline authoritative `Build.java` check for exact skill directories,
  frontmatter name/description rules, local links, example files, pinned version
  references, and an allowlist of HTTPS official-source URLs.
- Include both complete skill trees in the cached build-test input fingerprint
  so any edit reruns validation. Keep live network availability outside
  reproducible verification; perform one explicit bounded link check during
  implementation and record failures separately from local correctness.

## 11. Prove linked-runtime behavior

- Add a production self-check that directly renders a small encoded `HomeView`
  through its generated renderer and verifies UTF-8 bytes, escaping, and the 4
  MiB boundary before exit.
- Run that self-check from the linked image in `mise run prod`. Inspect the
  linked module/image and fail if it contains `.mustache`, generated Java,
  template catalogs, service registration, automatic modules, or reflection
  openings.

## 12. Tests and verification

- Extend `BuildTest` for coordinates/modules/options, template UTF-8/path/LF/
  syntax checks, AST path containment, processor diagnostics, deterministic
  generation, separate input/output integrity, same-length corruption,
  recoverable publication, clean behavior, tooling exclusions, IDE metadata,
  skills, and runtime-file absence.
- Add encoded Selfie snapshots for home production/development, token-list empty
  and paged representative states, and created-token output. Inspect every
  golden diff and commit neither update markers nor generated Java.
- Add focused renderer/HTTP tests for hostile text, URL validation, defensive
  copies, exact bytes/headers, 4 MiB boundary/overflow, no partial response,
  fallback independence, diagnostic isolation, and prepare/render/commit token
  behavior.
- Iterate with focused tests and PIT for changed authored rendering/error/token
  paths; inspect survivors. Run authored-file formatting, `mise run verify`,
  `mise run prod`, and `mise run coverage`.
- For the clean CI gate, create an alternate Git index from `HEAD`, add only an
  explicit implementation-path allowlist, write a temporary detached commit, and
  attach a temporary worktree to that commit. Assert its changed-path list
  equals the allowlist and excludes all unrelated `.system` files; run
  `mise run ci`, then remove the worktree, alternate index, and dangling commit
  reference without touching the user's branch, index, or worktree.

No runtime template files, custom Mustache parser, unescaped output, dynamic
partials, generated service registry, reflective lookup, committed generated
Java, `ErrorPage` templating, or valid-state token-list failure.
