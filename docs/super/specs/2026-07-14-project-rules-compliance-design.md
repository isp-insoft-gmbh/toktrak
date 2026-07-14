# Project Rules Compliance Design

## Goal

Adapt every Java source file, including production code, tests, and `tools/Build.java`, to the project rules in `CLAUDE.md` without changing normal TokTrak behavior.

The work combines three approaches in strategic order:

1. Replace naturally unbounded architecture with bounded streaming and deterministic units.
2. Add explicit runtime limits, exact arithmetic, deadlines, and fault behavior.
3. Add assertions across internal boundaries, returns, state transitions, and invariants.

The design spans independent subsystems. Per explicit user direction, implementation uses one strategic execution plan with three independently verified batches rather than a roadmap. Assertions are added within each owning batch, followed by one final cross-project audit.

Tests establish behavior before each structural change. Meaningless assertions are excluded; assertions must state an actual invariant.

## Scope

Included:

- All Java under `sources/`, `tests/`, and `tools/`.
- Java launch commands in `mise.toml` and generated build invocations.
- Event-log recovery, replay, append, projection rebuild, writer lifecycle, HTTP execution, JSON constraints, configuration, build traversal, process execution, and tests.
- Deletion of superseded list-based or compatibility APIs.

Excluded:

- Product features from later roadmap phases.
- Runtime-configurable limits. Limits are fixed code constants until operational evidence requires configuration.
- Event-log compaction, rotation, or archival.
- New dependencies.

## Fixed limits

Limits live beside their owning component rather than in a shared configuration object.

| Resource | Limit | Fault |
|---|---:|---|
| Event log | 16 GiB | Startup or append fails before further mutation |
| Event count during replay | 1,000,000 | Startup fails |
| Event line, including trailing LF | 10 MiB | Startup or append fails |
| JSON document | 10 MiB minus the trailing LF | Parsing fails |
| JSON nesting depth | 32 | Parsing fails |
| JSON string | 1 MiB characters | Parsing fails |
| JSON number | 256 characters | Parsing fails |
| JSON tokens | 100,000 | Parsing fails |
| Event type | 128 UTF-8 bytes | Construction fails |
| Event actor, when present | 256 UTF-8 bytes | Construction fails |
| Top-level event data entries | 1,024 | Construction fails |
| HTTP raw and decoded request path | 2 KiB UTF-8 each | Request receives 414 |
| HTTP concurrent handlers | 64 | Excess work waits only in the bounded executor queue |
| HTTP executor queue | 256 | Dispatcher returns 503 immediately when full |
| HTTP listen backlog | 128 | Operating system rejects excess connections |
| Usage request body ceiling | 5 MiB | Request-body reader rejects before exceeding the ceiling |
| Writer queue | 1,024 | Submission is rejected immediately |
| Build tree entries | 100,000 per traversal | Build fails |
| Build input file | 512 MiB | Build fails before hashing or reading |
| Generated arguments | 10,000 arguments | Build fails before writing |
| Generated argument | 32 KiB UTF-8 | Build fails before writing |
| Generated argfile | 8 MiB UTF-8 | Build fails before writing |
| Build subprocess | 10 minutes | Process is terminated, then forcibly terminated after 5 seconds |
| Test suite | 10 minutes through the build subprocess deadline | Build fails and terminates the test JVM |

The 1,000,000-event ceiling is generous for the expected one-event-per-upload model: at 100 daily users it represents about 27 years. The repository corpus averages about 31.6 KiB per event, so 16 GiB represents roughly 14 years at that rate for 100 daily users. The byte ceiling remains authoritative because accepted upload events vary in size.

All size arithmetic uses `long` and exact arithmetic where addition or conversion could overflow.

## Event-log architecture

`EventLog` stops loading the complete file and returning an event list.

Startup flow:

1. Acquire the data-directory lock.
2. Read file metadata and reject a log above 16 GiB.
3. Inspect and repair only a bounded final torn fragment.
4. Stream bytes through one fixed-size buffer.
5. Accumulate at most one 10 MiB line.
6. Parse and apply each event directly to the projection.
7. Count replayed events with exact arithmetic and reject event 1,000,001.
8. Bind HTTP only after successful replay.

The list-returning `readAll()` and list-based projection rebuild API are deleted. Tests inspect logs through the streaming replay API.

Torn-tail recovery scans backward in fixed-size blocks and never reads the complete file. A complete malformed line still hard-fails. A final fragment longer than the line limit fails rather than silently allocating or scanning without a ceiling.

Append flow:

1. Serialize one event.
2. Validate line and resulting file sizes before opening the mutation path.
3. Write until the entire buffer is consumed; a single `FileChannel.write` is not assumed complete.
4. Force the channel.
5. Assert the observed file size and completed buffer state.
6. Only then apply the event to the projection and complete the write future.

## Projection

Projection replay becomes a one-pass state machine:

- Raw events increment the count with `Math.incrementExact` semantics implemented through `Math.addExact`.
- A compatible snapshot replaces the count after validating its exact integer range.
- An incompatible snapshot is ignored.
- Negative, fractional, overflowing, or non-integer snapshot counts fail.

The same transition logic handles startup replay and accepted live writes. This removes the current two-pass latest-snapshot scan while preserving projection results.

## JSON and event boundaries

Jackson receives explicit stream-read constraints: 32 nesting levels, 1 MiB strings, 256-character numbers, 100,000 tokens, and a document length of at most 10 MiB minus the required trailing LF. The token ceiling bounds all nested arrays and objects without recursive validation. Event-envelope constructors allow at most 1,024 top-level `data` entries and validate type and optional actor byte lengths before copying data.

