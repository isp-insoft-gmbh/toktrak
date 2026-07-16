# Refaster Workflow Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use /skill:executing-plans to
> implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for
> tracking.

**Goal:** Compile project Refaster rules during normal compilation, apply them
through `mise run refactor`, enforce refactoring cleanliness through
`mise run ci`, and seed the workflow with a safe Windows OS-name rule.

**Architecture:** Keep Refaster's shaded compiler artifact in a separate
dependency directory because resolving it beside `error_prone_core` creates
duplicate modules. Compile one top-level `tools/refaster/Rules.java` into one
cached serialized rule. Apply that rule in two explicit Error Prone patch
passes—JPMS application/tests and classpath build-tool sources—then format. Keep
`verify` read-only; `ci` runs refactoring, rejects any resulting dirty Git tree,
then invokes verification.

**Tech Stack:** Java 26.0.1, javac plugin API, Error Prone/Refaster 2.50.0,
jresolve, google-java-format, mise

**Roadmap:** None

**Phase:** Single-plan implementation

---

## Tasks

### Task 1: Add failing Refaster behavior coverage

**Files:**

- Modify: `tests/tools/BuildTest.java`

- [ ] **Step 1: Add the failing test invocation**

Add `appliesWindowsOsNameRule();` after `rejectsErrorProneViolation();` in
`BuildTest.main`.

- [ ] **Step 2: Add the positive/negative rule test**

Add a test that writes this bounded temporary source:

```java
package probe;
import java.util.Locale;
final class Demo {
  boolean windows() {
    return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
  }
  boolean arbitrary(String value) {
    return value.toLowerCase(Locale.ROOT).contains("win");
  }
}
```

Build a javac command from the missing `Build.refasterArgumentsForTest()`,
compile in patch mode, and assert:

```java
sourceText.contains("System.getProperty(\"os.name\").startsWith(\"Windows\")")
sourceText.contains("value.toLowerCase(Locale.ROOT).contains(\"win\")")
```

Use:

```java
Build.waitForProcessForTest(
    ..., Duration.ofSeconds(30), Duration.ofSeconds(2), true)
```

Delete the bounded temporary tree in `finally`.

- [ ] **Step 3: Run the build-tool compile and verify RED**

Run:

```text
javac -Xlint:all -Werror -d output/build-tests-red tools/Build.java tests/tools/BuildTest.java
```

Expected: compilation fails because `Build.refasterArgumentsForTest()` does not
exist.

### Task 2: Resolve and compile Refaster rules

**Files:**

- Create: `sources/refaster-deps.txt`
- Create: `tools/refaster/Rules.java`
- Modify: `tools/Build.java`
- Modify: `tests/tools/BuildTest.java`

- [ ] **Step 1: Pin the Refaster compiler artifact**

Create `sources/refaster-deps.txt`:

```text
pkg:maven/com.google.errorprone/error_prone_refaster@2.50.0
```

Resolve it into `output/deps/refaster` without `--use-module-names`. The shaded
Refaster dependency graph contains duplicate module names, so it must not share
`output/deps/build` or module-name output mode.

- [ ] **Step 2: Add the first rule**

Create `tools/refaster/Rules.java`:

```java
package tools.refaster;

import com.google.errorprone.refaster.annotation.AfterTemplate;
import com.google.errorprone.refaster.annotation.BeforeTemplate;
import java.util.Locale;

public final class Rules {
  private Rules() {}

  static final class WindowsOsName {
    @BeforeTemplate
    boolean before() {
      return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
    }

    @AfterTemplate
    boolean after() {
      return System.getProperty("os.name").startsWith("Windows");
    }
  }
}
```

The no-parameter before template intentionally matches only the exact `os.name`
expression, not arbitrary case-insensitive string searches.

- [ ] **Step 3: Add Refaster paths and cleanup**

In `tools/Build.java`, add bounded constants for:

```text
output/deps/refaster
output/refaster
output/refaster/classes
output/refaster/toktrak.refaster
tools/refaster/Rules.java
```

Delete dependency and generated Refaster trees in `clean()`.

- [ ] **Step 4: Generalize dependency resolution**

