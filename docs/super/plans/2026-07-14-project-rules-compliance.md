# Project Rules Compliance Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use /skill:executing-plans to
> implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for
> tracking.

**Goal:** Bound and self-check every Java runtime, build, and test path required
by the approved project-rules design.

**Architecture:** Stream event replay directly into a one-pass projection, place
fixed limits beside each owning component, admit HTTP work through a bounded
virtual-thread pool, and bound writer/build shutdown. Runtime validation
protects external boundaries; paired assertions protect internal contracts.

**Tech Stack:** Java 26, JPMS, JDK `HttpServer`, Jackson 2.22.1, JUnit 6,
source-file `tools/Build.java`, mise

**Roadmap:** None — the user explicitly requested one strategic execution plan.

**Phase:** Single-plan implementation

---

## Tasks

### Task 1: Stream Event Replay and Projection

**Files:**

- Modify: `tests/toktrak.tests/toktrak/tests/EventLogTest.java`
- Modify: `tests/toktrak.tests/toktrak/tests/ProjectionTest.java`
- Modify: `tests/toktrak.tests/toktrak/tests/RestartAndLockTest.java`
- Modify: `tests/toktrak.tests/toktrak/tests/ShutdownTest.java`
- Modify: `tests/toktrak.tests/toktrak/tests/WriterTest.java`
- Modify: `sources/toktrak/toktrak/store/EventLog.java`
- Modify: `sources/toktrak/toktrak/projection/Projection.java`
- Modify: `sources/toktrak/toktrak/App.java`

- [ ] **Step 1: Write failing streaming, count-limit, and partial-write tests**

Add tests that call the desired API:

```java
var replayed = new ArrayList<EventEnvelope>();
assertEquals(2, log.replay(replayed::add));
assertEquals(List.of(first, second), replayed);
```

Add a sparse oversized-file test using:

```java
FileChannel.position(EventLog.MAX_FILE_BYTES).write(ByteBuffer.wrap(new byte[] {0}))
```

Assert `event log exceeds 17179869184 bytes`. Call a test-limit replay seam with
four valid events and a limit of three; assert rejection on event four without
creating one million records.

Use a test `WritableByteChannel` that consumes at most three bytes per `write`
call. Pass it and a larger buffer to the write-fully seam; assert the complete
buffer arrives and multiple writes occurred. Add projection tests that replay an
incompatible snapshot and that apply a raw event after `Integer.MAX_VALUE`,
expecting `ArithmeticException`.

- [ ] **Step 2: Run verification and observe RED**

Run: `mise run verify`

Expected: compilation fails because `EventLog.replay`,
`EventLog.MAX_FILE_BYTES`, and one-pass projection behavior do not exist.

- [ ] **Step 3: Implement bounded streaming replay**

Replace `readAll()` with:

```java
public int replay(Consumer<EventEnvelope> consumer)
```

Use fixed constants:

```java
public static final long MAX_FILE_BYTES = 16L * 1024 * 1024 * 1024;
public static final int MAX_LINE_BYTES = 10 * 1024 * 1024;
public static final int MAX_EVENT_COUNT = 1_000_000;
private static final int READ_BUFFER_BYTES = 64 * 1024;
```

Make `recoverTornTail` read and reject file size above `MAX_FILE_BYTES` as its
first action, before opening a writable channel or truncating. Replay repeats
the size check before allocation. Read at most the observed size through a 64
KiB buffer, append bounded byte segments to one line buffer, require LF
termination, parse each complete line, increment with `Math.addExact`, reject
above `MAX_EVENT_COUNT`, call the non-null consumer, and verify the file size is
unchanged after replay. Return the replayed count.

Add a narrowly named test seam that delegates to replay with a smaller positive
event limit; production replay always passes `MAX_EVENT_COUNT`. Rewrite
torn-tail recovery with bounded backward `FileChannel` block reads. Count at
most `MAX_LINE_BYTES` bytes before finding LF; reject a larger fragment. Define
line bytes as JSON bytes plus trailing LF.

