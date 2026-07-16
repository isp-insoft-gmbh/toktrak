# IntelliJ Project Generation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use /skill:executing-plans to
> implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for
> tracking.

**Goal:** Complete `mise run ide` with deterministic native IntelliJ metadata
while preserving selective Eclipse/IntelliJ generation.

**Architecture:** Reuse the IDE dependency resolution and bounded XML writer in
`tools/Build.java`. Generate six owned `.idea` files with three Java modules;
clean only those files and preserve user-owned IntelliJ state.

**Tech Stack:** Java 26, IntelliJ project XML, mise

**Roadmap:** None

**Phase:** Single-plan implementation

---

## Tasks

### Task 1: Add failing IntelliJ metadata coverage

**Files:**

- Modify: `tests/tools/BuildTest.java`

- [x] Add `generatesIntellijProjects()` using the existing temporary IDE
      fixture.
- [x] Call the missing `Build.generateIntellijProjectsForTest(...)` seam.
- [x] Parse `modules.xml`, `misc.xml`, `compiler.xml`, and all three `.iml`
      files.
- [x] Assert three registered modules, Java 26, source/test/package-prefix
      semantics, test-scoped dependencies, module-local libraries,
      module-specific `--add-exports`, isolated output paths, and absence of
      jlink/module outputs.
- [x] Create `.idea/workspace.xml`, regenerate, and assert its content is
      unchanged.
- [x] Run `java -ea tools/Build.java test tests/tools/BuildTest.java`; expect
      missing-method compilation failure.

### Task 2: Generate and clean IntelliJ metadata

**Files:**

- Modify: `tools/Build.java`
- Modify: `.gitignore`

- [x] Refactor IDE dependency resolution so bare `ide` generates both formats
      once, while `ide eclipse` and `ide intellij` generate only their target.
- [x] Generate `.idea/modules.xml`, `.idea/misc.xml`, `.idea/compiler.xml`, and
      three `.idea/modules/*.iml` files.
- [x] Model `toktrak`, `toktrak.tests`, and `toktrak.build` exactly as the
      approved spec requires.
- [x] Keep all IntelliJ compiler output under `output/ide/intellij` and all
      libraries rooted in `output/deps`.
- [x] Preserve user-owned `.idea` files during generation and clean.
- [x] Add `.idea/` to `.gitignore`.
- [x] Run the targeted build-tool test; expect PASS.

### Task 3: Verify command behavior

**Files:**

- Modify: `docs/super/specs/2026-07-16-java-ide-project-generation-design.md`

- [x] Remove the temporary staged-delivery failure note from the spec.
- [x] Verify `mise run ide`, `mise run ide eclipse`, and `mise run ide intellij`
      generate the intended formats.
- [x] Verify repeated generation is byte-identical.
- [x] Verify generated XML parses and `.idea/workspace.xml` survives generation.
- [x] Run `mise run check` and `java -ea tools/Build.java test`.
