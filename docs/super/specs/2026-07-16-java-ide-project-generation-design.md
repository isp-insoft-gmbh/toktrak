# Java IDE Project Generation

## Goal

Add a self-contained `mise run ide` command that generates deterministic project
metadata for JDT LS and IntelliJ IDEA without changing TokTrak's build model.

`tools/Build.java` remains the source of truth. IDEs provide editing,
navigation, native diagnostics, semantic source classification, and their own
test support. They do not compile, test, package, launch, or link TokTrak
authoritatively.

This design narrows and supersedes the recommendation in
[the IDE-generation research note](../../research/2026-07-16-java-ide-project-generation.md).

## Priority

1. JDT LS opened at the TokTrak repository root.
2. IntelliJ IDEA opened at the TokTrak repository root.
3. Eclipse IDE as best-effort compatibility inherited from JDT LS metadata.

Eclipse-specific convenience must not add complexity that JDT LS does not need.

## Command contract

Add `ide` to `tools/Build.java` and expose it as:

```text
mise run ide
mise run ide eclipse
mise run ide intellij
```

Bare `mise run ide` generates both formats. A named argument generates only that
format. Unknown or multiple arguments fail before writing metadata.

The command must:

1. require assertions;
2. resolve and cache the main, test, and Refaster dependency sets needed by the
   modeled source;
3. verify the main and test dependency modules as the authoritative build
   already does;
4. generate JDT LS and IntelliJ metadata;
5. succeed without a prior `clean`, `check`, `test`, `dev`, or `prod` command.

The command must not compile sources, run tests, invoke jlink, start an IDE, or
require an IDE installation.

Repeated generation with unchanged inputs must produce byte-identical files.

## IDE model

Generate three logical Java projects/modules.

| IDE name        | Java model                  | Production source                   | Test source           | Dependencies                                        |
| --------------- | --------------------------- | ----------------------------------- | --------------------- | --------------------------------------------------- |
| `toktrak`       | named JPMS module           | `sources/toktrak`                   | none                  | main dependency JARs                                |
| `toktrak.tests` | named JPMS module           | none                                | `tests/toktrak.tests` | `toktrak`, main JARs, test JARs                     |
| `toktrak.build` | unnamed Java module/project | `tools`, including `tools/refaster` | `tests/tools`         | the single Refaster JAR selected by `refasterJar()` |

The metadata must preserve package-relative paths. In particular:

- `tools/Build.java` remains package `tools`;
- `tools/refaster/Rules.java` remains package `tools.refaster`;
- `tests/tools/BuildTest.java` remains package `tools`.

`toktrak.tests` and `tests/tools` must be marked semantically as test source
roots even though IDE-native test discovery and execution remain outside this
generator.

## Directory semantics

Model current directories according to their actual build meaning.

| Path                    | IDE meaning                                                                             |
| ----------------------- | --------------------------------------------------------------------------------------- |
| `sources/toktrak`       | production Java source and JPMS module root                                             |
| `tests/toktrak.tests`   | test Java source and JPMS module root                                                   |
| `tools`                 | build-tool production Java source                                                       |
| `tests/tools`           | build-tool test Java source                                                             |
| `tests/corpus`          | plain repository data; not a Java source or classpath-resource root                     |
| `output/deps/main`      | read-only production module-path libraries                                              |
| `output/deps/test`      | read-only test module-path libraries                                                    |
| `output/deps/refaster`  | resolved Refaster artifacts; only the JAR selected by `refasterJar()` is an IDE library |
| `output/ide`            | generated IDE metadata and IDE compiler output                                          |
| `output/modules`        | authoritative build output; invisible to IDE compilation                                |
| `output/runtimes`       | authoritative jlink runtimes; ignored by IDEs                                           |
| other `output` subtrees | authoritative or temporary build data; ignored by IDEs                                  |

There are currently no Java classpath-resource roots. The generator must not
invent one. A future resource directory becomes an IDE resource root only when
`Build.java` also treats it as a classpath resource.

## Build isolation

TokTrak's development and production launches use generated jlink runtimes
containing resolved dependencies. IDEs must not model those runtimes.

IDE metadata must instead use:

- the host Java 26 SDK/JRE configured in the IDE;
- source roots from the repository;
- dependency JARs from `output/deps`;
- IDE compiler outputs under `output/ide`.

Generated metadata must never reference or write:

- `output/modules`;
- `output/runtimes`;
- `output/build-tests`;
- `output/refaster/classes`;
- authoritative compilation fingerprints or stamps.

Resolving dependencies may update `output/deps`; otherwise `mise run ide` may
write only generator-owned metadata and `output/ide` compiler directories.

## JDT LS metadata

Use Eclipse project metadata because JDT LS imports `.project` plus `.classpath`
projects recursively.

Generate:

```text
output/ide/eclipse/toktrak/
output/ide/eclipse/toktrak.tests/
output/ide/eclipse/toktrak.build/
```

Each directory contains:

```text
.project
.classpath
.settings/org.eclipse.core.resources.prefs
.settings/org.eclipse.jdt.core.prefs
```

Each project must:

- declare the Java builder and JDT nature;
- use UTF-8;
- use the explicit
  `org.eclipse.jdt.launching.JRE_CONTAINER/org.eclipse.jdt.internal.debug.ui.launcher.StandardVMType/JavaSE-26`
  JRE container;
- use Java 26 source, compliance, and target settings;
- link real repository sources through portable `PROJECT_LOC`-relative links;
- place compiler output only in that generated project directory;
- preserve main versus test source semantics;
- contain no link to a jlink runtime or authoritative class output.

The `toktrak` and `toktrak.tests` projects must put their JRE, modular JARs, and
project dependencies on the module path using the JDT `module=true` classpath
attribute.

