---
name: toktrak-jstachio
description: "Use when changing TokTrak JStachio models, generated renderers, templates, APT, or encoded HTML output."
---

# TokTrak JStachio

Read [the pinned integration reference](references/integration.md).

- Keep JStachio at 1.3.7 across annotations, runtime, and APT.
- Put external templates below `sources/toktrak/templates`.
- Compose `BaseView base` into every top-level page model; `base.mustache` owns
  shared document markup and one static `content` block.
- Use immutable view models and direct generated `*Renderer.of()` calls.
- Render through `HttpSupport.renderEncoded` before headers.
- Keep `ErrorPage` independent; never use runtime lookup, service loading,
  reflection, raw output, or packaged templates.
