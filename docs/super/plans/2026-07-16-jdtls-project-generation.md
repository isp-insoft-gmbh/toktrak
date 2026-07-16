# JDT LS Project Generation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use /skill:executing-plans to
> implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for
> tracking.

**Goal:** Generate the three approved Eclipse/JDT projects through
`mise run ide eclipse` while reserving the final Eclipse/IntelliJ command
contract.

**Architecture:** `tools/Build.java` resolves the existing dependency sets,
renders deterministic Eclipse XML, and writes only below `output/ide/eclipse`. A
package-private test seam writes the same model below a temporary root;
`BuildTest` parses and checks the generated metadata.

**Tech Stack:** Java 26, JDK XML APIs, Eclipse JDT project metadata, mise

**Roadmap:** None

**Phase:** Single-plan implementation

---

## Tasks

### Task 1: Add failing generator coverage

**Files:**

- Modify: `tests/tools/BuildTest.java`

- [x] **Step 1: Add the test invocation**

Call `generatesEclipseProjects();` from `BuildTest.main` after argument-limit
coverage.

- [x] **Step 2: Add the generator test**

Create a bounded temporary repository containing these directories and files:

```text
sources/toktrak/module-info.java
tests/toktrak.tests/module-info.java
tools/Build.java
tools/refaster/Rules.java
tests/tools/BuildTest.java
deps/main.jar
deps/test.jar
deps/error_prone_refaster-2.50.0.jar
```

Call:

```java
Build.generateEclipseProjectsForTest(
    root,
    root.resolve("output/ide/eclipse"),
    List.of(root.resolve("deps/main.jar")),
    List.of(root.resolve("deps/test.jar")),
    root.resolve("deps/error_prone_refaster-2.50.0.jar"));
```

Assert all three `.project`, `.classpath`, and two preference files exist. Parse
every XML file with `DocumentBuilderFactory`. Assert:

- all three project names occur;
- `toktrak.tests` contains test, module, and `add-exports` attributes;
- `toktrak.build` contains `src/tools`, `test/tools`, and only the Refaster JAR;
- every JRE container is JavaSE-26;
- no metadata contains `output/modules` or `output/runtimes`.

Delete only the temporary tree in `finally`.

- [x] **Step 3: Verify RED**

Run:

```text
java -ea tools/Build.java test tests/tools/BuildTest.java
```

Expected: compilation fails because `generateEclipseProjectsForTest` does not
exist.

### Task 2: Generate Eclipse/JDT metadata

**Files:**

- Modify: `tools/Build.java`

- [x] **Step 1: Add the command contract**

Add `ide` to the command list and switch. Accept zero or one argument after
`ide`:

- `eclipse`: generate Eclipse/JDT metadata;
- `intellij`: fail before writing with
  `IntelliJ IDE generation is not implemented`;
- no argument: fail before writing with
  `IntelliJ IDE generation is not implemented; use 'ide eclipse'`;
- any other or multiple arguments: fail before writing with a bounded argument
  error.

- [x] **Step 2: Resolve only required dependencies**

For the Eclipse target, resolve main and test dependencies with module names,
resolve Refaster dependencies without module names, verify main/test modules,
then call the existing `refasterJar()` selector.

Do not compile, test, or invoke jlink.

- [x] **Step 3: Render the projects**

Generate:

```text
output/ide/eclipse/toktrak/
output/ide/eclipse/toktrak.tests/
output/ide/eclipse/toktrak.build/
```

Each project receives `.project`, `.classpath`, UTF-8 resource preferences, and
Java 26 compiler preferences. Use `PARENT-4-PROJECT_LOC` links. For
`toktrak.build`, create physical `src` and `test` parents and nested `src/tools`
and `test/tools` links so links do not overlap the generated project.

Use sorted main/test JAR lists. Mark app/test JRE and libraries as modules. Mark
every test source, project dependency, and direct test-project JAR as test. Put
the approved `add-exports` value on `/toktrak`. Keep the build project unnamed
and attach only the selected Refaster JAR on its classpath.

Escape XML attributes and reject generated files larger than 1 MiB.

- [x] **Step 4: Extend clean**

Delete `output/ide` from `clean` without touching source or dependency output.

- [x] **Step 5: Verify GREEN**

Run:

```text
java -ea tools/Build.java test tests/tools/BuildTest.java
```

Expected: build-tool tests pass.

### Task 3: Expose the mise task and verify

**Files:**

- Modify: `mise.toml`

- [x] **Step 1: Add the task**

Add:

```toml
[tasks.ide]
description = "Generate IDE metadata; choose eclipse or intellij, or omit for both"
raw_args = true
run = "java -ea tools/Build.java ide"
```

- [x] **Step 2: Generate twice**

Run `mise run ide eclipse`, hash every generated file, run it again, and confirm
identical hashes.

- [x] **Step 3: Verify failure behavior**

Run bare `mise run ide` and `mise run ide intellij`. Both must fail before
changing generated Eclipse file hashes.

- [x] **Step 4: Verify the repository**

Run:

```text
mise run check
java -ea tools/Build.java test
```

Expected: Markdown and Java checks pass; all tests pass. If `mise run check` is
blocked only by the pre-existing `Main.java` formatting change, report that
exact limitation without modifying the file.
