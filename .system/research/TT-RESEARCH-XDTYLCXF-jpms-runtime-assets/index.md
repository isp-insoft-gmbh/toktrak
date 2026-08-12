---
id: TT-RESEARCH-XDTYLCXF
type: research
title: JPMS runtime asset packaging
---

## Question

Will resources copied beside classes in an exploded Java 26 module survive
`jlink` and remain readable from the linked runtime?

## Evidence

Java 26 documents exploded modules as valid `jlink` input and runtime images as
stores for module classes and resources:

- <https://docs.oracle.com/en/java/javase/26/docs/specs/man/jlink.html>
- <https://docs.oracle.com/en/java/javase/26/docs/api/java.base/java/lang/Module.html#getResourceAsStream(java.lang.String)>
- <https://openjdk.org/jeps/220>

An isolated Java 26.0.1 probe compiled a module, copied an index and CSS file
into its exploded output, linked it, and loaded identical bytes through
`Module.getResourceAsStream` in direct and linked execution. `jimage`
independently listed both resources. Deleting one resource caused deterministic
failure. A negative control also confirmed `javac` does not copy source-tree
resources.

## Conclusion

Supported. The build must explicitly copy and verify resources into the exploded
module; `jlink` retains them and same-module code can load them. A
linked-runtime asset check is meaningful.
