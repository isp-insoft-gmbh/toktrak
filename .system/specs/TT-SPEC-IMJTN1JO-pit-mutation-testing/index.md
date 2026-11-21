---
id: TT-SPEC-IMJTN1JO
type: spec
title: PIT mutation testing
---

## Intent

Provide a standalone focused mutation loop:

```text
mise run pit [--no-history] [PIT options...] [-- production source paths...]
```

No selector mutates all TokTrak production code. Paths after one separator, or
existing production source paths before it, map deterministically to the
top-level class and nested-class glob; Mise consumes the first `--`. PIT
discovers covering tests.

## Contract

Forward pre-separator PIT options unchanged except build-owned safety, target,
path, report, concurrency, timeout, JVM, and history locations. Reject reserved
options, multiple separators, symbolic/external source paths, and argument
injection. History in `output/pit.history` is on by default; `--no-history` opts
out for one run. A run stopped at the time limit keeps finished mutants, and a
run on another selection replaces the history. Delete it only when results are
inconsistent.

PIT remains outside check/test/verify/CI. It compiles through the authoritative
build, runs with four workers and assertions, bounds test/mutation/process time,
and replaces `output/mutations` each run. Successful normal reports contain only
killed, survived, or no-coverage statuses; dry runs contain only not-started.
Survivors do not fail the command.

Always retain validated HTML and XML reports. Missing, oversized, malformed, or
exceptional reports fail. Timeout and process interruption terminate descendant
processes. No warning/exclusion suppression or score threshold is allowed.

## Acceptance

Selection, forwarding, report replacement/validation, help side effects,
history, process cleanup, and dependency cache integrity are directly tested.
Focused and full mutation runs complete without exceptional statuses or
application-resource warnings.
