# Project Rules

- Zero warnings: fix every compiler, lint, and static-analysis warning. Disabling or suppressing warnings is forbidden without explicit human approval.

## Safety and Style

- Enable assertions (`-ea`) in every dev, test, and production launch. Assert internal arguments, returns, pre/postconditions, invariants, expected states, and forbidden states. Pair assertions across boundaries. Never replace external-input validation or recoverable error handling with assertions. Fail fast on invariant breach.
- Correctness is necessary, not sufficient: use defense in depth and runtime self-checks.
- Explicitly bound bodies, files, lines, collections, queues, concurrency, loops, retries, timeouts, and batches. Avoid recursion and unbounded work. Schedule background work at fixed intervals and process bounded batches.
- Choose fixed-width Java primitives deliberately, validate ranges, and use exact arithmetic where overflow matters.
- Keep interfaces small and define fault behavior. Isolate nondeterministic I/O behind deterministic logic; push control flow upward and data transformation downward.
- Prefer simple signatures and return types. Declare variables near first use.
- Separate control and data planes; batch I/O and computation.
- Follow Java naming conventions. Use precise nouns and verbs, suffix qualifiers (`latencyMillisMax`), and avoid abbreviations.

Adapted from [TigerStyle](https://github.com/tigerbeetle/tigerbeetle/blob/main/docs/TIGER_STYLE.md).
