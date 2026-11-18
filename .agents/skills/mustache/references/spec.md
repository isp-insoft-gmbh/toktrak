# Mustache reference

Authoritative sources:

- <https://mustache.github.io/mustache.5.html>
- <https://github.com/mustache/spec>

TokTrak uses escaped variables, sections (`{{#name}}`), inverted sections
(`{{^name}}`), and one constrained layout:

- `base.mustache` defines one `{{$content}}` block.
- Each page invokes `{{<base.mustache}}` once and overrides `content` once.
- Parents and block tags stay standalone so Mustache indentation rules produce
  readable output.

Normal variables remain HTML-escaped by JStachio. Other partials, parents,
blocks, dynamic names, and recursion are forbidden.
