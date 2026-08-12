---
id: TT-RESEARCH-HZ9QD5GJ
type: research
title: Java IDE project generation
---

## Finding

Generate native Eclipse/JDT LS and IntelliJ metadata from `tools/Build.java`.
Build truth already exists in source roots, module descriptors, qualified test
exports, and resolved dependency directories; BSP or an IDE plugin adds no value
for this fixed project.

JDT needs one project per JPMS descriptor because one Eclipse project cannot
contain multiple `module-info.java` files. IntelliJ likewise works best with one
module per JPMS module. Build-tool sources form a separate unnamed module.

Generated metadata should use the host Java SDK, module/class paths from
`output/deps`, and compiler output below `output/ide`. It must never use linked
runtimes or authoritative outputs under `output/modules`.

## Evidence

- Mill's static Eclipse and IntelliJ generators demonstrate the native metadata
  approach:
  <https://mill-build.org/mill/cli/installation-ide.html#_intellij_idea_xml_support>.
- JDT permits one module descriptor per project:
  <https://github.com/eclipse-jdt/eclipse.jdt.core/issues/1465>.
- IntelliJ infers JPMS from `module-info.java`:
  <https://blog.jetbrains.com/idea/2017/09/java-9-and-intellij-idea/>.
- Bazel's plugin/aspect sync solves a much larger dynamic graph and is needless
  here: <https://blog.bazel.build/2019/09/29/intellij-bazel-sync.html>.

## Limits

IDE diagnostics intentionally differ from `javac -Xlint:all` plus Error Prone.
The generated model supports editing and navigation; `mise run check` remains
authoritative.
