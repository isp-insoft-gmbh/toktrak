---
id: TT-SPEC-Y_XMD0D1
type: spec
title: Java IDE metadata
research:
  - TT-RESEARCH-HZ9QD5GJ
---

## Intent

`mise run ide [eclipse|intellij]` deterministically generates editing metadata
without changing the authoritative build. Bare invocation generates both.

## Model

Represent exactly four projects/modules:

- named production module `toktrak` from `sources/toktrak`;
- named test module `toktrak.tests` from `tests/toktrak.tests`;
- unnamed build module `toktrak.build` from `tools` and `tests/tools`;
- unnamed performance module `toktrak.perf` from `tools/perf`, with production
  and JMH dependencies.

Use host Java 26, dependency JARs under `output/deps`, qualified test exports,
and IDE compiler output below `output/ide`. Preserve production/test semantics.
Never reference linked runtimes or authoritative module outputs.

JDT LS uses nested Eclipse projects under `output/ide/eclipse`; IntelliJ uses
generator-owned `.idea` project/module XML. Both IDEs run JStachio APT into
disposable IDE output instead of importing authoritative generated sources.
IntelliJ enables only the accepted supplementary inspection categories and uses
project formatting settings aligned with Google Java Format; `Build.java` checks
and `mise run fmt` remain authoritative. Generation is sorted, safely escaped,
byte-identical on repeat, and preserves user-owned IDE files. `clean` removes
only generated ownership.

## Boundaries

The command resolves dependencies but does not compile, test, link, launch an
IDE, watch files, add run configurations, or implement BSP/plugins. Unknown or
multiple selectors fail before metadata replacement.

## Acceptance

Generation works after clean, registers exactly four projects in both formats,
models JPMS/classpath/test/performance scopes correctly, configures APT without
duplicate generated inputs, writes only owned output, preserves user sentinels,
and imports, inspects, formats, and rebuilds cleanly in JDT LS and IntelliJ.
Build checks remain authoritative.
