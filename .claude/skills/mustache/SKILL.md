---
name: mustache
description: "Use when editing TokTrak .mustache templates or reviewing Mustache variables and sections."
---

# Mustache

Read [the formal reference](references/spec.md) before editing templates.

- Use escaped variables, sections, and inverted sections.
- Preserve standalone-tag and whitespace semantics.
- Use one static `base` parent and one `content` block for top-level pages.
- Reject other partials, nested parents, extra blocks, raw variables, delimiter
  changes, dynamic names, recursion, and template-generating lambdas in TokTrak.
- Keep URLs validated in Java; never create dynamic tags, attributes, scripts,
  styles, or event handlers.
