---
id: TT-RESEARCH-RFASTR01
type: research
title: Refaster rule failures
---

## 2026-08-18 — Replace exposed object monitors

**Pattern:** Replace repeated `synchronized (this)` blocks with one private
monitor field and move matching `wait`/`notifyAll` calls onto that monitor.

**Attempt:** Checked Refaster 2.50.0 templates against the current Refaster
reference. A safe rewrite must introduce a field once per target class, bind
every synchronized block to that field, and update unqualified monitor methods.

**Failure:** Refaster rewrites matched expressions and blocks; it cannot
introduce one target-class field and bind separate matches to that shared
symbol. An expression-only rule would reference a rule-template field, not a
field in the transformed class.

**Outcome:** Rule rejected as impossible. Apply the two class-local monitor
refactors explicitly and verify their concurrency tests.

**Upgrade trigger:** Refaster adds target-class member introduction with one
shared symbol available across separate statement matches.

## 2026-07-16 — Test method naming convention

**Pattern:** Rename every JUnit test method and manually invoked `BuildTest`
case to
`given_<camelCaseContext>_when_<camelCaseBehavior>_then_<camelCaseExpectation>`.

**Attempt:** Checked current Refaster 2.50.0 template capabilities against the
repeated method-declaration rename. JUnit methods require declaration-symbol
renames; `BuildTest` additionally requires matching direct call-site renames.

**Failure:** Refaster templates rewrite matched expressions, statements, and
method bodies. They cannot rename a declared method symbol or update every
reference to that symbol, so no safe Refaster rule can express this change.

**Outcome:** Rule rejected as impossible. Apply explicit symbol renames and let
javac verify every `BuildTest` call site.

**Upgrade trigger:** Refaster adds declaration-symbol rename support with
compiler-resolved call-site updates.

## 2026-07-16 — Close `HttpClient` test resources

**Pattern:** Convert four local `HttpClient` creations into try-with-resources
scopes that close each client after its final use.

**Attempt:** Checked current Refaster 2.50.0 template constraints and modeled
the common client-construction expression.

**Failure:** The safe replacement must capture and reparent an arbitrary tail of
statements into a new try-with-resources block. Refaster expression and block
templates cannot bind an open-ended enclosing statement tail, and replacing only
the construction expression cannot introduce the required lexical scope.

**Outcome:** Rule rejected as impossible. The four `HttpClient` sites are
manually replaced with bounded `HttpURLConnection` requests that own no selector
threads.

**Upgrade trigger:** Refaster adds statement-sequence captures that can safely
reparent the remainder of an enclosing block.

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
