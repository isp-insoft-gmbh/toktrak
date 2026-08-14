---
id: TT-SPEC-GUMOSCIP
type: spec
title: JStachio HTML templating
research:
  - TT-RESEARCH-N__SD7RS
---

## Intent

Move ordinary browser HTML and Datastar fragments from Java concatenation into
type-checked external Mustache templates. Preserve TokTrak's bounded responses,
strict escaping, linked JPMS runtime, deterministic build, and simple direct
rendering.

## Scope

Migrate the home, tracker-token list, and created-token pages. New browser pages
and Datastar patches use the same system.

Keep `ErrorPage` as an independent bounded Java fallback so template or output
failure cannot break its own error response. Its Markdown Javadoc explains this
common-mode-failure boundary.

Use dedicated immutable view-model records. Do not annotate projection,
identity, auth, event, or storage models.

## Templates

All templates are lowercase `.mustache` files below `sources/toktrak/templates`.
They are compile-time inputs, not public/private runtime assets. Every model
uses an explicit relative `@JStache(path = "...")`.

Inline `@JStache(template = "...")` is exceptional: tiny single-purpose non-HTML
output only, without sections or partials, when an external file would reduce
clarity. The use is justified and snapshotted.

Use escaped variables, sections, inverted sections, static partials, and static
parent/block layouts. Unescaped variables, delimiter changes, dynamic or
recursive partials, and template-generated templates are forbidden.

Prefer view-model methods over lambdas. Typed `@JStacheLambda` is allowed only
for reusable view transformations that retain normal escaping and compile-time
typing. Raw, unescaped, or template-generating lambdas are forbidden.

Dynamic URLs remain validated in Java. Templates never create dynamic scripts,
styles, event attributes, tag names, or attribute names.

## Rendering

Use standard `JSTACHIO` generation with JStachio's maintained HTML escaping and
pre-encoding enabled. Pin the runtime, annotations, and annotation processor to
one release.

Production calls each generated renderer directly and writes its pre-encoded
UTF-8 output. Do not use `JStachio.render`, reflective lookup, runtime template
loading, or JPMS package openings.

Complete rendering into a bounded encoded buffer before sending headers. A page
or patch may not exceed 4 MiB; normal query and view-model limits remain much
smaller. Send exact content length. Never mutate reusable pre-encoded arrays.

Rendering failure before headers uses `ErrorPage`; no partial HTML is sent. In
dev auth, the error page may show bounded template path, model type, renderer
type, and exception message. It never shows model values or stack traces.
Production remains generic.

## Build

`Build.java` is authoritative. It:

- resolves explicit JStachio runtime and build-only processor inputs;
- runs JStachio APT with Error Prone without warnings;
- supplies the template root and a dedicated generated-source output;
- includes templates, processor version, generated sources, and classes in
  fingerprints and cache integrity;
- rejects missing, stale, partial, or unexpected generated output;
- removes generated output during `clean` and never commits it;
- compiles generated code normally but excludes it from formatting, Refaster,
  coverage, PIT, and authored-source conventions;
- validates template paths, strict UTF-8, counts, per-file size, and total size;
- adds only unequivocal regex checks; it does not implement Mustache parsing or
  ambiguous policy matching;
- packages and verifies the linked runtime with explicit modules only.

Template changes always regenerate affected renderers. Build failure never
publishes a partial generated tree. The processor's custom-output warning is
resolved by supported layout/configuration, never suppressed.

## IDEs

Generated Eclipse and IntelliJ metadata enables JStachio APT on a best-effort
basis with IDE-owned generated directories and the template root. IDE clean/
rebuild may be required after template-only changes. IDE plugins may improve
this later; Build.java correctness remains authoritative.

## Tests

Selfie snapshots materialize encoded output through a typed camera that decodes
UTF-8. Snapshot every top-level page and Datastar fragment with representative,
empty, and conditional states where applicable. Golden files contain rendered
HTML, never generated Java.

Focused assertions separately prove hostile text escaping, URL validation,
forbidden raw output, output bounds, exact HTTP bytes/length, dev/production
error separation, and no partial response on rendering failure.

Build tests prove processor invocation, template discovery, generated-source
publication, cache invalidation, warning-free failure diagnostics, clean
behavior, IDE metadata, and missing/invalid template rejection. Production
verification renders through the linked runtime.

## Skills

Add two repo-local skills:

- `mustache` covers the formal Mustache specification, whitespace, variables,
  sections, inverted sections, partials, parents, and blocks.
- `toktrak-jstachio` covers version-matched JStachio docs, external templates,
  view models, generated renderers, pre-encoded output, Java extensions, typed
  lambdas, snapshots, build/IDE behavior, and TokTrak restrictions.

Both skills link to current official online sources and keep volatile API detail
in references rather than activation instructions. Validate links, skill
structure, activation, and examples.

## Acceptance

- Ordinary browser HTML no longer uses Java concatenation; `ErrorPage` remains
  independent and documented with `///` Markdown Javadoc.
- Template/model mistakes fail compilation with useful template locations.
- Normal variables use JStachio escaping; forbidden constructs are absent.
- Encoded snapshots and HTTP tests cover every materialized template state.
- Template edits invalidate build caches and regenerate disposable source.
- Eclipse and IntelliJ receive working best-effort APT metadata.
- A clean checkout passes verification and builds the assertion-enabled linked
  production runtime without warnings, reflection openings, automatic modules,
  runtime template files, or generated-source drift.

No custom template engine, Mustache parser, runtime hot reload, committed
generated Java, generated-code mutation testing, or general rendering framework.
