---
id: TT-RESEARCH-___SRUXK
type: research
title: Mise lockfiles and Renovate maintenance
---

## Findings

| Capability                | Mise                                                                                           | Renovate                                                                                                  | Dependabot                    |
| ------------------------- | ---------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------- | ----------------------------- |
| Concrete tool versions    | Committed `mise.lock` resolves `mise.toml` selectors, including inherited task-template tools. | Reads locked versions for extracted tools.                                                                | No documented Mise ecosystem. |
| Scheduled refresh         | `mise lock --bump` advances `latest` and bounded fuzzy selectors, not exact pins.              | Lockfile maintenance can run `mise lock --bump`; opt-in required.                                         | No Mise lockfile updater.     |
| Golem task-template tools | `mise lock` includes inherited tools.                                                          | Whole-lock maintenance can advance them; individual dependency extraction omits `task_templates.*.tools`. | No support.                   |

Mise lock entries can record platform-specific download URLs and checksums;
generate relevant Linux, macOS, and Windows entries before strict CI installs
([Mise lockfile documentation](https://mise.jdx.dev/dev-tools/mise-lock.html)).
`mise-action` detects a committed lockfile and applies `--locked` to its own
install, but does not enforce strict mode on later `mise run` task-tool installs
([pinned action source](https://github.com/jdx/mise-action/blob/c2a87611a18de5b3828c5652fe268e992400cb5c/src/index.ts)).
`[tool_config] locked = true` enforces project-root lock coverage across
subsequent Mise invocations
([Mise strict lockfile mode](https://mise.jdx.dev/dev-tools/mise-lock.html#strict-lockfile-mode)).
Renovate extracts top-level tools and `tasks.*.tools`, not templates
([extractor](https://github.com/renovatebot/renovate/blob/main/lib/modules/manager/mise/extract.ts));
its lockfile maintenance delegates to Mise and supports safe-mode `--bump` when
the bot uses a sufficiently recent Mise
([artifact updater](https://github.com/renovatebot/renovate/blob/main/lib/modules/manager/mise/artifacts.ts)).
Renovate lockfile maintenance defaults to disabled
([configuration reference](https://docs.renovatebot.com/configuration-options/#lockfilemaintenance));
Dependabot's
[supported ecosystems](https://docs.github.com/en/code-security/reference/supply-chain-security/supported-ecosystems-and-repositories)
omit Mise.

## TokTrak implications

Before this change, project tools and the shared golem tool template used exact
versions, Renovate lockfile maintenance was disabled, and tracker, production,
and performance workflow filters omitted `mise.lock`.
[Project tools](../../../mise.toml) now use floating selectors with a committed
strict lockfile, [Renovate](../../../renovate.json) enables lockfile
maintenance, and those workflow filters include the lockfile. The Mise bootstrap
version remains pinned outside the lockfile. `hyperfine` uses Mise's GitHub
backend because the Aqua recipe selects an Intel binary for Apple Silicon
despite an available native release.
[Maintenance golem instructions](../../../.github/golems/_golem.md) forbid
workflow changes and merging PRs; a breaking update to its own Pi/model
combination can prevent self-repair.

## Conclusion

A committed lockfile can make floating tool requests reproducible across
development and CI, but `latest` permits incompatible major or vendor changes at
each bump. Review lockfile-only update PRs with platform and authenticated
harness checks rather than relying on ordinary CI alone. Renovate's scheduled
whole-lock maintenance is the smaller deterministic updater; a maintenance golem
can assist within its existing PR scope but cannot safely own bootstrap or
merging.

## Unresolved

- Does the deployed Renovate bot support safe-mode Mise lock maintenance and
  propose updates for inherited golem tools?
- How will future Java major upgrades stay aligned with the pinned container
  builder?
- How will trusted pre-merge checks validate subscription-backed harness models
  and thinking levels without exposing credentials to untrusted PRs?
