# GitHub Actions

- Grant each workflow only the permissions it needs.
- Pin every external action to a full commit SHA and retain its upstream tag or tracked branch in a comment.
- Give every job a measured `timeout-minutes` limit.
- Cancel superseded pull-request work. Do not cancel publication or state-rotation work in progress.
- Prefer checks for status, annotations for actionable problems, and `GITHUB_STEP_SUMMARY` for concise human reports.
- Use outputs only for small machine-readable values. Use artifacts only when data must be downloaded or passed between jobs.
- A failed optional report upload must warn, but must not override the validation command's result.
- Keep artifact retention no longer than its demonstrated review window.
- Cache only data with a bounded key space and measured reuse. Caches and artifacts have separate quotas.
- The encrypted rotating authentication in `golem.yml` is deliberate persistent state. Do not delete, disable, or change its restore/save behavior without an approved migration and reseed plan.
- This is a private repository. GitHub-hosted runners consume organization budget. Do not change runner providers or Blacksmith labels without explicit approval.
- Preserve trust boundaries: untrusted pull-request code gets read-only credentials and cannot consume privileged secrets.
