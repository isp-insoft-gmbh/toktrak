---
id: TT-PLAN-NXMNDKFK
type: plan
title: Implement identity and tracker tokens
spec: TT-SPEC-IXJLYK_K
phase: TT-PHASE-GLEXXHIV
status: draft
---

1. Extend `Config` and `App` with bounded production OIDC, company-domain,
   session-signing, and token-pepper settings; keep dev auth explicit and reject
   every mixed production/dev configuration.
2. Expand `Projection` with users keyed by issuer and subject, active lifecycle,
   profile/color, and tracker-token metadata; rebuild all identity state from
   versioned events and disposable snapshots.
3. Add concrete OIDC handling around JDK HTTP and Nimbus: bounded discovery,
   keys, authorization-code exchange, PKCE, state, nonce, exact issuer/audience/
   time/email/domain validation, and safe failure mapping.
4. Add signed, expiring transaction and session cookies plus CSRF tokens.
   Centralize protected-route authorization so every request verifies the cookie
   and current active-user projection; re-login reactivates a known user.
5. Add token creation, listing, revocation, and authentication through `Writer`.
   Generate 256 random bits, present plaintext once, persist only peppered
   HMACs, compare digests in constant time, bound labels, and record last use.
6. Extend `Router` with minimal login, callback, logout, account deactivation,
   token-management, and verification surfaces while preserving shared limits,
   browser/API errors, headers, request IDs, and the visible dev-auth banner.
7. Add fake-provider, projection/replay, HTTP, concurrency, and restart tests
   for OIDC rejection paths, cookies, CSRF, domain claims, reactivation,
   deactivation, token secrecy, revocation, and active-user enforcement; run
   focused mutation tests, full verification, production runtime checks, and a
   manual token-management checkpoint.

Keep usage ingestion, analytics, dashboard UI, tracker installation, admin
roles, and individual-session revocation absent. Add no auth framework,
database, compatibility path, or secret-bearing log/event.
