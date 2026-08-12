---
id: TT-PHASE-GLEXXHIV
type: phase
title: Identity and tracker tokens
spec: TT-SPEC-IXJLYK_K
status: done
---

Approved; implementation follows the durable server core.

## Outcome

Company users authenticate, maintain account lifecycle, and manage durable
tracker tokens through protected routes.

## Scope

- OIDC authorization code with PKCE, transaction/session cookies, exact claim
  and company-domain validation, and CSRF.
- User profile/color, reactivation, self-deactivation, and active-user checks.
- HMAC tracker-token create/list/revoke, labels, lookup, and last-used state.
- Minimal login/callback/error/token-management verification surfaces.
- Fake-provider and rejection-path security tests.

Usage upload, analytics, dashboard presentation, workstation tracker, and
release packaging remain out.

## Done criteria

OIDC success/rejection, cookies, CSRF, domain/claim checks, lifecycle, HMAC
lookup, revocation, concurrent mutation, restart, and manual token management
all pass without weakening the shared authorization route.
