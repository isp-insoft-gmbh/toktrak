---
id: TT-SPEC-VO3HZX3S
type: spec
title: Browser error pages
---

## Behavior

Decoded `/api/` requests keep JSON error envelopes and `/health` stays
machine-readable. Other failures, including pre-context admission failures,
return safe HTML.

Stable browser errors cover invalid method, missing route, oversized URI,
internal failure, and server saturation. Every page shows status, public
explanation, safely escaped bounded path, request ID, and home link.

Dev auth additionally shows an environment banner, stable code, method, and
bounded exception class/message. Production never exposes exception details;
development never exposes stack traces.

## Constraints

Use one deterministic bounded renderer for all 4xx/5xx statuses. Escape every
dynamic field and cap the final document. API/browser selection belongs to the
router. If response has started, close only. Keep strict CSP and existing
security headers; add no template engine, inline styles, nonce, or general
static server.

A dev-only deliberate error route supports deterministic verification and is an
ordinary 404 in production.

Accepted presentation: [diagnostic list](./mockup-diagnostic-list.html).

## Acceptance

Tests prove production/debug content boundaries, escaping, HTML versus JSON
admission behavior, API compatibility, asset/security headers, and absence of
stack traces/secrets. Human dev checks cover 404 and deliberate 500 pages.
