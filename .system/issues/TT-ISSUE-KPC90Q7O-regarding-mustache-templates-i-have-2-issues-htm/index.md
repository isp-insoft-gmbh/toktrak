---
id: TT-ISSUE-KPC90Q7O
type: issue
title: Format templates and share the base layout
specs:
  - TT-SPEC-GUMOSCIP
status: done
---

## Symptoms

- Mustache sources and rendered HTML are one unreadable line.
- Every page duplicates the document shell.

## Impact

Markup is harder to review, and shared document structure can drift.

## Evidence

`home.mustache`, `tokens.mustache`, and `created-token.mustache` each repeat the
doctype, metadata, stylesheet, environment banner, and main container.

## Expected

- Format templates with two-space indentation, structural line breaks, inline
  short text and attributes, and final newlines.
- Let static source whitespace produce readable rendered HTML. Whitespace is not
  a functional contract.
- Add one non-nested static `base.mustache` parent with one `content` block.
- Add `BaseView` containing title, stylesheet URL, and development state.
  Compose it into every page model under the field `base`; the parent reads
  `base.*`.
- Keep page-specific state in page models and `ErrorPage` independent.
- Update build validation and both template skills for this constrained static
  inheritance.
- Add no HTML parser, runtime formatter, partials, dynamic names, nested
  layouts, or recursive layouts.

## Resolution

Pages now compose `BaseView base`, inherit the single `base.mustache` layout,
and keep readable source and rendered structure. Build validation and both
skills enforce the constrained relationship.