Rewrite append to precompute the exact resulting file size, reject above
`MAX_FILE_BYTES`, and delegate to a write-fully helper that accepts
`WritableByteChannel`. Expose only a narrowly named test seam for that helper.
Loop while the `ByteBuffer` has remaining bytes, fail on a zero-progress write,
force the real file channel, and assert the buffer was consumed.

- [ ] **Step 4: Implement one-pass exact projection transitions**

Delete list-based `Projection.rebuild`. Make `apply` ignore incompatible
snapshots, validate snapshot numbers as exact integral `int` values, and
increment raw events with:

```java
eventCount = Math.addExact(eventCount, 1);
```

Change `App.start` to:

```java
Projection projection = Projection.empty();
eventLog.replay(projection::apply);
```

Migrate all tests from `readAll()` to replay-count or collected replay results.

- [ ] **Step 5: Run focused and full tests**

Run: `mise run verify`

Expected: all event-log, projection, restart, shutdown, and writer tests pass;
no warnings.

- [ ] **Step 6: Commit**

```bash
git add sources/toktrak/toktrak/App.java sources/toktrak/toktrak/store/EventLog.java sources/toktrak/toktrak/projection/Projection.java tests/toktrak.tests/toktrak/tests
git commit -m "refactor: bound event replay"
```

### Task 2: Bound JSON and Event Envelopes

**Files:**

- Modify: `tests/toktrak.tests/toktrak/tests/EventLogTest.java`
- Create: `tests/toktrak.tests/toktrak/tests/EventEnvelopeTest.java`
- Modify: `sources/toktrak/toktrak/json/Json.java`
- Modify: `sources/toktrak/toktrak/store/EventEnvelope.java`

- [ ] **Step 1: Write failing JSON and envelope tests**

Test a 33-level JSON value, a 257-character number, a type above 128 UTF-8
bytes, an actor above 256 UTF-8 bytes, and data with 1,025 entries. Assert
`IllegalStateException` for JSON parsing and `IllegalArgumentException` for
envelope construction.

- [ ] **Step 2: Run verification and observe RED**

Run: `mise run verify`

Expected: the new limit tests fail because current Jackson and envelope
construction accept them.

- [ ] **Step 3: Configure Jackson read constraints**

Construct the mapper from a constrained `JsonFactory`:

```java
var constraints = StreamReadConstraints.builder()
    .maxNestingDepth(32)
    .maxDocumentLength(EventLog.MAX_LINE_BYTES - 1L)
    .maxTokenCount(100_000)
    .maxNumberLength(256)
    .maxStringLength(1024 * 1024)
    .build();
var factory = JsonFactory.builder().streamReadConstraints(constraints).build();
```

Retain the existing `Instant` serializer/deserializer. Assert mapper and module
construction results are non-null.

- [ ] **Step 4: Validate event fields before copying**

Add fixed constants for 128 type bytes, 256 actor bytes, and 1,024 top-level
data entries. Bound UTF-8 lengths without integer overflow, validate actor only
when non-null, then `Map.copyOf`. Keep schema-version and blank-type runtime
validation.

- [ ] **Step 5: Run verification and commit**

Run: `mise run verify`

Expected: all tests pass with no warnings.

```bash
git add sources/toktrak/toktrak/json/Json.java sources/toktrak/toktrak/store/EventEnvelope.java tests/toktrak.tests/toktrak/tests/EventLogTest.java tests/toktrak.tests/toktrak/tests/EventEnvelopeTest.java
git commit -m "feat: bound persisted JSON"
```

### Task 3: Deterministic Bounded HTTP Admission

**Files:**

- Modify: `tests/toktrak.tests/toktrak/tests/BodyLimitTest.java`
- Modify: `tests/toktrak.tests/toktrak/tests/HttpServerTest.java`
- Create: `tests/toktrak.tests/toktrak/tests/HttpAdmissionTest.java`
- Modify: `sources/toktrak/toktrak/App.java`
- Modify: `sources/toktrak/toktrak/http/HttpSupport.java`
- Modify: `sources/toktrak/toktrak/http/Router.java`

- [ ] **Step 1: Write failing body, path, and saturation tests**

Assert `HttpSupport.readLimited` rejects a caller limit above 5 MiB. Send an
ASCII raw path above 2 KiB and assert 414.

