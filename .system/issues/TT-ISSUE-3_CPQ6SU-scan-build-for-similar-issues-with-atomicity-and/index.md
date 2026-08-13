---
id: TT-ISSUE-3_CPQ6SU
type: issue
title: Make generated build artifacts transactional
specs:
  - TT-SPEC-IXJLYK_K
status: done
---

## Symptom

Build fingerprints can remain valid while their artifact trees are partial. A
failed clean removed the development runtime's timezone database but left its
fingerprint; the next dev launch accepted the cache and failed at startup.

## Impact

False cache hits can skip required resolution, compilation, tests, or linking.
Failures then appear later and misleadingly, while some corrupt caches return
success. Recovery requires deleting generated output manually.

Concurrent build commands also delete and rewrite shared argument files and
output trees without one interprocess build lock.

## Evidence

Experiments against `0b8bd38`, followed by deletion and successful rebuild of
each affected cache:

| Cache                      | Removed while fingerprint remained | Observed result                                                  |
| -------------------------- | ---------------------------------- | ---------------------------------------------------------------- |
| Application modules        | `SignedCookie.class`               | `check` returned success and kept the class missing              |
| Production dependencies    | Jackson annotations JAR            | resolver reported cached; compilation failed with module missing |
| Build-tool classes/results | `Build$PitSelection.class`         | compilation and tests reported cached and returned success       |
| Refaster classes           | nested `WindowsOsName` rule class  | Refaster compilation reported cached and kept the class missing  |
| Development runtime        | `lib/tzdb.dat`                     | jlink reported cached; real startup failed loading `tzdb.dat`    |

A prior concurrent `coverage`/`prod` run also failed because one command deleted
`output/args` while the other wrote it.

## Scan findings

- Dependency and linked-runtime caches trust only a matching fingerprint.
- Module, Refaster, and build-test caches check small hand-picked file subsets;
  unlisted classes may be absent.
- Artifacts are produced directly in final directories.
- Stamps are written last, but invalidation depends on recursive deletion.
  Deletion may fail before reaching the stamp, preserving a false commit marker.
- Stamp writes truncate the final file directly rather than publishing by atomic
  rename.
- Test-result stamps can remain valid while their compiled test classes are
  incomplete.
- Coverage, mutation, IDE, and Refaster-application output has no commit marker;
  interrupted runs leave partial output that looks current to humans.
- Fixed argument-file names and invocation-wide `output/args` deletion make
  otherwise independent build commands race.

## General solution

Use one build transaction mechanism for every generated tree:

1. Acquire one interprocess build lock for all output-mutating commands; keep
   formatting independent. A dev server holds the lock for its lifetime.
2. Build into a unique bounded staging sibling on the same filesystem.
3. Validate the staged artifact and record a generated inventory of expected
   relative files and lengths. Avoid full cache-hit hashing.
4. Write the fingerprint/inventory commit marker through a temporary file and
   atomic rename.
5. Before replacing or cleaning an existing artifact, delete its commit marker
   first. Then remove the old tree and atomically rename staging into place.
6. Treat missing, malformed, mismatched, or incomplete markers as cache misses.
   Remove stale staging trees under the build lock.
7. Publish reports and IDE metadata through the same staging path; test-result
   markers depend on the complete compiled-artifact marker.

Do not add per-cache sentinel lists or fallback to partially valid output.

## Resolution

Build output now uses one interprocess lock, same-filesystem staging, atomic
publication, and fingerprinted file-length inventories. Dependencies, compiled
classes, linked runtimes, reports, Refaster output, build tests, and IDE
metadata reject incomplete trees.

## Verification

Add deterministic failpoints around stage creation, validation, marker write,
invalidation, deletion, and publish. After each injected interruption, the next
run must either use the previous complete artifact or rebuild; it must never
report a partial artifact as cached.

Run two output-mutating commands concurrently and require one clear lock error.
Retain focused corruption tests for a missing dependency, nested class, runtime
data file, and report file.
