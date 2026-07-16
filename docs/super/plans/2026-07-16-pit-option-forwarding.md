# PIT Option Forwarding Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use /skill:executing-plans to
> implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for
> tracking.

**Goal:** Forward arbitrary PIT options while preserving Build-owned
safety/report invariants and source filtering after `--`.

**Architecture:** `Build.java` splits raw PIT tokens from source paths at one
optional `--`, rejects only Build-owned options, and forwards everything else
unchanged. HTML/XML are always retained. PIT defaults to `NO_SPINNER` unless
explicit verbosity is forwarded.

**Tech Stack:** Java 26, PIT 1.25.7, mise.

---

## Task 1: Specify parsing and arguments with failing tests

- [ ] Add `BuildTest` cases for raw forwarding, mandatory source separator,
      split and `=value` reserved options, multiple separators, CR/LF rejection,
      verbosity override, PIT help, and every dry-run spelling.
- [ ] Change report fixtures so normal reports accept only `KILLED`, `SURVIVED`,
      and `NO_COVERAGE`; dry reports accept only nonempty `NOT_STARTED`.
- [ ] Assert PIT arguments omit `--classPath`, always request HTML/XML, default
      to `--verbosity NO_SPINNER`, retain forwarded token order, and omit the
      default when explicit `--verbosity`/`--verbose` is present.
- [ ] Run `mise run test tests/tools/BuildTest.java`; expect
      compilation/assertion failures caused by the old `PitSelection` contract.

## Task 2: Implement the minimal forwarding route

- [ ] Replace `PitSelection(boolean xml, ...)` with a request containing
      forwarded arguments, source-derived targets, dry-run mode, and PIT-help
      mode.
- [ ] Parse one `--` separator; forward every preceding token except Build-owned
      options; treat every following token as a source path.
- [ ] Reject CR/LF in the shared argument-file encoder.
- [ ] Remove PIT `--classPath`; rely on the Java launch classpath and
      `includeLaunchClasspath=true`.
- [ ] Add `NO_SPINNER` only without explicit verbosity, append forwarded options
      unchanged, and keep all Build-owned arguments fixed.
- [ ] Always retain `index.html` and `mutations.xml`; validate `NOT_STARTED`
      only for explicit dry runs.
- [ ] Forward `-h`/`-?` without replacing or validating reports.
- [ ] Update `mise.toml` description and `CLAUDE.md` workflow syntax; add the
      open-source PIT history plugin and clean ownership for
      `output/pit.history`.
- [ ] Run `mise run test tests/tools/BuildTest.java`; require green output.

## Task 3: Integration verification

- [ ] Run a filtered normal command:
      `mise run pit -- sources/toktrak/toktrak/Main.java`.
- [ ] Run forwarded verbosity:
      `mise run pit --verbosity SILENT -- sources/toktrak/toktrak/Main.java`;
      require reports despite silent PIT output.
- [ ] Run `mise run pit --dryRun true -- sources/toktrak/toktrak/Main.java`;
      require only `NOT_STARTED` mutations.
- [ ] Run
      `mise run pit --fullMutationMatrix true -- sources/toktrak/toktrak/Main.java`.
- [ ] Run two iterations with the same `output/pit.history`; require compatible
      reports and a reused history file.
- [ ] Confirm a fake PIT flag reaches PIT and fails there.
- [ ] Run `mise run verify` and full `mise run pit`; require zero exceptional
      statuses and both reports.