For deterministic saturation, create the production-sized 64-worker, 256-queue
`ThreadPoolExecutor`, occupy all 64 workers with one latch, fill all 256 queue
slots, run a real local `HttpServer` with direct execution and `Router`, send
request 321, and assert 503. Assert the executor reports exactly 64 active
workers and 256 queued tasks before the request. Release the latch and close
every resource in `finally`.

- [ ] **Step 2: Run verification and observe RED**

Run: `mise run verify`

Expected: body ceiling, path ceiling, and 503 saturation tests fail.

- [ ] **Step 3: Make body arithmetic bounded and overflow-safe**

Add `MAX_REQUEST_BODY_BYTES = 5 * 1024 * 1024`. Reject negative or larger
limits. Replace `output.size() + read > limit` with:

```java
if (read > limit - output.size()) {
  throw new IllegalArgumentException("request body exceeds " + limit + " bytes");
}
```

Assert exchange, content type, body, status range, headers, and response-body
completion at internal response boundaries.

- [ ] **Step 4: Dispatch admitted exchanges through the bounded worker pool**

Give `Router` the worker `Executor`. Its synchronous `handle` method validates
raw and decoded paths, then submits an asynchronous accepted-handler task. Catch
`RejectedExecutionException`, synchronously return 503, and close. The worker
path creates request context, routes, handles internal error, and closes exactly
once.

Use a character-count precheck before UTF-8 encoding each path. Return 414 if
raw or decoded UTF-8 length exceeds 2,048 bytes.

- [ ] **Step 5: Configure production admission bounds**

In `App.start`, create `HttpServer` with backlog 128. Create a
`ThreadPoolExecutor` with 64 core/max virtual workers, zero keepalive, a
256-entry `ArrayBlockingQueue`, and abort policy. Set the JDK server executor to
direct execution and pass the bounded worker executor to `Router`.

- [ ] **Step 6: Run verification and commit**

Run: `mise run verify`

Expected: all tests pass; saturation returns 503 deterministically.

```bash
git add sources/toktrak/toktrak/App.java sources/toktrak/toktrak/http tests/toktrak.tests/toktrak/tests/BodyLimitTest.java tests/toktrak.tests/toktrak/tests/HttpServerTest.java tests/toktrak.tests/toktrak/tests/HttpAdmissionTest.java
git commit -m "feat: bound HTTP admission"
```

### Task 4: Bound Writer and Application Shutdown

**Files:**

- Modify: `tests/toktrak.tests/toktrak/tests/ShutdownTest.java`
- Modify: `tests/toktrak.tests/toktrak/tests/WriterTest.java`
- Modify: `sources/toktrak/toktrak/store/Writer.java`
- Modify: `sources/toktrak/toktrak/App.java`
- Modify: `sources/toktrak/toktrak/health/HealthState.java`

- [ ] **Step 1: Write failing writer-state and forced-abort tests**

Add tests that distinguish `writer is closed` from `writer queue is full`,
verify every accepted future is complete after close, and verify close remains
idempotent. Use explicit two-second future timeouts.

For forced abort, use a `ClockSource` that signals entry then blocks on a latch
while ignoring interruption. Start the writer through a test seam with 20 ms
drain and 20 ms abort deadlines. Submit one command, wait until the clock is
entered, close, and assert: the future fails, the projection count remains zero,
the log replays zero events, and close returns within a one-second test
deadline. Release the clock latch in `finally`.

- [ ] **Step 2: Run verification and observe RED**

Run: `mise run verify`

Expected: the closed-writer message test fails.

- [ ] **Step 3: Track in-flight work and exact completion**

Add an `AtomicReference<Request>` for the in-flight request, an abort flag, and
a state monitor shared by submission and close. Validate queue capacity as
positive and no larger than 1,024. Inside one synchronized state-monitor block,
check closed and offer; close sets closed under the same monitor before the
worker can observe terminal empty state. This prevents an offer after worker
exit. Around processing, assert successful `compareAndSet(null, request)`, clear
in `finally`, and assert each `CompletableFuture.complete*` call wins only where
ownership guarantees first completion.