The `toktrak.tests` source entry, main-project dependency, main dependency JARs,
and test dependency JARs must all carry JDT test semantics. Its `/toktrak`
project dependency must also carry the colon-separated qualified exports:

```text
toktrak/toktrak.dev=toktrak.tests:toktrak/toktrak.http=toktrak.tests:toktrak/toktrak.log=toktrak.tests
```

The `toktrak.build` project is unnamed. Only the single `error_prone_refaster`
JAR returned by the existing `refasterJar()` selection belongs on its classpath;
the other resolved Refaster artifacts must not be added. Its linked roots and
inclusion patterns must preserve the `tools` package paths while distinguishing
`tools` as production source and `tests/tools` as test source.

Opening the repository root with an unmodified JDT LS import configuration must
discover all three projects within JDT LS's default recursive scan depth. User
configuration that explicitly excludes `output/**` is unsupported because it
prevents discovery of generated projects.

## IntelliJ metadata

Generate:

```text
.idea/modules.xml
.idea/misc.xml
.idea/compiler.xml
.idea/modules/toktrak.iml
.idea/modules/toktrak.tests.iml
.idea/modules/toktrak.build.iml
```

The three `.iml` files must model the same three logical projects as JDT LS.

Each IntelliJ module must:

- declare exact content and source roots;
- mark test roots with `isTestSource=true`;
- declare no resource root when none exists;
- use inherited Java 26 project SDK settings;
- write production and test compiler output only below `output/ide/intellij`;
- use module-local libraries rather than one project-library XML file per JAR.

`toktrak` uses production-scoped main dependency JARs. `toktrak.tests` uses a
test-scoped module dependency on `toktrak`; every main and test JAR attached
directly to `toktrak.tests` must also use test scope. `toktrak.build` uses only
the single JAR selected by `refasterJar()` on its ordinary classpath.

The build module may use IntelliJ package-prefix metadata where required to
preserve packages while using `tools` and `tests/tools` as narrow content roots.
It must not make the repository root a broad Java source root.

IntelliJ must infer JPMS behavior for the two module descriptors. `compiler.xml`
must apply the same three `--add-exports` options specifically to
`toktrak.tests`, not globally.

The generated project JDK name uses the running Java feature version. If the
local IntelliJ JDK table has no matching SDK, selecting the Project SDK once is
an accepted machine-local step.

## Ownership and cleanup

Add `.idea/` to `.gitignore`. Eclipse metadata remains under the already ignored
`output/` tree.

The generator owns only:

- the three generated Eclipse project directories;
- `.idea/modules.xml`;
- `.idea/misc.xml`;
- `.idea/compiler.xml`;
- the three generated `.iml` files;
- IDE compiler output below `output/ide`.

Generation may replace those files deterministically. It must not delete or
rewrite user-owned IntelliJ files such as `.idea/workspace.xml`, dictionaries,
inspection profiles, or shelf data.

`mise run clean` must remove generator-owned IDE metadata and IDE compiler
output. It must preserve user-owned `.idea` files and remove `.idea` directories
only when they become empty.

## Non-goals

Do not add:

- BSP;
- an IDE plugin;
- IDE build delegation;
- IDE run configurations;
- IDE test-runner integration;
- Error Prone integration into IDE diagnostics;
- jlink runtime awareness;
- automatic regeneration, file watching, or IDE startup;
- a generic project graph or IDE abstraction layer;
- support for arbitrary future modules or directory layouts.

`mise run check`, `mise run test`, and `mise run verify` remain authoritative.

## Failure behavior

Generation must fail before replacing owned metadata when:

- dependency resolution or module verification fails;
- the running Java feature version is not 26;
- a required source directory or module descriptor is missing;
- an expected dependency directory is missing after resolution;
- an output path escapes its owned directory;
- generated XML cannot represent a path safely.

All generated XML must be UTF-8, well-formed, deterministically ordered, and
safely escaped.

## Verification

Automated checks must prove:

1. `mise run ide` works after `mise run clean` without another prerequisite
   command.
2. All expected files exist and parse as XML.
3. Exactly three IDE projects/modules are registered in each IDE format.
4. Source, test, library, module-path, classpath, qualified-export, explicit
   JavaSE-26 JRE, and output semantics match the tables above.
5. `toktrak.build` references exactly the JAR selected by `refasterJar()`, while
   every JAR attached to `toktrak.tests` has test semantics.
6. JAR entries are sorted and regeneration is byte-identical.
7. No generated metadata references `output/modules` or `output/runtimes`.
8. Generation does not overwrite an unrelated `.idea/workspace.xml` sentinel.
9. `mise run clean` removes owned metadata while preserving that sentinel.
10. `java -ea tools/Build.java verify` remains green.

Manual acceptance requires opening the repository root in current JDT LS and
IntelliJ IDEA and confirming:

1. `toktrak`, `toktrak.tests`, and `toktrak.build` import without manual module
   creation.
2. Java 26 and both JPMS descriptors are recognized.
3. Production, test, and build-tool files carry the correct source/test
   semantics.
4. Navigation resolves JDK classes, production dependencies, JUnit, Refaster,
   and cross-project references.
5. Unchanged source has no false unresolved-module or non-exported-package
   diagnostics.
6. Removing one real `requires` directive produces a diagnostic in both clients;
   restoring it clears the diagnostic.
7. IDE compilation writes only below `output/ide`.
8. No IDE configuration references or launches a generated jlink runtime.

Eclipse IDE behavior is informative but not an acceptance gate.

## Known limitation

IDE diagnostics are intentionally not equivalent to `javac -Xlint:all` plus
Error Prone. IDE metadata exists for accurate project structure and convenient
editing; the build remains definitive.
