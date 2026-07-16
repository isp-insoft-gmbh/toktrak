# Refaster Rule Failures

## 2026-07-15 — `Objects.requireNonNull(...)` to `assert`

**Pattern:** Replace standalone `Objects.requireNonNull(value, message)` calls
with `assert value != null : message`.

**Attempt:** A Refaster 2.50.0 block template compiled and applied successfully
on JDK 26.0.1.

**Failure:** Refaster matched both a private internal invariant and a public
external-input validation. It cannot infer which null checks are trust-boundary
validation. Applying the rule would violate the project requirement never to
replace external-input validation with assertions. It would also change
`NullPointerException` semantics to `AssertionError` semantics.

**Outcome:** Rule rejected as unsafe. The original task continues without this
rule.

**Upgrade trigger:** A syntactically explicit internal-invariant marker that the
Refaster rule can match without touching external validation.

## 2026-07-15 — Explicit imports to module imports

**Pattern:** Replace groups of traditional type/package imports with
`import module ...;` when the imported types are exported by one readable
module.

**Attempt:** Checked current Refaster 2.50.0 template and import APIs against
finalized JDK 25 module import declarations. Refaster templates express
method-body expression/block transformations; import declarations are
compilation-unit-wide and cannot differ between `@BeforeTemplate` and
`@AfterTemplate`. Refaster's `ImportPolicy` can only add top-level,
direct-class, or static imports.

**Failure:** Refaster cannot match a set of import declarations or emit
`import module`. Module-import conversion also requires whole-compilation-unit
name-ambiguity analysis outside Refaster's template model.

**Outcome:** Rule rejected as impossible. The original task continues without
this rule.

**Upgrade trigger:** Error Prone or Refaster adds compilation-unit/module-import
matching and emission support, or the project adopts a dedicated import
rewriter.