Add a test-only start overload taking positive drain and abort `Duration`
values; production passes 10 and 5 seconds. Use
`Math.addExact(System.nanoTime(), timeoutNanos)` with a saturating deadline
helper when exact addition overflows. Replace spin-yield test pausing with
bounded monitor waits.

- [ ] **Step 4: Bound forced shutdown**

Drain for 10 seconds, then set abort, interrupt, and wait 5 seconds. If still
incomplete, fail the tracked in-flight and queued requests, degrade health, and
rely on daemon thread termination. Check abort after append and before
projection apply so forced-abort disk truth is recovered only on restart.

Keep normal accepted writes append/fsync/apply/complete ordered. Preserve
interrupt status.

- [ ] **Step 5: Bound application executor shutdown and close ordering**

Keep the 10-second HTTP-worker deadline, force `shutdownNow`, and assert final
lifecycle state. Preserve nested close ordering so data lock closes last. Add
non-null constructor assertions and port-range postconditions.

- [ ] **Step 6: Run verification and commit**

Run: `mise run verify`

Expected: all writer and shutdown tests pass; no thread remains non-daemon after
forced shutdown.

```bash
git add sources/toktrak/toktrak/App.java sources/toktrak/toktrak/store/Writer.java sources/toktrak/toktrak/health/HealthState.java tests/toktrak.tests/toktrak/tests/ShutdownTest.java tests/toktrak.tests/toktrak/tests/WriterTest.java
git commit -m "fix: bound writer shutdown"
```

### Task 5: Bound Build Inputs and Processes

**Files:**

- Modify: `mise.toml`
- Modify: `tools/Build.java`
- Create: `tests/tools/BuildTest.java`

- [ ] **Step 1: Write failing standalone build-helper tests**

Create a default-package `BuildTest` with a `main` method and Java assertions.
It calls the desired package-private helpers to verify: a sparse 512
MiB-plus-one file is rejected before hashing; traversal entry 100,001 is
rejected using a temporary directory or a small injected maximum; argument
10,001, a 32 KiB-plus-one UTF-8 argument, and an 8 MiB-plus-one argfile are
rejected; a short-lived process exits normally; and a deliberately sleeping
child is terminated using millisecond test deadlines.

Compile and run directly:

```bash
rm -rf output/build-tests
javac -Xlint:all -Werror -d output/build-tests tools/Build.java tests/tools/BuildTest.java
java -ea -cp output/build-tests BuildTest
```

Expected: compilation fails because the package-private bounded helpers do not
exist.

- [ ] **Step 2: Add build-owned fixed limits and assertion enforcement**

Add constants:

```java
private static final long FILE_BYTES_MAX = 512L * 1024 * 1024;
private static final int TREE_ENTRIES_MAX = 100_000;
private static final int ARGUMENTS_MAX = 10_000;
private static final int ARGUMENT_BYTES_MAX = 32 * 1024;
private static final int ARGFILE_BYTES_MAX = 8 * 1024 * 1024;
private static final long PROCESS_TIMEOUT_SECONDS = 600;
private static final long PROCESS_KILL_TIMEOUT_SECONDS = 5;
private static final int COPY_BUFFER_BYTES = 64 * 1024;
```

At `Build.main` entry, require assertions with the same fail-fast pattern as
`Main`. Bound incoming command arguments before indexing. Change every mise task
command from `java tools/Build.java ...` to `java -ea tools/Build.java ...`.

- [ ] **Step 3: Stream bounded hashing**

Replace every `Files.readAllBytes` used for fingerprints with a helper that
checks regular-file size against 512 MiB and updates `MessageDigest` through a
64 KiB buffer. Assert exact bytes read equals the prechecked size and fail if
the file changes during hashing.

- [ ] **Step 4: Bound traversals and generated collections**

Replace `Files.walk(...).toList()` and `Files.list(...).toList()` with counted
iteration. Fail at entry 100,001 before materializing more paths. For deletion,
collect only after the bounded scan succeeds, sort reverse, then delete. Bound
module names, roots, module paths, and generated arguments before loops.

