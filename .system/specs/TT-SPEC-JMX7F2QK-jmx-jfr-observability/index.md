---
id: TT-SPEC-JMX7F2QK
type: spec
title: JMX and JFR observability
---

## Intent

Keep production small while permitting temporary VisualVM, Java Mission Control,
and in-container diagnostic sessions.

## Requirements

- Include `jdk.management.agent`, `jdk.management.jfr`, and `jdk.jcmd` in the
  production linked runtime.
- Keep remote management disabled by default and publish no management port in
  the image.
- Enable remote JMX only for an explicit diagnostic run with one fixed JMX/RMI
  port published on host loopback. Use SSH port forwarding for remote hosts;
  never publish unauthenticated JMX publicly.
- Permit JMC to control JFR through JMX and permit `jcmd`/`jfr` execution
  through `podman exec` without requiring a shell in the image.
- Verify linked modules and launchers during production builds.

## Acceptance

A container started with temporary JMX options accepts a host JMX connection,
exposes the Flight Recorder MXBean, and answers `jcmd`. Normal containers expose
only HTTP and start no remote management listener.

## Exclusions

No always-on JMX, public management port, bundled GUI tooling, or monitoring
platform integration.
