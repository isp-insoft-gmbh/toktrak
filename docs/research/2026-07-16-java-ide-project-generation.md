# Minimal Eclipse, JDT LS, and IntelliJ support

Date: 2026-07-16

## Recommendation

Add one `ide` command to `tools/Build.java`, exposed as `mise run ide`. It
should run existing dependency resolution, then deterministically generate
native Eclipse/JDT and IntelliJ metadata for the two existing JPMS modules.

Do not implement BSP, an IDE plugin, build delegation, run configurations, test
integration, or Error Prone integration. `mise run check` remains authoritative;
IDE metadata only supplies project layout, module-path dependencies, qualified
exports, navigation, and IDE-native diagnostics.

## Why this approach

### Mill

Mill supports IntelliJ through BSP, but also has a direct
[`mill.idea/` XML generator](https://mill-build.org/mill/cli/installation-ide.html#_intellij_idea_xml_support).
For Eclipse, where no Mill/BSP integration exists, it directly generates
`.project`, `.classpath`, and JDT preference files.

Its implementation is useful as a file-format example:

- [`GenEclipseImpl`](https://github.com/com-lihaoyi/mill/blob/dc1f603548f468c5bd306cd50c48e64b2f16f5cd/runner/eclipse/src/mill/eclipse/GenEclipseImpl.scala)
- [`EclipseJdtUtils`](https://github.com/com-lihaoyi/mill/blob/dc1f603548f468c5bd306cd50c48e64b2f16f5cd/runner/eclipse/src/mill/eclipse/EclipseJdtUtils.scala)
- [`GenIdeaImpl`](https://github.com/com-lihaoyi/mill/blob/dc1f603548f468c5bd306cd50c48e64b2f16f5cd/runner/idea/src/mill/idea/GenIdeaImpl.scala)
- [Eclipse generator integration fixtures](https://github.com/com-lihaoyi/mill/tree/dc1f603548f468c5bd306cd50c48e64b2f16f5cd/integration/feature/gen-eclipse)
- [IntelliJ generator integration fixtures](https://github.com/com-lihaoyi/mill/tree/dc1f603548f468c5bd306cd50c48e64b2f16f5cd/integration/feature/gen-idea)

Two Mill choices must not be copied:

1. Its Eclipse generator currently places libraries on the classpath, not
   explicitly on JDT's JPMS module path.
2. It aggregates nested production and test modules into one Eclipse project.

TokTrak has two real `module-info.java` descriptors. Eclipse JDT still rejects
multiple descriptors in one Java project
([JDT issue #1465](https://github.com/eclipse-jdt/eclipse.jdt.core/issues/1465)),
so TokTrak needs two Eclipse projects.

### Bazel

Bazel's IntelliJ support is plugin-based, not a small standalone generator. Sync
runs an aspect over the target graph, serializes sources, generated sources,
dependencies, JARs, toolchains, and outputs, then constructs IntelliJ modules
and libraries. The
[sync architecture](https://blog.bazel.build/2019/09/29/intellij-bazel-sync.html)
is much larger than this task.

The transferable lesson is simple: resolve build truth first, then serialize
IDE-specific views. TokTrak already has that truth in `Build.java`,
`module-info.java`, `TEST_EXPORTS`, and `output/deps/{main,test}`. No generic
target graph or IDE integration protocol is needed for two fixed modules.

## Proposed generated files

### Eclipse and JDT LS

Generate two nested Eclipse projects under ignored build output:

```text
output/ide/eclipse/toktrak/
  .project
  .classpath
  .settings/org.eclipse.core.resources.prefs
  .settings/org.eclipse.jdt.core.prefs
output/ide/eclipse/toktrak.tests/
  .project
  .classpath
  .settings/org.eclipse.core.resources.prefs
  .settings/org.eclipse.jdt.core.prefs
```

Each `.project` should contain the JDT nature and Java builder, plus a portable
linked `src` folder pointing to the real module directory through `PROJECT_LOC`:

- `toktrak` → `sources/toktrak`
- `toktrak.tests` → `tests/toktrak.tests`

This keeps Eclipse files and Eclipse compiler output away from source
directories. Eclipse supports project-relative linked resources, and
[JDT LS recursively discovers nested directories containing both `.project`
and `.classpath`][jdtls-eclipse-importer].

Each `.classpath` should contain:

- linked source entry `src`;
- JavaSE-26 JRE container;
- resolved dependency JAR entries;
- test-to-main project dependency `/toktrak`;
- local `bin` output under the generated Eclipse project.

Mark the JRE container, every modular JAR, and the test-to-main project
dependency with:

```xml
<attribute name="module" value="true"/>
```

JDT defines this attribute as placing an entry on the module path. On the test
project's `/toktrak` entry, also emit one colon-separated `add-exports`
attribute:

```text
toktrak/toktrak.dev=toktrak.tests:
toktrak/toktrak.http=toktrak.tests:
toktrak/toktrak.log=toktrak.tests
```

The syntax and attachment rules come from
[`IClasspathAttribute`](https://github.com/eclipse-jdt/eclipse.jdt.core/blob/792a9156c119d28063904c85726e486ec2fb2206/org.eclipse.jdt.core/model/org/eclipse/jdt/core/IClasspathAttribute.java).

Dependency mapping:

- `toktrak`: all `output/deps/main/*.jar`.
- `toktrak.tests`: `/toktrak`, all main JARs, and all `output/deps/test/*.jar`.

Set UTF-8 resource encoding and Java 26 source/compliance/target preferences. Do
not attempt to reproduce Error Prone warnings in JDT.

Eclipse and JDT LS can use exactly the same files. JDT LS's Eclipse importer
supports multiple nested projects and requires no separate protocol.

### IntelliJ

Generate:

```text
.idea/modules.xml
.idea/misc.xml
.idea/compiler.xml
.idea/modules/toktrak.iml
.idea/modules/toktrak.tests.iml
```

`modules.xml` registers the two `.iml` files. Each `.iml` should declare:

- one content/source root containing its `module-info.java`;
- output under `output/ide/intellij/<module>`;
- inherited project JDK;
- a module-local library containing its resolved JAR roots;
- for tests, a module dependency on `toktrak`.

Module-local libraries avoid one `.idea/libraries/*.xml` file per JAR. IntelliJ
documents that
[module libraries are stored directly in `.iml`](https://plugins.jetbrains.com/docs/intellij/library.html#module-library).

Use one IntelliJ module per JPMS module. IntelliJ recognizes `module-info.java`
and
[automatically chooses module-path behavior](https://blog.jetbrains.com/idea/2017/09/java-9-and-intellij-idea/).

`compiler.xml` should add the same three `--add-exports` options specifically
for `toktrak.tests`. This must be validated in the editor, not merely by
invoking IntelliJ's compiler, because eliminating false package-visibility
diagnostics is the goal.

`misc.xml` should set language level 26. IntelliJ's JDK table and SDK names are
machine-local, so generation cannot make a portable guarantee that an SDK name
matches. Use the detected Java feature version as the initial project JDK name;
if IntelliJ has no matching entry, one manual Project SDK selection is
unavoidable. Mill has the same problem and preserves an existing IntelliJ JDK
choice when regenerating.

## Build integration

Keep implementation fixed to today's two modules:

1. Add `ide` to the existing command switch and help text.
2. Add one `mise run ide` task.
3. Reuse `deps()`, `jarPaths(...)`, module constants, and `TEST_EXPORTS`.
4. Sort JAR paths before writing.
5. Use JDK XML APIs or a tiny escaping helper; add no dependency.
6. Rewrite only generator-owned files.
7. Extend `clean` to remove generated Eclipse projects, IntelliJ module files,
   and owned IntelliJ project XML without deleting user-owned
   `.idea/workspace.xml`.
8. Ignore `.idea/`; Eclipse metadata already lives under ignored `output/`.

Do not add a generic project graph, IDE abstraction, plugin API, watcher, or
incremental sync. Regeneration is explicit after dependency, JDK, module
descriptor, or layout changes.

## Alternatives rejected

### BSP

IntelliJ supports BSP, but Eclipse/JDT LS do not consume it for this use case.
Implementing a long-running JSON-RPC build server, target discovery,
source/dependency endpoints, compile requests, cancellation, and lifecycle
management is far beyond static editing support.

### Bazel-style IntelliJ plugin

A plugin can register JDKs and update IntelliJ's live project model, but it
creates version compatibility, packaging, installation, and maintenance work.
Bazel needs this because its graph is huge and dynamic; TokTrak does not.

### Eclipse files only, imported by IntelliJ

IntelliJ can import Eclipse projects, but relying on it to discover two
generated nested projects adds manual import behavior and couples IntelliJ
correctness to its Eclipse importer. Five small native IntelliJ files are more
deterministic.

### One Eclipse project

Impossible for this layout without discarding one JPMS descriptor or changing
production/test semantics. JDT permits only one `module-info.java` per Java
project.

## Acceptance checks

Implementation is not complete until tested in real clients:

1. Run `mise run ide` from a clean checkout.
2. Open the repository with JDT LS; confirm both projects import.
3. Open the repository in IntelliJ; confirm modules `toktrak` and
   `toktrak.tests` appear.
4. Confirm navigation into production code, test code, JDK classes, Jackson,
   Nimbus, and JUnit.
5. Confirm no unresolved-module or non-exported-package errors in unchanged
   sources.
6. Confirm test references to `toktrak.dev`, `toktrak.http`, and `toktrak.log`
   are accepted.
7. Temporarily remove a real `requires` directive; confirm each client reports
   the resulting error, then revert it.
8. Regenerate twice; confirm byte-identical files and no unrelated `.idea`
   deletion.
9. Run `mise run verify`; generated metadata must not affect the authoritative
   build.

[jdtls-eclipse-importer]: https://github.com/eclipse-jdtls/eclipse.jdt.ls/blob/788f0de6b323978eb42fb774ae712dabcfb28772/org.eclipse.jdt.ls.core/src/org/eclipse/jdt/ls/core/internal/managers/EclipseProjectImporter.java

## Known limit

IDE diagnostics will not equal `javac -Xlint:all` plus Error Prone. This minimal
support removes false layout/module-path errors and enables each IDE's own
warnings; build checks remain definitive.