Add a `boolean useModuleNames` parameter to `ensureDependency(...)`. Existing
main/test/build calls pass `true`; `ensureRefasterDependencies()` passes `false`
for `sources/refaster-deps.txt` and `output/deps/refaster`.

- [ ] **Step 5: Select exactly one Refaster compiler JAR**

Add a helper that filters bounded `jarPaths(List.of(REFASTER_DEPS))` for a
filename beginning `error_prone_refaster-` and ending `.jar`. Fail unless
exactly one match exists.

- [ ] **Step 6: Compile and cache the serialized rule**

Add `compileRefaster()` that:

1. ensures Refaster dependencies;
2. builds javac arguments with `-Xlint:all`, `-Werror`, the Refaster JAR
   classpath, output classes,
   `-Xplugin:RefasterRuleCompiler --out <absolute rule path>`, and `Rules.java`;
3. fingerprints rule source, Refaster dependency JARs, JDK/platform, javac
   arguments, and required JDK exports/opens;
4. accepts a cache hit only when the fingerprint, `Rules.class`, and non-empty
   serialized rule exist;
5. otherwise deletes only Refaster generated classes/rule, runs javac with the
   existing compiler JVM exports, and writes the fingerprint.

Call `compileRefaster()` from normal `compile()` before compiling
application/test modules.

- [ ] **Step 7: Build reusable Refaster patch arguments**

Add a private compiler-argument builder that uses the existing Error Prone
processor path and shared `sources/error-prone.cfg`, then appends in the same
`-Xplugin:ErrorProne ...` argument:

```text
-XepPatchChecks:refaster:<absolute output/refaster/toktrak.refaster>
-XepPatchLocation:IN_PLACE
```

Do not add `-Werror` to patch passes; Refaster reports each applied match as a
warning. Keep normal compilation's `-Werror` unchanged.

Expose a package-private `refasterArgumentsForTest()` that prepends the required
javac JVM exports/opens for the focused fixture test.

- [ ] **Step 8: Make build-tool tests independently prepare the rule**

Call `compileRefaster()` at the start of `testBuildTool()` so
`mise run test tests/tools` can execute the rule test without an application
compile.

Update `runsBuildToolTestsWithoutApplicationSources()` to copy:

```text
sources/refaster-deps.txt
tools/refaster/Rules.java
```

into its temporary project, alongside the already copied build
dependency/config/vendor files.

- [ ] **Step 9: Run the focused test and verify GREEN**

Run:

```text
java -ea tools/Build.java test tests/tools
```

Expected: Refaster dependencies resolve, the rule compiles, and all build-tool
tests pass.

### Task 3: Apply Refaster rules explicitly and gate CI cleanliness

**Files:**

- Modify: `tools/Build.java`
- Modify: `mise.toml`

- [ ] **Step 1: Apply to JPMS application/test sources**

Add a bounded patch pass using existing module source paths, module path, test
exports, and modules `toktrak,toktrak.tests`. Write throwaway class output under
`output/refaster/apply-modules`; delete that generated class directory before
each pass.

- [ ] **Step 2: Apply to build-tool sources**

Add a second bounded patch pass for `tools/Build.java` and
`tests/tools/BuildTest.java`, with throwaway class output under
`output/refaster/apply-build-tool`. Do not include `tools/refaster/Rules.java`,
preventing a rule from rewriting its own template.

- [ ] **Step 3: Add the `refactor` command**

Add `refactor` to `Build.main` command validation and dispatch. Its sequence is:

```text
resolve dependencies
compile Refaster rules
apply module pass
apply build-tool pass
format all Java sources in place
```

- [ ] **Step 4: Write the failing Git-dirty test**

Add a `BuildTest` fixture that initializes a bounded temporary Git repository,
asserts the missing `Build.isGitDirtyForTest(path)` returns `false`, creates an
untracked file, and asserts it returns `true`. Run the direct build-tool compile
and expect failure because the helper does not exist. Using an untracked file
avoids Windows read-only Git object cleanup while still exercising the
`--untracked-files=all` CI contract.

- [ ] **Step 5: Add a bounded Git cleanliness check**

Implement `isGitDirty(Path repository)` by running:

