---
name: modern-css
description: "Use when changing TokTrak HTML or CSS layout, responsive behavior, themes, controls, focus, motion, or browser-compatible visual styling."
---

# Modern CSS

Preserve TokTrak's accessible brutalist UI with native CSS and semantic HTML.
Read the [source-by-source research digest](references/LEARNINGS.md) before
adopting newer CSS.

## Rules

1. Keep one plain stylesheet. Add no framework, utility system, preprocessor,
   package, CSS-in-JS, or CSS build step.
2. Let semantic HTML define structure and CSS define presentation; never use
   JavaScript for layout. Preserve Mustache landmarks, labels, tables, lists,
   native buttons and disclosures, plus every Datastar hook.
3. Preserve hard borders, square geometry, monospace data, condensed headings,
   restrained accents, explicit light/dark colors, and inversion hovers.
4. Audit reset declarations separately: apply `border-box` to elements and both
   pseudo-elements, zero the body margin, inherit all control typography, and
   set text-size adjustment and tab sizing. Do not import a normalize package.
5. Keep semantic tokens in `:root`. Pair `color-scheme: light dark` with
   explicit, contrast-tested dark overrides.
6. Build resilient Grid/Flex layouts with `gap`, logical properties, local
   overflow, and bounded fluid sizes. Reserve margins for content rhythm and
   physical directions for visually directional meaning.
7. Remove global minimum widths. Test every `clamp()` bound; headings and
   unbreakable data must reflow or use local overflow. Verify page composition
   at 320px and 200% zoom, including headers, nav, rhythm grids, visualizations,
   and tables.
8. Keep selectors shallow and low-specificity. Use `:where()` only when zero
   specificity is useful; avoid `!important`.
9. Keep `:focus-visible` obvious. Never remove native focus without an equally
   strong replacement; retain keyboard-operable native controls.
10. Give each transition or animation an explicit reduced-motion rule that
    preserves immediate feedback. Disable smooth scrolling for reduced motion.
11. Use viewport queries for page composition. Add a container query only when
    the same component genuinely occupies differently sized containers.
12. Progressively enhance for a concrete benefit only. Put a supported
    declaration before optional `color-mix()` or newer syntax and retain a
    usable fallback in current Chrome, Firefox, and Safari.
13. Reuse `.scope-page` around page content instead of creating page-specific
    spacing CSS. Preserve Datastar behavior while changing markup.

## Non-goals

- No typography or palette redesign, wholesale reset, framework, or toolchain.
- No speculative cascade layers, `@scope`, nesting, `:is()`, or container-query
  churn without demonstrated repetition or context reuse.
- No experimental contrast functions, `if()`, anchor positioning, scroll-state
  queries, `shape()`, sibling indexes, or `text-box` without earned benefit and
  tested fallback.
- No mandatory scroll snapping, JavaScript layout listeners, or decorative
  `aspect-ratio` on ordinary content.

## Completion checks

1. Inspect changed Mustache semantics, labels, landmarks, native controls, and
   Datastar hooks; deliberately review affected HTML snapshots.
2. Run focused asset and snapshot tests, then `mise run verify`; run
   `mise run prod` when runtime behavior or packaging can change.
3. Manually verify keyboard focus, reduced motion, light/dark contrast, 320px
   reflow, 200% zoom, local overflow, and current Chrome, Firefox, and Safari.
4. Confirm no CSS dependency, build step, broad motion override, global minimum
   width, unsupported-only declaration, or application secret was introduced.
