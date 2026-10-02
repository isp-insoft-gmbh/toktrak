# Java Golems

`tools/golems/Golems.java` owns selection, candidate staging, guard checks, and
PR publication. `tools/golems/Auth.java` owns rotating subscription cache
encryption and decryption. Both are JDK source launchers with no application
libraries, `jq`, or Node dependency. Git and gh are required orchestration
executables; the selected harness CLI is also required.

`mise run golem <name> --local` runs a task without publishing.
`mise run golem <name>` allows publication and requires `GH_TOKEN`.
`mise run golem-auth-check <name>` probes the task's subscription and model
without tools. `java -ea tools/golems/Golems.java select --event schedule`
explains the UTC schedule and prints the GitHub Actions matrix on the final
line. `mise run verify` runs `SelfCheck.java` with isolated scratch repositories
and fake authentication.

Branch-writing tasks start at a pinned remote or local base in a detached
worktree under the run directory. The original checkout, its branch, and its
index remain unchanged. The candidate is retained when a local or failed run
needs recovery; the report prints its path. A clean startup, a clean committed
result, an unchanged control plane, a passing declared check, the default-branch
guard, resolved PR review threads, and an ordinary non-forced push are required
before PR publication. PR metadata updates and reports use the existing GitHub
App permissions; the golem never merges its PR.

The CI workflow restores Pi and Codex authentication from the same versioned
AES-GCM cache format used by the previous JS runner. The cache key never enters
task definitions or source control. After every run, CI encrypts the potentially
rotated authentication file again and saves a new cache key. If a cache is
missing or decryption fails, run the existing `reseed-pi` or `reseed-codex`
manual workflow with a fresh secret.

The manual-only `.golems/canary.md` creates or updates `golem/java-canary` for a
documentation-only, unmerged PR. Dispatch it after landing this cutover and
inspect its CI run, PR, and Pi authentication rotation. Schedules and non-canary
dispatches are blocked until a maintainer sets the repository variable
`GOLEM_JAVA_CUTOVER=enabled` after reviewing the canary. The old
`tools/golem.mjs` and `.github/golems/` definitions remain as a rollback
reference during the canary phase; CI and Mise task entrypoints use Java. Do not
delete the old runner or definitions before an actual CI canary proves
authentication rotation and publication.