```text
git status --porcelain=v1 --untracked-files=all
```

with the repository as working directory and stderr merged into stdout. Capture
stdout through a virtual-thread reader using
`readNBytes(GIT_STATUS_BYTES_MAX + 1)` while the main thread enforces the
existing process timeout. If capture reaches the ceiling, forcibly terminate Git
and fail. Reject nonzero exit, invalid UTF-8, or output above the named ceiling;
return whether bounded output is non-empty. Store no status file inside the
repository. Expose only `isGitDirtyForTest(Path)` package-private.

- [ ] **Step 6: Add the `ci` command**

Keep `verify()` read-only. Add `ci` to `Build.main`; its exact sequence is:

```text
refactor
if Git working tree is dirty: fail "working tree is dirty after refactor"
verify
```

This intentionally leaves Refaster edits visible when the gate fails.

- [ ] **Step 7: Add mise tasks**

Add:

```toml
[tasks.refactor]
description = "Apply Refaster rules and format Java sources"
run = "java -ea tools/Build.java refactor"

[tasks.ci]
description = "Apply Refaster rules, require a clean tree, and verify"
run = "java -ea tools/Build.java ci"
```

Keep the existing `verify` description read-only.

- [ ] **Step 8: Run refactor and inspect the three intended edits**

Run:

```text
mise run refactor
```

Expected exactly these old OS-name patterns to become `startsWith("Windows")`:

```text
tools/Build.java
tests/tools/BuildTest.java
tests/toktrak.tests/toktrak/tests/JlinkSmokeTest.java
```

Expected: arbitrary lowercase/contains expressions remain untouched; now-unused
`Locale` imports are removed by formatting.

### Task 4: Document the workflow and agent rule

**Files:**

- Modify: `CLAUDE.md`
- Create: `docs/REFASTER_RULE_FAILS.md`
- Modify: `README.md`

- [ ] **Step 1: Preserve the approved project instruction**

Keep the approved `CLAUDE.md` Refaster section:

```markdown
## Refaster

- For three or more identical code-pattern transformations, fetch current
  Refaster documentation and known limitations, then attempt one Refaster rule
  first.
- If a rule is impossible or unsafe, log the date, pattern, and reason in
  `docs/REFASTER_RULE_FAILS.md`; abandon only the rule attempt and continue the
  original task.
```

- [ ] **Step 2: Preserve failed attempts**

Keep `docs/REFASTER_RULE_FAILS.md` entries for:

- unsafe `Objects.requireNonNull(...)` → `assert` conversion;
- impossible traditional-import → module-import conversion.

- [ ] **Step 3: Update developer documentation**

Document `mise run refactor` as mutating source via Refaster then formatting.
Document `mise run ci` as refactoring, rejecting a dirty tree, then running
read-only verification. State that normal compile caches the serialized rule.
Add `output/deps/refaster` and `output/refaster` to the repository layout.

### Task 5: Full verification

**Files:**

- Verify all modified/created files

- [ ] **Step 1: Run a cold build**

Run:

```text
java -ea tools/Build.java clean
java -ea tools/Build.java verify
```

Expected:

- all dependency groups resolve from a clean output tree;
- Refaster rule compilation succeeds on JDK 26;
- `verify` does not mutate source;
- Error Prone and javac emit zero warnings;
- all 60 tests pass.

- [ ] **Step 2: Verify idempotence**

Record Java-source hashes, run:

```text
mise run refactor
```

again, and confirm Java-source hashes are unchanged.

- [ ] **Step 3: Verify the CI gate while the implementation is uncommitted**

Run:

```text
mise run ci
```

Expected: Refaster is idempotent, then CI fails with
`working tree is dirty after refactor` because the implementation itself is
intentionally uncommitted. The `BuildTest` clean/dirty temporary repository
fixture proves both gate branches independently of the development checkout.

- [ ] **Step 4: Inspect final state**

Run `git status --short --branch` and inspect the complete diff. Confirm only
intended source, build, test, rule, documentation, and plan files changed;
generated `output/` artifacts remain ignored. After the implementation is
committed, rerun `mise run ci`; expected: clean-tree gate passes and all 60
tests pass.
