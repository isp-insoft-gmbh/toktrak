# JPMS runtime asset packaging research

Date: 2026-08-10

## Question

Will the planned pipeline work when non-class resources are copied into the
exploded `toktrak` module and that module is linked into a custom Java 26
runtime with `jlink`?

## Official documentation

Oracle's Java 26 `jlink` documentation states that:

- `jlink` creates a custom runtime image from modules and their transitive
  dependencies;
- the module path accepts modular JARs, JMOD files, and exploded modules;
- its compression plugin compresses resources in the output image.

Source: <https://docs.oracle.com/en/java/javase/26/docs/specs/man/jlink.html>

Java 26 `Module.getResourceAsStream` reads a `/`-separated resource name from a
specific module. A leading slash is ignored. Named-module resources in module
packages may be encapsulated from callers in other modules, but code in the same
module can read them. TokTrak's `Assets` loader will call the method on its own
module.

Sources:

- <https://docs.oracle.com/en/java/javase/26/docs/api/java.base/java/lang/Module.html#getResourceAsStream(java.lang.String)>
- <https://raw.githubusercontent.com/openjdk/jdk26u/master/src/java.base/share/classes/java/lang/Module.java>

`ModuleReader` defines module resources as abstract `/`-separated names and
provides module-content access independent of whether a module has already been
loaded. Its guidance also treats `.`, `..`, empty elements, and platform file
separators as not found, matching the design's canonical path restrictions.

Source:
<https://docs.oracle.com/en/java/javase/26/docs/api/java.base/java/lang/module/ModuleReader.html>

JEP 220 defines modular runtime images as stores for class and resource files
from JDK, library, and application modules. It defines the
`jrt:/<module>/<path>` model and runtime-image filesystem for enumerating and
reading them.

Source: <https://openjdk.org/jeps/220>

## Experiment

Hypothesis:

> If resources are copied beside `module-info.class` in an exploded module,
> direct module execution and the linked runtime will load identical bytes with
> `Module.getResourceAsStream`; removing a required resource will make the check
> fail.

Environment:

```text
OpenJDK 26.0.1 Temurin
Windows 10.0.26200.8894
repository commit 4a57a67
```

The experiment ran entirely in a generated temporary directory outside the
repository. It created:

```text
mods/probe/
├── module-info.class
├── p/Main.class
└── assets/
    ├── index.tsv
    └── public/main.css
```

The module was compiled with warnings as errors. `Main` enabled assertions and
loaded both resources through:

```java
Main.class.getModule().getResourceAsStream(name)
```

The decisive commands were equivalent to:

```text
javac -Xlint:all -Werror -d mods/probe module-info.java p/Main.java
java -ea --module-path mods/probe -m probe/p.Main
jlink --module-path mods/probe --add-modules probe --output image --strip-debug --no-header-files --no-man-pages
image/bin/java -ea -m probe/p.Main
jimage list image/lib/modules
```

Observed positive controls:

```text
DIRECT_EXPLODED:
RESOURCE_OK module=probe cssBytes=16

LINKED_IMAGE:
RESOURCE_OK module=probe cssBytes=16
```

Independent `jimage` inspection showed:

```text
Module: probe
    assets/index.tsv
    assets/public/main.css
```

Negative control: deleting `mods/probe/assets/public/main.css` before direct
execution returned exit code 1 with:

```text
IllegalStateException: missing resource: assets/public/main.css
```

A second isolated negative control placed `main.css` under a module source tree
and invoked `javac --module-source-path ... -d ... --module probe`. The class
was present in the output while the resource was absent:

```text
CLASS=present
RESOURCE=absent
```

This confirms the build must explicitly copy resources after compilation.

Two harness-only retries occurred before the final run:

1. The first precreated the jlink output directory; `jlink` correctly rejected
   it because `--output` must not already exist.
2. The second expected `jimage` to prefix each resource with the module name;
   actual output uses a `Module: probe` heading followed by relative resource
   paths.

Every temporary directory was deleted by a bounded cleanup trap. Repository
status was unaffected.

## Conclusion

Result: supported.

The planned mechanism works on the project's Java 26 runtime and exact exploded
module-path shape:

1. `javac` produces module classes.
2. The build copies resources into the exploded module root.
3. Direct dev/test execution reads those resources from the named module.
4. `jlink` retains them in the custom runtime image.
5. Linked application code reads identical bytes with
   `Module.getResourceAsStream`.
6. `jimage` independently confirms physical inclusion.
7. The planned `Main --check-assets` negative behavior is meaningful because a
   missing required resource deterministically fails.

No design change is required. The build must still copy resources explicitly;
`javac` does not perform that project-specific copy step.
