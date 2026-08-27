---
id: TT-PLAN-KDGP7NFZ
type: plan
title: Implement runtime assets
spec: TT-SPEC-ZWZWS6FD
status: approved
---

1. Test and implement sorted source discovery, path/size/format/SVG validation,
   actionable diagnostics, canonical indexing, and exact copying.
2. Include asset names/bytes/outputs in compile and link cache integrity.
3. Eagerly load immutable public/private assets before HTTP binding and verify
   index lengths/hashes/types.
4. Serve exact fingerprinted GET URLs with immutable cache/security headers;
   reject query, alternate, private, stale, and noncanonical routes.
5. Prove resources survive the linked runtime through an explicit production
   asset check.
6. Run build/application tests, mutation analysis, full verification, linked
   production build, and human browser cache/style checks.

No bundler, minifier, converter, filesystem serving, ETag, or automatic repair.
