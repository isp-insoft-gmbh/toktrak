# Learnings

See the [operational rules](../SKILL.md) for applying this digest.

## Sources

- [Essential Modern CSS Features for 2026](https://dev.to/digitalunicon/essential-modern-css-features-for-2026-5835)
- [Modern CSS snippets](https://modern-css.com/)
- [Modernes CSS 2025](https://endruhn.de/modernes-css-2025-alles-was-wir-ueber-die-zukunft-von-stylesheets-wissen-muessen/)
- [modern-normalize](https://github.com/sindresorhus/modern-normalize)
- [Pico CSS](https://picocss.com/)

## Adopted

- The DEV article supports bounded `clamp()`, reduced-motion preferences,
  progressive color functions, and container queries for reused components.
- Modern CSS supports custom properties, `color-scheme`, logical properties,
  Grid/Flex `gap`, `:focus-visible`, and feature detection over JavaScript.
- Endruhn supports native CSS, custom properties, and container-aware reusable
  components as ways to reduce scripts, preprocessors, and DOM manipulation.
- modern-normalize grounds pseudo-element `border-box`, body margin removal,
  control font inheritance, text-size adjustment, and readable tab sizing.
- Pico grounds semantic, class-light HTML with responsive layouts and explicit
  system-aware light/dark styling.

## Rejected or deferred

- Do not adopt every catalogued feature. Container queries, subgrid, nesting,
  cascade layers, `@scope`, and `:is()` need a concrete local problem first.
- Keep a supported declaration before `color-mix()`; do not use unsupported or
  experimental `color-contrast()`, `contrast-color()`, `if()`, anchor,
  scroll-state, shape, sibling-index, or text-box features.
- Avoid blanket `!important` motion resets, mandatory scroll snapping, and
  decorative aspect ratios; target actual motion and content needs.
- Do not import modern-normalize or Pico. Their useful principles fit the
  existing stylesheet; their dependencies and visual defaults do not.
