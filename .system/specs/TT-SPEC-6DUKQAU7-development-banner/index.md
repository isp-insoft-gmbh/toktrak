---
id: TT-SPEC-6DUKQAU7
type: spec
title: Development environment banner
---

Dev-auth pages use semantic `environment-banner` markup linked to the runtime
stylesheet. Inline styling and CSP relaxation are forbidden.

The banner must be unmistakably red with contrasting text while retaining all
response security headers. Production behavior is unchanged.

Acceptance verifies linked CSS, semantic markup, no inline style, correct media
type, visible colors, strict CSP, and a browser check after restart.