Before writing an argfile, reject argument 10,001, any UTF-8 argument above 32
KiB, or total encoded output above 8 MiB using exact arithmetic. Build content
with `StringBuilder`, not reduction concatenation.

- [ ] **Step 5: Bound subprocess execution**

Use `process.waitFor(10, TimeUnit.MINUTES)`. On timeout call `destroy`, wait
five seconds, then `destroyForcibly` and wait five seconds once more. Fail with
a stable timeout message. Preserve interrupts, terminate the process, and
restore interrupt status. Add `-ea` before `-jar` for the dependency-resolver
JVM.

- [ ] **Step 6: Integrate and run build-helper tests**

Run `BuildTest` from `Build.test()` before application tests: compile
`tools/Build.java` and `tests/tools/BuildTest.java` into a bounded temporary
module-free directory, launch it with `-ea`, and apply the same process
deadline. Re-run the direct compile command from Step 1.

Expected: `BuildTest` exits zero with no warnings.

- [ ] **Step 7: Verify real build paths and commit**

Run: `mise run clean && mise run check && mise run verify && mise run prod`

Expected: all commands finish without warnings; all launched project JVMs
require assertions.

```bash
git add mise.toml tools/Build.java tests/tools/BuildTest.java
git commit -m "build: bound inputs and subprocesses"
```

### Task 6: Cross-Project Assertions, Naming, and Test Bounds

**Files:**

- Modify: all remaining Java files under `sources/toktrak/`
- Modify: all Java files under `tests/toktrak.tests/`
- Modify: `tests/toktrak.tests/toktrak/tests/DevDataTest.java`
- Modify: `sources/toktrak/toktrak/dev/DevData.java`
- Modify: `tools/Build.java`

- [ ] **Step 1: Audit every loop, collection, wait, file, and public boundary**

Use bounded searches:

```bash
rg -n "for \(|while \(|Files\.(readAllBytes|readString|writeString|copy|walk|list)|\.wait\(|\.join\(|\.get\(|new ArrayList|new HashMap|new .*Queue" sources tests tools
```

For each result, identify its owning bound. Add runtime validation at
external/public boundaries and `assert` at internal producer/consumer
boundaries. Delete unused imports, single-use wrappers, and redundant branches
encountered in touched flows.

Replace `DevData` corpus `Files.copy` with a 64 KiB buffered copy bounded by
`EventLog.MAX_FILE_BYTES`, verify exact source size stability, write a sibling
temporary file, then atomically replace `events.ndjson`. Add tests for oversized
sparse corpus rejection and preservation of an existing destination when bounded
copy fails. Replace every build stamp `Files.readString` with a helper that
bounds the stamp to 128 UTF-8 bytes before comparison.

- [ ] **Step 2: Add missing production assertions without replacing validation**

Assert constructor-established fields, internal non-null arguments/returns,
projection and queue ranges, request-context binding, health state transitions,
generated paths below project root, resource state before/after close, and
impossible switch/default states. Pair postconditions with consumer
preconditions.

Use precise names with qualifier suffixes such as `fileBytesMax`,
`timeoutSeconds`, `eventCountMax`, and `pathBytesMax`. Keep fixed-width `int`
for bounded counts and `long` for bytes/nanoseconds.

- [ ] **Step 3: Bound test execution and helpers**

Set JUnit default test timeout to 10 seconds in `TestLauncher` before discovery.
Reject zero tests, more than 10,000 tests, or any failures. Give every
`HttpClient` a connect timeout and every request a response timeout. Give every
future a timeout. Bound generated test files and loops by named test constants.

- [ ] **Step 4: Format and verify**

Run the repository formatter available through the installed
`google-java-format` mise tool on all changed Java files, then run:

```bash
mise run check
mise run verify
mise run prod
```

Expected: zero warnings, zero failed tests, assertion-enabled production smoke
test.

- [ ] **Step 5: Inspect and commit final audit**

Run:

```bash
git diff --check
git status --short
git diff --stat
git diff
```

Confirm only intentional source, test, build, and plan changes remain.

```bash
git add sources tests tools mise.toml docs/super/plans/2026-07-14-project-rules-compliance.md
git commit -m "refactor: enforce project invariants"
```
