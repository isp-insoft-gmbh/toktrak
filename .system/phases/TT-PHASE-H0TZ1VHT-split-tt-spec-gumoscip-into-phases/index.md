---
id: TT-PHASE-H0TZ1VHT
type: phase
title: JStachio templating rollout
spec: TT-SPEC-GUMOSCIP
status: done
---

## Outcome

TokTrak renders ordinary browser pages and Datastar fragments from typed,
pre-encoded external Mustache templates while preserving bounded HTTP behavior,
independent error fallback, deterministic builds, and linked-runtime integrity.

## Steps

### 1. Build and guidance foundation

- Pin JStachio runtime/annotations/APT and integrate warning-free annotation
  processing with authoritative generated-source publication, fingerprints,
  cache checks, clean behavior, and template validation.
- Add current-source Mustache and TokTrak JStachio skills.
- Extend Eclipse and IntelliJ metadata with best-effort IDE-owned APT output.

### 2. Bounded rendering boundary

- Add dedicated view models and direct generated-renderer integration.
- Buffer pre-encoded UTF-8 output within the 4 MiB limit before headers and send
  exact lengths.
- Route pre-header failures through independent `ErrorPage`; add bounded dev
  template diagnostics and Markdown Javadoc for the fallback boundary.
- Add the Selfie encoded-output camera and focused escaping, limit, failure, and
  HTTP assertions.

### 3. Page migration and verification

- Move home, tracker-token list, and created-token HTML to external templates.
- Snapshot representative, empty, and conditional materializations.
- Verify template-change regeneration, IDE metadata, full local gates, and the
  linked production runtime.

## Dependencies

- Existing runtime asset, HTTP response, `ErrorPage`, Selfie, build artifact,
  IDE metadata, and linked-runtime behavior remain stable boundaries.
- Step 2 depends on generated renderers from step 1. Step 3 depends on the
  bounded rendering and snapshot boundary from step 2.

## Risks

- JStachio custom-output warnings or processor ordering could break `-Werror`.
- Failed or cached compilation could expose stale generated classes.
- Pre-encoded buffering could bypass output limits or mutate reusable bytes.
- IDEs may require clean/rebuild after template-only edits.
- Template failures must never compromise the fallback error renderer.

## Done criteria

- Build and skills acceptance passes before page migration.
- Every migrated response uses direct generated pre-encoded rendering and
  remains bounded before headers.
- Snapshots and focused assertions cover all materialized states and security
  boundaries.
- Generated code stays disposable and excluded from authored-code tooling.
- Verification and production linking pass without warnings, automatic modules,
  reflection openings, runtime templates, partial artifacts, or dirty generated
  output.