Unknown fields inside event `data` remain accepted. Trust-boundary validation stays as runtime exceptions; assertions never replace it.

## HTTP execution

The server uses an explicit backlog of 128. Its JDK request executor runs the admission handler directly on the server dispatcher. The admission handler submits accepted exchanges to a `ThreadPoolExecutor` with:

- 64 virtual worker threads,
- a 256-entry `ArrayBlockingQueue`,
- immediate rejection when saturated,
- bounded shutdown.

If submission is rejected, the admission handler synchronously returns a minimal 503 response and closes the exchange. The behavior is deterministic and directly testable; rejected work is not queued elsewhere. Accepted exchanges retain existing JSON/HTML fault behavior.

Router input handling validates method and path before creating request context. It first bounds the raw URI path, then the decoded path, to 2 KiB UTF-8 each; either violation receives 414. `HttpSupport.readLimited` uses subtraction rather than potentially overflowing addition and rejects caller limits above the 5 MiB global ceiling.

Response helpers assert internal status, content type, body, and completion invariants. Existing security headers remain mandatory.

## Writer and lifecycle

The writer remains a single dedicated platform thread with a 1,024-command queue. Test-only queue capacity remains an explicit bounded argument.

Submission distinguishes a closed writer from a full queue. The writer tracks the current in-flight request, and every accepted future completes exactly once. Shutdown:

1. Stops accepting work.
2. Releases a test pause.
3. Drains accepted commands for at most 10 seconds.
4. Sets an abort flag and interrupts the writer if the deadline expires; interruptible NIO closes the active channel.
5. Waits at most 5 more seconds.
6. Completes the tracked in-flight and remaining queued futures exceptionally if they are still incomplete.
7. Prevents projection application after the abort flag is observed.
8. Marks health degraded on write or shutdown failure.

A write that crosses the forced-abort boundary has an explicitly unknown durable outcome: its future fails, no in-memory projection update follows, and restart replay determines disk truth. Later idempotent ingestion handles a retry. The writer thread is daemonized so a permanently stuck external filesystem operation cannot keep the JVM alive after bounded shutdown. Normal shutdown still drains and joins it.

Application shutdown remains idempotent and closes server, executor, writer, event log, and data lock in dependency order. Every stage has a deadline or constant-time close path.

## Build architecture

`tools/Build.java` keeps one-file source-launcher deployment but removes unbounded helpers:

- Hash files incrementally with a fixed buffer after checking the 512 MiB limit.
- Traverse directories through iterators while counting at most 100,000 entries.
- Bound lists produced from dependency directories and module discovery.
- Delete trees only after a bounded traversal succeeds.
- Wait for child processes for at most 10 minutes.
- On timeout, destroy, wait 5 seconds, then destroy forcibly and fail.
- Reject more than 10,000 generated arguments, an argument above 32 KiB UTF-8, or an argfile above 8 MiB UTF-8 before writing.
- Use exact duration and count arithmetic.

`mise.toml` launches `Build.java` with `-ea`. Build-launched TokTrak test, development, and production JVMs continue to use `-ea`; dependency resolver launches also enable assertions. The production smoke test remains assertion-enabled.

## Assertion policy

Assertions cover internal facts only:

- Non-null internal arguments and returns.
- Constructor-established field invariants.
- Queue capacities and state transitions.
- Buffer positions and exact write completion.
- Projection count range before and after transitions.
- Resource ownership and close ordering.
- Bounded collection sizes before loops.
- Valid generated command lines and paths below the project root.
- Expected process and runtime states.

Public/external inputs continue using explicit validation and recoverable exceptions. Important invariants are paired across boundaries: the producer asserts its postcondition and the consumer asserts the matching precondition.

## Tests

Changes proceed test-first in this order:

1. Streaming replay equivalence, compatible/incompatible snapshots, malformed complete lines, and torn tails.
2. Sparse oversized-log rejection without allocating 16 GiB.
3. Event-count and projection-overflow rejection through small deterministic test limits or direct transition fixtures.
4. Full-buffer append verification.
5. HTTP path rejection, executor saturation, and bounded shutdown.
6. Writer closed/full distinction, accepted-request completion, and shutdown timeout.
7. Build hashing, traversal counting, the fixed argument bounds, and process-deadline helpers where deterministic unit seams are possible without creating production abstractions solely for tests.
8. Deterministic HTTP saturation: hold all 64 workers, fill all 256 queue slots, and verify the next admitted exchange receives 503.
9. Assertion-enabled launch checks.

Tests use explicit HTTP and future timeouts. Test fixture strings, collections, loops, and files are bounded. Existing behavior tests remain unless their tested API is deliberately deleted; those tests migrate to the replacement API.

Final verification:

- `mise run check`
- `mise run verify`
- `mise run prod`
- No compiler, lint, static-analysis, or test warnings.
- No warning suppression.

## Strategic implementation order

One execution plan contains three independently reviewable batches:

1. **Bounded durable data:** regression tests, streaming event replay, one-pass projection transitions, JSON/event limits, exact arithmetic, and assertions owned by those components.
2. **Bounded runtime control:** deterministic HTTP admission, writer completion and forced-abort semantics, application shutdown, configuration limits, and assertions owned by those components.
3. **Bounded development control:** build traversal, streaming hashing, generated arguments, subprocess deadlines, test-helper limits, assertion-enabled launches, and a final cross-project assertion and naming audit.

Each batch begins with tests and ends with `check` and `verify`; the final batch also runs `prod`. Each commit-sized slice stays independently compiling and testable. No compatibility layer is retained for deleted internal APIs.
