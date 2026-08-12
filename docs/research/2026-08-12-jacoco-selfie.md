# JaCoCo and Selfie integration

Research date: 2026-08-12.

## Decision

Use JaCoCo 0.8.15 through its runtime agent and nodeps CLI, and Selfie 3.1.1
through its JUnit 5 runner. Keep all project logic behind `mise` and the Java
build tool.

## JaCoCo

JaCoCo 0.8.15 is the latest Maven Central release and officially supports Java
class files through Java 26. The agent performs preferred on-the-fly
instrumentation and writes execution data when each test JVM exits. The CLI
turns that data plus the exact compiled classes and source root into HTML, XML,
and CSV reports.

JaCoCo counts bytecode instructions, branches, lines, complexity, methods, and
classes. Instruction and branch coverage are stable gates; line coverage is best
for navigation because source formatting affects it. Exception paths can look
uncovered because probe execution may be interrupted by the exception. Runtime
and report class files must match byte-for-byte.

TokTrak runs both test groups against one append-only execution file, renders
`output/coverage/report/index.html`, and gates the current baseline at 75%
instruction and 60% branch coverage. Floors prevent regression without
pretending 100% is universally useful.

Official sources:

- <https://www.jacoco.org/jacoco/index.html>
- <https://www.jacoco.org/jacoco/trunk/doc/agent.html>
- <https://www.jacoco.org/jacoco/trunk/doc/cli.html>
- <https://www.jacoco.org/jacoco/trunk/doc/counters.html>
- <https://www.jacoco.org/jacoco/trunk/doc/faq.html>
- <https://www.jacoco.org/jacoco/trunk/doc/classids.html>
- <https://repo1.maven.org/maven2/org/jacoco/org.jacoco.agent/maven-metadata.xml>

## Selfie

Selfie 3.1.1 is the latest JVM release. Its JUnit 5 listener supports inline and
disk snapshots, facets, stale snapshot garbage collection, and read-only CI.
`CI=true` makes `_TODO`, `//selfieonce`, and `//SELFIEWRITE` fail instead of
rewriting committed files.

Snapshots are useful for stable structured behavior: complete HTML, JSON,
protocol transcripts, and other outputs where many hand-written contains
assertions omit accidental changes. Precise assertions remain better for
security exclusions, boundaries, and invariants. Nondeterministic values must be
normalized before snapshotting. `cacheSelfie` is intentionally not adopted: it
caches fixtures and does not test the cached operation.

TokTrak starts with one production error-page disk snapshot while retaining
explicit assertions that secrets and diagnostics are absent. Agents get a
project skill explaining safe creation and review. PIT launches JUnit many times
per worker, while Selfie 3.1.1 requires its listener-managed test context.
Snapshot tests are therefore verified by normal CI and excluded from PIT;
Selfie's listener is also deactivated inside PIT to avoid lifecycle conflicts.
Precise tests still drive mutation analysis of the same production behavior.

Official sources and examples:

- <https://selfie.dev/jvm/get-started>
- <https://selfie.dev/jvm/facets>
- <https://selfie.dev/jvm/cache>
- <https://kdoc.selfie.dev/selfie-runner-junit5/com.diffplug.selfie.junit5/-selfie-settings-a-p-i/>
- <https://github.com/diffplug/selfie/blob/main/jvm/example-junit5/src/test/java/com/example/LoginFlowTest.java>
- <https://github.com/equodev/equo-ide/blob/main/solstice/src/test/java/dev/equo/solstice/p2/P2Test.java>
- <https://repo1.maven.org/maven2/com/diffplug/selfie/selfie-runner-junit5/maven-metadata.xml>

## GitHub reports

CI uploads the complete JaCoCo directory and PIT HTML report as 14-day workflow
artifacts. GitHub renders job summaries but not arbitrary artifact HTML in the
browser; download and open `index.html` locally. GitHub Pages would render both,
but Pages is currently disabled and Pages sites can be public even for private
repositories. Enabling it is therefore an explicit repository-admin decision.
GitHub's native coverage comments require Code Quality/Advanced Security and
Cobertura XML; this private repository currently returns 403 for that feature.

Sources:

- <https://docs.github.com/en/actions/reference/workflows-and-actions/workflow-commands#adding-a-job-summary>
- <https://docs.github.com/en/pages/getting-started-with-github-pages/configuring-a-publishing-source-for-your-github-pages-site>
- <https://docs.github.com/en/enterprise-cloud@latest/code-security/how-tos/maintain-quality-code/set-up-code-coverage>
