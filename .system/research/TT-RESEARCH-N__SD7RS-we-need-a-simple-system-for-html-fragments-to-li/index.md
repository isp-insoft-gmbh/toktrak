---
id: TT-RESEARCH-N__SD7RS
type: research
title: Lightweight HTML asset templating
---

## Need

HTML currently lives in Java concatenation in `Router` and `ErrorPage`. Runtime
assets already provide bounded, hash-verified private resources loaded before
HTTP binding, but `.html` is rejected and private resources are exposed only as
bytes.

TokTrak's dashboard, leaderboards, tables, token list, conditional warnings, and
Datastar patches require repeated and conditional fragments. Datastar consumes
rendered HTML and imposes no renderer.

## Security baseline

HTML encoding is context-sensitive. Element text, quoted attributes, URLs,
JavaScript, CSS, and unsafe contexts need different handling. Every option still
requires validated dynamic URLs and a ban on dynamic scripts, styles, event
attributes, tag names, and attribute names.

- <https://cheatsheetseries.owasp.org/cheatsheets/Cross_Site_Scripting_Prevention_Cheat_Sheet.html>
- <https://data-star.dev/reference/sse_events>

Java 26 has no standard HTML template facility. String Templates were withdrawn
before JDK 23; `MessageFormat` and string formatting provide no HTML safety.

- <https://docs.oracle.com/en/java/javase/23/migrate/significant-changes-jdk-release.html>

## Experiment

A Java 26.0.1 JPMS probe rendered a representative leaderboard with two rows, an
optional timestamp, an empty state, and hostile labels. Positive checks asserted
exact escaping and structure. Negative checks exercised missing model fields.
`jdeps`, `jlink`, and linked-runtime execution independently checked packaging.

### Custom interpolation

Scalar-only interpolation safely escaped a pre-rendered row string, so the row
appeared as text. It cannot place a variable number of elements inside one
complete template.

An opaque rendered-HTML value fixed composition while Java retained loops and
conditions. The probe passed escaping, empty-state, missing-placeholder, JPMS,
and linked-runtime checks with no dependency. One component required page, row,
time, and empty-state assets plus explicit Java assembly. This is viable, but it
is already a small template system whose parser, context restrictions, bounds,
opaque HTML type, diagnostics, and tests TokTrak must own.

### JStachio 1.3.7

One external Mustache template and one typed record rendered the complete
fixture. Variables were HTML-escaped, sections handled rows/conditions, an empty
list selected the inverted section, and an unknown field failed compilation at
the exact template position.

The standard safe runtime added two explicit JPMS modules and 168 KiB of JARs;
the stripped linked image grew from 46,754 KiB to 47,062 KiB. Its annotation
processor is a 400 KiB build-only input. Templates compile to Java, so request
rendering performs no template lookup, parsing, reflection, or expression
evaluation.

The processor emitted a CWD-layout warning that fails TokTrak's `-Werror` build
when classes target the current custom output shape. A `target/classes`-shaped
staging path eliminated it. Integration must resolve this without suppressing
the warning. The 1.3.7 processor JAR also reports module version
`1.4.0-SNAPSHOT` while its manifest reports implementation version `1.3.7`; this
is build-only but should be pinned and asserted.

Zero-runtime-dependency generation exists, but disables escaping by default and
requires a supplied escaper. Prefer the small standard runtime: safe defaults
are worth two explicit modules.

- <https://jstach.io/doc/jstachio/current/apidocs/>
- <https://mustache.github.io/mustache.5.html>
- <https://repo1.maven.org/maven2/io/jstach/jstachio/maven-metadata.xml>

### Thymeleaf 3.1.5.RELEASE

One natural HTML template rendered rows, conditions, and escaped hostile text.
An unknown variable silently produced `<p></p>` instead of failing validation.
Record property access through OGNL required opening the model package.

The resolved runtime contained Thymeleaf, OGNL, AttoParser, Unbescape,
Javassist, and SLF4J: 2.4 MiB across six JARs. Core artifacts are automatic
modules; dependencies and `java.sql` needed manual resolution. `jlink` rejected
the graph at `attoparser`: automatic modules cannot enter a linked image. Using
a loaded asset string also selects the non-cacheable string resolver by default,
requiring more runtime configuration or a custom resolver.

Thymeleaf's runtime parser, OGNL expression language, reflection openings,
silent misses, automatic modules, and dependency graph conflict with TokTrak's
small explicit linked runtime.

- <https://www.thymeleaf.org/doc/tutorials/3.1/usingthymeleaf.html>
- <https://www.thymeleaf.org/apidocs/thymeleaf/3.1.5.RELEASE/org/thymeleaf/templateresolver/StringTemplateResolver.html>
- <https://repo1.maven.org/maven2/org/thymeleaf/thymeleaf/3.1.5.RELEASE/thymeleaf-3.1.5.RELEASE.pom>

## Comparison

| Need                    | Custom typed interpolation     | JStachio                    | Thymeleaf                            |
| ----------------------- | ------------------------------ | --------------------------- | ------------------------------------ |
| Asset-owned HTML        | Yes                            | Yes, compile-time asset     | Yes, runtime asset                   |
| Lists/conditions        | Java assembly                  | Typed Mustache sections     | Runtime expressions                  |
| Dynamic-value safety    | TokTrak must implement         | Escaped by default          | `th:text` escaped; `th:utext` unsafe |
| Model/template mismatch | Runtime                        | Compile time                | Runtime; missing value may be empty  |
| Runtime work            | Replacement/assembly           | Generated Java calls        | Parse/cache/evaluate/reflect         |
| Runtime dependencies    | None                           | 2 explicit modules, 168 KiB | 6 JARs, 2.4 MiB                      |
| Linked runtime          | Pass                           | Pass                        | Fail: automatic modules              |
| Maintenance owner       | TokTrak                        | JStachio                    | Thymeleaf/OGNL plus integration      |
| TokTrak fit             | Acceptable but custom language | Best                        | Reject                               |

## Resolution

Collection control can remain in Java only by adding trusted fragment
composition and splitting each repeated/conditional component across more
assets. Scalar interpolation alone is insufficient. TokTrak's already-defined
leaderboards, tables, token rows, warnings, and live patches make typed template
sections the smaller system overall.

Use JStachio external templates with its standard runtime. Keep models as small
records, call generated renderers directly, generate but do not commit Java, and
keep templates under private assets. Reject raw Mustache variables, lambdas,
dynamic partials, and inheritance during asset validation. Keep URL validation
in Java and retain the dedicated bounded `ErrorPage` renderer.

Do not adopt Thymeleaf. Do not build a general custom template engine. Revisit a
custom scalar renderer only for isolated documents that provably have no lists,
conditions, or nested fragments.
