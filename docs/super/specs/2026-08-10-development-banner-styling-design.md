# Development banner styling design

Date: 2026-08-10 Status: approved

## Problem

The root page renders the development-authentication warning with an inline
`style` attribute. The response's strict Content Security Policy rejects inline
styles, so browsers display an unstyled warning.

## Design

Keep the strict Content Security Policy unchanged. Replace the inline style with
the semantic class `environment-banner` and link the root page to
`/assets/main.css`.

Serve one bounded static response from `Router`:

```text
GET /assets/main.css -> text/css; charset=utf-8
```

The stylesheet contains only the development banner presentation. No general
static-file server, filesystem lookup, nonce, or relaxed CSP is introduced.

## Verification

A focused HTTP test must fail before implementation, then prove:

- the root page links `/assets/main.css`;
- the warning uses `environment-banner` without inline styling;
- the stylesheet route returns CSS with the correct content type;
- the stylesheet gives the banner a red background and contrasting text;
- existing response security headers remain present.

Run focused tests, PIT against `Router.java`, then `mise run verify`. Repeat the
manual browser check after restarting the development server.

## Scope

This change fixes only the Phase 1 development warning. Dashboard styling,
general asset infrastructure, README changes, and later roadmap phases remain
out of scope.
