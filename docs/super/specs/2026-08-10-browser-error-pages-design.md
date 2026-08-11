# Browser error pages design

Date: 2026-08-10 Status: approved

## Goal

Make browser errors useful to humans in production and diagnostic in development
without changing JSON API errors or weakening browser security.

## HTTP behavior

Requests whose decoded path starts with `/api/` keep the existing JSON error
envelope. Other requests receive HTML error pages, including admission failures
that occur before request-context binding. An overlong URI displays a fixed
bounded path description instead of echoing the rejected URI.

Stable browser errors are:

- `400 invalid_method`: Request method is invalid.
- `404 not_found`: Route not found.
- `414 uri_too_long`: Request URI is too long.
- `500 internal_error`: Internal server error. Try again.
- `503 server_busy`: Server is busy. Try again.

Every browser error page shows:

- HTTP status and a plain-language explanation;
- the safely escaped requested path;
- the request ID for support correlation;
- a link to `/`.

When `TOKTRAK_DEV_AUTH=true`, the page additionally shows:

- a `DEV AUTH · DEBUG` environment banner;
- stable error code;
- request method;
- exception class and message when a failure exists.

Production pages never include exception details. Development pages never
include stack traces. Project rules forbid secrets in exception messages.

## Components

Add `toktrak.http.ErrorPage`, a deterministic renderer with no I/O. It accepts
the status, stable code, public message, request ID, method, display path,
optional failure, and debug-mode flag. It validates bounded inputs, HTML-escapes
every dynamic value, limits displayed exception messages to 8 KiB, and returns
one bounded HTML document. Accepting values instead of `RequestContext` lets
admission failures use the same renderer before scoped context exists.

`Router` selects the existing JSON envelope for API failures and `ErrorPage` for
browser failures. Unknown browser routes use the renderer instead of the fixed
404 string. Unexpected browser failures also use the renderer when no response
has started.

Development mode adds `GET /debug/error`, which deliberately throws the
public-safe `IllegalStateException("debug failure")`. The route is absent in
production. It provides deterministic HTTP, mutation, and manual verification of
the 500 page without a production failure hook.

`/assets/main.css` remains the single stylesheet. Extend its bounded in-memory
CSS with the accepted diagnostic-list presentation. Keep Content Security Policy
unchanged and add no filesystem asset server, template engine, nonce, or inline
style.

## Failure behavior

Rendering must not throw because attacker-controlled method, path, or exception
text contains HTML. Escape `&`, `<`, `>`, double quotes, and single quotes.
Reject invalid internal status/code/message/context arguments with assertions or
stable argument exceptions according to existing trust-boundary conventions.

If an API handler fails before responding, return the existing JSON
`internal_error` envelope. If a browser handler fails before responding, return
a 500 HTML page. If a response already started, preserve the existing close-only
behavior.

## Verification

Use TDD. Focused tests prove:

- production 404 pages contain useful status, explanation, path, request ID, and
  home link without debug fields;
- development 404 pages contain the accepted diagnostic list;
- development `/debug/error` returns a 500 page with escaped exception
  class/message but no stack trace;
- production `/debug/error` remains an ordinary 404 and omits exception details;
- dynamic HTML is escaped;
- browser admission errors are HTML while API admission errors remain JSON;
- API 404 behavior remains JSON;
- the stylesheet and security headers remain valid.

Run focused tests, PIT for changed production sources, `mise run verify`, then
manually open `/nope` and `/debug/error` in development mode.

## Scope

This change covers browser error presentation only. Logging, API error shapes,
exception taxonomy, general templates, dashboard UI, README content, and later
roadmap phases remain unchanged.

## Mockups

- [Diagnostic list](./2026-08-10-browser-error-pages-design-mockup-diagnostic-list.html)
