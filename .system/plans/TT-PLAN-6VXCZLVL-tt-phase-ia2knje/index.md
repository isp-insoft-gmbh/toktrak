---
id: TT-PLAN-6VXCZLVL
type: plan
title: Implement production packaging and release
spec: TT-SPEC-IXJLYK_K
phase: TT-PHASE-IA2KNJE_
status: done
---

1. Make `Config` and `App` bind production HTTP to the container interface while
   retaining loopback-only development binding; cover both modes and preserve
   proxy-owned TLS/compression.
2. Add a digest-pinned multi-stage `Containerfile` and minimal build context.
   Build the Linux linked runtime through `tools/Build.java`, copy only runtime
   output into a CA-capable runtime image, run as container UID `0` under
   rootless Podman user-namespace mapping, enable assertions, expose `8080`, and
   reserve `/data` for persistent state.
3. Extend `tools/Build.java` as the sole authority for image build, inspection,
   asset self-check, and rootless Podman verification. Bound subprocesses and
   output, reject missing Podman clearly, label images with integer version and
   revision, and never place secrets in arguments or logs.
4. Add container verification that starts the image with throwaway production
   config, a random published port, and a fresh Podman volume; poll `/health`,
   stop, restart against the same volume, verify health and persisted data, and
   always remove temporary containers, volumes, and secret files.
5. Define each release as an integer version with a `v`-prefixed Git and image
   tag: first `v0`, then exactly one greater than the highest release. Require a
   clean synchronized `trunk`, an exact `CHANGELOG.md` section for the next tag,
   an absent tag, and the image repository `registry.isp-insoft.de/toktrak`.
6. Add `image`, `container-verify`, and `release` mise tasks.
   `release --dry-run` performs every local preflight, verification, and build
   without tags or remote writes. A real release performs the same gates before
   creating the Git tag, pushing `registry.isp-insoft.de/toktrak:v<integer>`,
   and pushing the Git tag; retries must not create a different artifact and no
   mutable `latest` tag is published.
7. Add build-tool tests with controlled fake Git and Podman executables for
   command ordering, version gaps, dirty/diverged trees, changelog failures,
   secret redaction, cleanup, dry-run isolation, and failed external mutation.
   Keep the existing linked-runtime tests and add production bind coverage.
8. Extend production CI to build and verify the container from a clean checkout.
   Update deployment guidance with rootless Podman, container-root user mapping,
   required environment, secret generation, writable volume ownership,
   reverse-proxy boundaries, backups, upgrades, rollback by immutable
   `v<integer>` tag, and release recovery.
9. Run focused tests, mutation checks for changed Java paths, `verify`, `prod`,
   image build, `container-verify`, and release dry-run; finish with one human
   rootless deployment verification. Add no Docker path, in-app TLS/compression,
   backup implementation, migration layer, or automatic release trigger.
