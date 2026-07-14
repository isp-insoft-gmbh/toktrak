# TokTrak Phase 1: Java HTTP Persistence Core

> **For agentic workers:** REQUIRED SUB-SKILL: Use /skill:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add the Java 26 build/runtime foundation and a headless dev server that persists event envelopes, rebuilds one projection, and reports write failures through `/health`.

**Architecture:** A single explicit JPMS module (`toktrak`) uses JDK `HttpServer` with virtual-thread request handling. A dedicated platform-thread writer serializes append/fsync/apply operations to an NDJSON event log; startup exclusively locks the data directory, repairs only a torn final line, replays compatible state, then binds HTTP.

**Tech Stack:** Java 26, JPMS, JDK `HttpServer`, virtual threads, scoped values, Jackson core/databind/annotations, Nimbus JOSE JWT, JUnit Platform Console, `mise`, and vendored `jresolve.jar`.

**Roadmap:** `docs/super/roadmaps/2026-07-13-toktrak-roadmap.md`

**Phase:** Phase 1: Durable Server Core

---

## Phase boundary

This plan intentionally covers only Phase 1. It must not add OIDC, users, tracker tokens, usage ingestion, SSE, dashboard UI, workstation tracker, container release, or production OIDC env validation.

## Dependency version note

The approved spec says `jackson-annotations 2.22.1`, but Maven metadata currently exposes `jackson-annotations 2.22` while `jackson-core` and `jackson-databind` expose `2.22.1`. Pin `jackson-annotations@2.22` and keep this note until the spec is corrected or that artifact is published.

## File structure

- Modify `mise.toml`: map `mise run install/build/verify/dev/link-prod` to the Java build script.
- Create `tools/Build.java`: cross-platform build/test/link/dev helper; no Bash scripts in the repo.
- Create `sources/main-deps.txt`: production dependency coordinates for `jresolve`.
- Create `sources/test-deps.txt`: JUnit console dependency coordinate.
- Create `sources/toktrak/module-info.java`: explicit app module.
- Create `sources/toktrak/toktrak/Main.java`: CLI entrypoint.
- Create `sources/toktrak/toktrak/App.java`: lifecycle orchestration: config, lock, event store, writer, HTTP server.
- Create `sources/toktrak/toktrak/Config.java`: env/CLI/dev validation.
- Create `sources/toktrak/toktrak/ClockSource.java`: system or pinned dev clock.
- Create `sources/toktrak/toktrak/json/Json.java`: one Jackson mapper and compact JSON helpers.
- Create `sources/toktrak/toktrak/log/JsonLogFormatter.java`: compact stdout JSON logs.
- Create `sources/toktrak/toktrak/http/RequestContext.java`: scoped request metadata.
- Create `sources/toktrak/toktrak/http/Router.java`: brutal route dispatch and shared error path.
- Create `sources/toktrak/toktrak/http/ApiError.java`: `{"error":{"code","message","requestId"}}` envelope.
- Create `sources/toktrak/toktrak/http/HttpSupport.java`: body limits, security headers, JSON/HTML responses.
- Create `sources/toktrak/toktrak/health/HealthState.java`: healthy/degraded reason state.
- Create `sources/toktrak/toktrak/store/DataLock.java`: exclusive `toktrak.lock` lifetime lock.
- Create `sources/toktrak/toktrak/store/EventEnvelope.java`: event envelope model and validation.
- Create `sources/toktrak/toktrak/store/EventLog.java`: append/fsync/read/recover NDJSON.
- Create `sources/toktrak/toktrak/store/Writer.java`: bounded single-consumer writer queue.
- Create `sources/toktrak/toktrak/store/WriteCommand.java`: command result and event factory.
- Create `sources/toktrak/toktrak/projection/Projection.java`: event-count projection and snapshot support for Phase 1.
- Create `sources/toktrak/toktrak/dev/DevData.java`: disposable dev data copy and `--fail-writes` injection.
- Create tests under `tests/toktrak/*.java` matching the tasks below.

## Task 1: Build skeleton and JPMS dependency gate

**Files:**

- Modify: `mise.toml`
- Create: `tools/Build.java`
- Create: `sources/main-deps.txt`
- Create: `sources/test-deps.txt`
- Create: `sources/toktrak/module-info.java`
- Create: `sources/toktrak/toktrak/Main.java`

- [ ] **Step 1: Write dependency files**

`sources/main-deps.txt`:

```text
pkg:maven/com.fasterxml.jackson.core/jackson-core@2.22.1
pkg:maven/com.fasterxml.jackson.core/jackson-databind@2.22.1
pkg:maven/com.fasterxml.jackson.core/jackson-annotations@2.22
pkg:maven/com.nimbusds/nimbus-jose-jwt@10.9.1
```

`sources/test-deps.txt`:

```text
pkg:maven/org.junit.platform/junit-platform-console-standalone@6.0.0
```

- [ ] **Step 2: Write module descriptor**

`sources/toktrak/module-info.java`:

```java
module toktrak {
  requires com.fasterxml.jackson.annotation;
  requires com.fasterxml.jackson.core;
  requires com.fasterxml.jackson.databind;
  requires com.nimbusds.jose.jwt;
  requires java.logging;
  requires jdk.httpserver;

  exports toktrak;
  exports toktrak.health;
  exports toktrak.store;
  exports toktrak.projection;
}
```

- [ ] **Step 3: Add minimal entrypoint**

`sources/toktrak/toktrak/Main.java`:

```java
package toktrak;

public final class Main {
  private Main() {}

  public static void main(String[] args) throws Exception {
    try (var app = App.start(args, System.getenv())) {
      Thread.currentThread().join();
    }
  }
}
```

- [ ] **Step 4: Add cross-platform build driver**

`tools/Build.java` must implement these commands with `ProcessBuilder`, `Files.walk`, and no shell-specific commands:

```text
java tools/Build.java deps        # resolve prod + test deps into output/deps/{main,test}
java tools/Build.java compile     # compile module sources into output/classes
java tools/Build.java test        # compile tests into output/test-classes and launch JUnit
java tools/Build.java jlink-prod  # link output/runtimes/prod and reject automatic modules
java tools/Build.java dev -- ...  # compile then run toktrak.Main with forwarded args
```

Use `jar --describe-module --file <jar> --release 9`; fail if output contains `automatic` or no module name line. Build script exits non-zero on first failed child process and prints the exact command.

- [ ] **Step 5: Wire mise tasks**

`mise.toml`:

```toml
[tools]
coursier = "latest"
google-java-format = "latest"
java = "latest"

[tasks.install]
run = "java tools/Build.java deps"

[tasks.build]
run = "java tools/Build.java compile"

[tasks.verify]
run = "java tools/Build.java test"

[tasks.link-prod]
run = "java tools/Build.java jlink-prod"

[tasks.dev]
run = "java tools/Build.java dev --"
```

- [ ] **Step 6: Run dependency gate**

Run: `mise run install`

Expected: prod jars copied under `output/deps/main`, test jar under `output/deps/test`, and no automatic-module rejection.

- [ ] **Step 7: Run build and observe expected missing `App` failure**

Run: `mise run build`

Expected: FAIL because `toktrak.App` does not exist yet.

- [ ] **Step 8: Commit**

```sh
git add mise.toml tools/Build.java sources/main-deps.txt sources/test-deps.txt sources/toktrak/module-info.java sources/toktrak/toktrak/Main.java
git commit -m "build: add java module build gate"
```

## Task 2: Config, clock, JSON, and logging

**Files:**

- Create: `sources/toktrak/toktrak/App.java`
- Create: `sources/toktrak/toktrak/Config.java`
- Create: `sources/toktrak/toktrak/ClockSource.java`
- Create: `sources/toktrak/toktrak/json/Json.java`
- Create: `sources/toktrak/toktrak/log/JsonLogFormatter.java`
- Test: `tests/toktrak/ConfigTest.java`
- Test: `tests/toktrak/JsonLogFormatterTest.java`

- [ ] **Step 1: Write failing config tests**

`tests/toktrak/ConfigTest.java`:

```java
package toktrak;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class ConfigTest {
  @Test
  void devAuthDefaultsPortAndDataDir() {
    var cfg = Config.from(new String[] {}, Map.of("TOKTRAK_DEV_AUTH", "true"));
    assertEquals(8080, cfg.port());
    assertTrue(cfg.devAuth());
    assertTrue(cfg.dataDir().toString().contains("toktrak-dev"));
  }

  @Test
  void productionRequiresDataDirAndBaseUrl() {
    var ex = assertThrows(IllegalArgumentException.class, () -> Config.from(new String[] {}, Map.of()));
    assertEquals("TOKTRAK_DEV_AUTH=true or TOKTRAK_DATA_DIR is required", ex.getMessage());
  }

  @Test
  void rejectsNonLocalHttpWithoutDevAuth() {
    var env = Map.of("TOKTRAK_DATA_DIR", "data", "TOKTRAK_BASE_URL", "http://toktrak.example");
    var ex = assertThrows(IllegalArgumentException.class, () -> Config.from(new String[] {}, env));
    assertEquals("non-local HTTP requires TOKTRAK_DEV_AUTH=true", ex.getMessage());
  }

  @Test
  void acceptsPinnedDevClockOnlyInDevMode() {
    var cfg = Config.from(new String[] {"--clock", "2026-07-10T00:00:00Z"}, Map.of("TOKTRAK_DEV_AUTH", "true"));
    assertEquals(Instant.parse("2026-07-10T00:00:00Z"), cfg.clock().instant());
  }

  @Test
  void acceptsExplicitDataDirAndPort() {
    var cfg = Config.from(
        new String[] {},
        Map.of(
            "TOKTRAK_DEV_AUTH", "true",
            "TOKTRAK_DATA_DIR", Path.of("output", "dev-data").toString(),
            "TOKTRAK_PORT", "9090"));
    assertEquals(9090, cfg.port());
    assertEquals(Path.of("output", "dev-data"), cfg.dataDir());
  }
}
```

- [ ] **Step 2: Write failing JSON log test**

`tests/toktrak/JsonLogFormatterTest.java`:

```java
package toktrak;

import static org.junit.jupiter.api.Assertions.*;

import java.util.logging.Level;
import java.util.logging.LogRecord;
import org.junit.jupiter.api.Test;
import toktrak.log.JsonLogFormatter;

final class JsonLogFormatterTest {
  @Test
  void formatsOneCompactJsonLine() {
    var record = new LogRecord(Level.INFO, "hello");
    var line = new JsonLogFormatter().format(record);
    assertTrue(line.endsWith("\n"));
    assertTrue(line.contains("\"level\":\"INFO\""));
    assertTrue(line.contains("\"message\":\"hello\""));
    assertFalse(line.contains("email"));
  }
}
```

- [ ] **Step 3: Run tests to verify failure**

Run: `mise run verify`

Expected: FAIL with missing `Config`, `ClockSource`, `JsonLogFormatter`, and `App` classes.

- [ ] **Step 4: Implement config and clock**

`Config.from` must parse:

```text
TOKTRAK_PORT default 8080
TOKTRAK_DATA_DIR required unless dev auth; dev default output/toktrak-dev/data
TOKTRAK_BASE_URL optional in dev, required in production
TOKTRAK_DEV_AUTH=true enables fake viewer and local HTTP relaxation
--corpus <path> accepted only with dev auth
--fail-writes accepted only with dev auth
--clock <instant> accepted only with dev auth
```

Use `ClockSource.system()` and `ClockSource.fixed(Instant)`; expose `instant()`.

- [ ] **Step 5: Implement JSON and logging**

`Json` owns one `ObjectMapper` configured to write compact JSON. Serialize/parse `Instant` explicitly as `Instant.toString()`/`Instant.parse(...)` through small helpers or Jackson serializers in this class; do not add `jackson-datatype-jsr310`. `JsonLogFormatter` writes `timestamp`, `level`, and `message`. Task 3 extends it to add request fields from `RequestContext.currentOrNull()`.

- [ ] **Step 6: Add temporary App stub so build passes**

`App.start` may construct config and return an `App` whose `close()` does nothing. This is replaced by Task 3.

- [ ] **Step 7: Run tests**

Run: `mise run verify`

Expected: PASS for `ConfigTest` and `JsonLogFormatterTest`.

- [ ] **Step 8: Commit**

```sh
git add sources/toktrak/toktrak/App.java sources/toktrak/toktrak/Config.java sources/toktrak/toktrak/ClockSource.java sources/toktrak/toktrak/json/Json.java sources/toktrak/toktrak/log/JsonLogFormatter.java tests/toktrak/ConfigTest.java tests/toktrak/JsonLogFormatterTest.java
git commit -m "feat: add server config and json logging"
```

## Task 3: HTTP routing, request context, errors, and `/health`

**Files:**

- Modify: `sources/toktrak/toktrak/App.java`
- Create: `sources/toktrak/toktrak/http/RequestContext.java`
- Create: `sources/toktrak/toktrak/http/Router.java`
- Create: `sources/toktrak/toktrak/http/ApiError.java`
- Create: `sources/toktrak/toktrak/http/HttpSupport.java`
- Create: `sources/toktrak/toktrak/health/HealthState.java`
- Test: `tests/toktrak/HttpServerTest.java`

- [ ] **Step 1: Write failing HTTP tests**

`tests/toktrak/HttpServerTest.java`:

```java
package toktrak;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class HttpServerTest {
  @Test
  void healthReturnsOkJsonAndSecurityHeaders() throws Exception {
    try (var app = App.start(new String[] {}, Map.of("TOKTRAK_DEV_AUTH", "true", "TOKTRAK_PORT", "0"))) {
      var res = get(app, "/health");
      assertEquals(200, res.statusCode());
      assertEquals("nosniff", res.headers().firstValue("x-content-type-options").orElseThrow());
      assertEquals("DENY", res.headers().firstValue("x-frame-options").orElseThrow());
      assertTrue(res.body().contains("\"status\":\"ok\""));
    }
  }

  @Test
  void unknownApiRouteReturnsEnvelope() throws Exception {
    try (var app = App.start(new String[] {}, Map.of("TOKTRAK_DEV_AUTH", "true", "TOKTRAK_PORT", "0"))) {
      var res = get(app, "/api/nope");
      assertEquals(404, res.statusCode());
      assertTrue(res.body().contains("\"error\""));
      assertTrue(res.body().contains("\"code\":\"not_found\""));
      assertTrue(res.body().contains("\"requestId\""));
    }
  }

  @Test
  void unknownBrowserRouteReturnsBrutalHtml() throws Exception {
    try (var app = App.start(new String[] {}, Map.of("TOKTRAK_DEV_AUTH", "true", "TOKTRAK_PORT", "0"))) {
      var res = get(app, "/nope");
      assertEquals(404, res.statusCode());
      assertTrue(res.body().contains("<h1>404</h1>"));
      assertTrue(res.body().contains("BRUTAL ERROR"));
    }
  }

  private static HttpResponse<String> get(App app, String path) throws Exception {
    var req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + path)).GET().build();
    return HttpClient.newHttpClient().send(req, BodyHandlers.ofString());
  }
}
```

- [ ] **Step 2: Run tests to verify failure**

Run: `mise run verify`

Expected: FAIL because HTTP classes do not exist.

- [ ] **Step 3: Implement request context**

`RequestContext` uses `ScopedValue<RequestContext>` and contains `requestId`, `method`, `path`, `userId`, `tokenId`, and `mode`. `with(ctx, Callable<T>)` binds the scoped value. Generate request ids with `UUID.randomUUID()`.

- [ ] **Step 4: Implement support responses**

`HttpSupport` must add these headers to every response:

```text
X-Content-Type-Options: nosniff
X-Frame-Options: DENY
Referrer-Policy: no-referrer
Content-Security-Policy: default-src 'self'; frame-ancestors 'none'; base-uri 'none'
```

`ApiError` serializes exactly `{"error":{"code":"...","message":"...","requestId":"..."}}`.

- [ ] **Step 5: Implement Router and App server lifecycle**

`App.start` creates `HttpServer` on configured host port, sets executor to `Executors.newVirtualThreadPerTaskExecutor()`, registers one root context with `Router`, starts, and exposes `port()`. `close()` stops the server and closes the executor.

Routes for this task:

```text
GET /health -> 200 {"status":"ok"} when healthy
GET /health -> 503 {"status":"degraded","reason":"..."} when degraded
/api/* unknown -> JSON 404 envelope
other unknown -> brutal HTML 404
```

- [ ] **Step 6: Run tests**

Run: `mise run verify`

Expected: PASS through `HttpServerTest`.

- [ ] **Step 7: Commit**

```sh
git add sources/toktrak/toktrak/App.java sources/toktrak/toktrak/http sources/toktrak/toktrak/health tests/toktrak/HttpServerTest.java
git commit -m "feat: add http core and health route"
```

## Task 4: Data lock, event envelopes, torn-tail recovery, and corruption failure

**Files:**

- Modify: `sources/toktrak/toktrak/App.java`
- Create: `sources/toktrak/toktrak/store/DataLock.java`
- Create: `sources/toktrak/toktrak/store/EventEnvelope.java`
- Create: `sources/toktrak/toktrak/store/EventLog.java`
- Test: `tests/toktrak/EventLogTest.java`
- Test: `tests/toktrak/DataLockTest.java`

- [ ] **Step 1: Write failing event-log tests**

`tests/toktrak/EventLogTest.java`:

```java
package toktrak;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import toktrak.store.EventEnvelope;
import toktrak.store.EventLog;

final class EventLogTest {
  @TempDir Path dir;

  @Test
  void appendsNewlineTerminatedEnvelopeAndReadsIt() throws Exception {
    var log = EventLog.open(dir.resolve("events.ndjson"));
    var event = EventEnvelope.create("projection-snapshot", Instant.parse("2026-07-10T00:00:00Z"), "system", Map.of("eventCount", 0));
    log.appendAndFsync(event);
    assertTrue(Files.readString(dir.resolve("events.ndjson")).endsWith("\n"));
    assertEquals("projection-snapshot", log.readAll().getFirst().type());
  }

  @Test
  void truncatesOnlyFinalTornTail() throws Exception {
    var path = dir.resolve("events.ndjson");
    Files.writeString(path, "{\"id\":\"bad-fragment");
    var recovered = EventLog.recoverTornTail(path);
    assertEquals(1, recovered.truncatedFragments());
    assertEquals("", Files.readString(path));
  }

  @Test
  void malformedCompleteLineFailsLoudly() throws Exception {
    var path = dir.resolve("events.ndjson");
    Files.writeString(path, "{not-json}\n");
    var log = EventLog.open(path);
    var ex = assertThrows(IllegalStateException.class, log::readAll);
    assertTrue(ex.getMessage().contains("malformed event line 1"));
  }

  @Test
  void rejectsLineAboveTenMiB() throws Exception {
    var path = dir.resolve("events.ndjson");
    Files.writeString(path, "{\"x\":\"" + "a".repeat(10 * 1024 * 1024) + "\"}\n");
    var log = EventLog.open(path);
    var ex = assertThrows(IllegalStateException.class, log::readAll);
    assertTrue(ex.getMessage().contains("event line exceeds 10485760 bytes"));
  }
}
```

- [ ] **Step 2: Write failing lock test**

`tests/toktrak/DataLockTest.java`:

```java
package toktrak;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import toktrak.store.DataLock;

final class DataLockTest {
  @TempDir Path dir;

  @Test
  void rejectsSecondLockInSameJvm() throws Exception {
    try (var first = DataLock.acquire(dir)) {
      var ex = assertThrows(IllegalStateException.class, () -> DataLock.acquire(dir));
      assertEquals("TokTrak data directory is already locked", ex.getMessage());
    }
  }
}
```

- [ ] **Step 3: Run tests to verify failure**

Run: `mise run verify`

Expected: FAIL because store classes do not exist.

- [ ] **Step 4: Implement DataLock**

Create `${dataDir}/toktrak.lock`, acquire `FileChannel.tryLock()`, keep channel and lock open until `close()`. Treat overlapping same-JVM lock and `null` lock as `IllegalStateException("TokTrak data directory is already locked")`.

- [ ] **Step 5: Implement EventEnvelope**

Record fields: `UUID id`, `Instant at`, `String type`, `int schemaVersion`, `String actor`, `Map<String,Object> data`. `create` uses UUID v4 and schema version `1`. Validation rejects blank type and non-positive schema version.

- [ ] **Step 6: Implement EventLog**

Rules:

```text
path: ${TOKTRAK_DATA_DIR}/events.ndjson
encoding: UTF-8
max line: 10 MiB
append: compact JSON + \n
fsync: FileChannel.force(true) after token/upload/snapshot command successes in Phase 1 tests
recover: truncate only bytes after last complete newline; malformed newline-terminated line fails
```

Use `RandomAccessFile` or `FileChannel` opened with `CREATE`, `READ`, `WRITE`, `APPEND` for append/fsync. Reading must preserve event-log order.

- [ ] **Step 7: App startup uses lock and recovery before binding**

`App.start` must acquire `DataLock`, call `EventLog.recoverTornTail`, read events, then bind HTTP. If read fails, close the lock and do not bind.

- [ ] **Step 8: Run tests**

Run: `mise run verify`

Expected: PASS through event-log and lock tests.

- [ ] **Step 9: Commit**

```sh
git add sources/toktrak/toktrak/App.java sources/toktrak/toktrak/store tests/toktrak/EventLogTest.java tests/toktrak/DataLockTest.java
git commit -m "feat: add durable event log startup"
```

## Task 5: Projection rebuild, snapshots, writer queue, fsync acknowledgement

**Files:**

- Modify: `sources/toktrak/toktrak/App.java`
- Create: `sources/toktrak/toktrak/store/Writer.java`
- Create: `sources/toktrak/toktrak/store/WriteCommand.java`
- Create: `sources/toktrak/toktrak/projection/Projection.java`
- Test: `tests/toktrak/WriterTest.java`
- Test: `tests/toktrak/ProjectionTest.java`

- [ ] **Step 1: Write failing projection tests**

`tests/toktrak/ProjectionTest.java`:

```java
package toktrak;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import toktrak.projection.Projection;
import toktrak.store.EventEnvelope;

final class ProjectionTest {
  @Test
  void rebuildCountsRawEventsAfterLatestCompatibleSnapshot() {
    var t = Instant.parse("2026-07-10T00:00:00Z");
    var old = EventEnvelope.create("dev-test", t, "system", Map.of());
    var snap = EventEnvelope.create("projection-snapshot", t, "system", Map.of("projectionVersion", Projection.VERSION, "eventCount", 5));
    var newer = EventEnvelope.create("dev-test", t, "system", Map.of());
    var projection = Projection.rebuild(List.of(old, snap, newer));
    assertEquals(6, projection.eventCount());
  }

  @Test
  void ignoresIncompatibleSnapshotAndReplaysAllRawEvents() {
    var t = Instant.parse("2026-07-10T00:00:00Z");
    var raw = EventEnvelope.create("dev-test", t, "system", Map.of());
    var snap = EventEnvelope.create("projection-snapshot", t, "system", Map.of("projectionVersion", -1, "eventCount", 100));
    var projection = Projection.rebuild(List.of(raw, snap));
    assertEquals(1, projection.eventCount());
  }
}
```

- [ ] **Step 2: Write failing writer tests**

`tests/toktrak/WriterTest.java`:

```java
package toktrak;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import toktrak.ClockSource;
import toktrak.health.HealthState;
import toktrak.projection.Projection;
import toktrak.store.EventLog;
import toktrak.store.WriteCommand;
import toktrak.store.Writer;

final class WriterTest {
  @TempDir Path dir;

  @Test
  void successReturnsOnlyAfterAppendAndProjectionApply() throws Exception {
    var log = EventLog.open(dir.resolve("events.ndjson"));
    var projection = Projection.empty();
    var writer = Writer.start(log, projection, new HealthState(), ClockSource.fixed(Instant.parse("2026-07-10T00:00:00Z")), false);
    try (writer) {
      var result = writer.submit(WriteCommand.devTest("system")).get();
      assertTrue(result.eventId().isPresent());
      assertEquals(1, writer.projection().eventCount());
      assertEquals(1, log.readAll().size());
    }
  }

  @Test
  void fullQueueReturnsRejectedFuture() throws Exception {
    var log = EventLog.open(dir.resolve("events.ndjson"));
    var writer = Writer.startForTest(log, Projection.empty(), new HealthState(), ClockSource.system(), false, 1);
    try (writer) {
      writer.pauseForTest();
      assertTrue(writer.trySubmit(WriteCommand.devTest("a")).accepted());
      assertFalse(writer.trySubmit(WriteCommand.devTest("b")).accepted());
    }
  }

  @Test
  void injectedFailureMarksHealthDegraded() throws Exception {
    var health = new HealthState();
    var log = EventLog.open(dir.resolve("events.ndjson"));
    var writer = Writer.start(log, Projection.empty(), health, ClockSource.system(), true);
    try (writer) {
      var ex = assertThrows(Exception.class, () -> writer.submit(WriteCommand.devTest("system")).get());
      assertTrue(ex.getMessage().contains("writes disabled by --fail-writes"));
      assertFalse(health.healthy());
      assertEquals("writes_failed", health.reason());
    }
  }
}
```

- [ ] **Step 3: Run tests to verify failure**

Run: `mise run verify`

Expected: FAIL because writer/projection classes do not exist.

- [ ] **Step 4: Implement Projection**

Phase 1 projection state:

```text
VERSION = 1
eventCount: count of non-snapshot events after newest compatible snapshot plus snapshot eventCount
apply(event): projection-snapshot restores state only during rebuild; normal writer applies non-snapshot event count increments
snapshotData(): {"projectionVersion":1,"eventCount":<count>}
```

Projection corruption means invalid snapshot payload types; throw `IllegalStateException("projection snapshot is corrupt")` during rebuild.

- [ ] **Step 5: Implement WriteCommand**

`WriteCommand.devTest(actor)` creates one event with type `dev-test`. `WriteCommand.snapshot(actor)` creates `projection-snapshot` using `Projection.snapshotData()`. Keep command surface tiny; Phase 1 needs only these two write commands.

- [ ] **Step 6: Implement Writer**

Use `ArrayBlockingQueue` capacity `1024` by default and one platform thread named `toktrak-writer`. Command success path:

```text
build event using current projection and clock
append compact NDJSON
force fsync
apply event to projection
complete future with event id
```

Queue full returns a rejected result without blocking. Any append exception marks health degraded with reason `writes_failed`; startup corruption still fails hard before writer starts.

- [ ] **Step 7: App starts writer after projection rebuild**

Startup order:

```text
Config -> DataLock -> EventLog recover -> readAll -> Projection.rebuild -> Writer.start -> HttpServer.start
```

`App.close()` stops HTTP first, writer second, lock last.

- [ ] **Step 8: Run tests**

Run: `mise run verify`

Expected: PASS through writer and projection tests.

- [ ] **Step 9: Commit**

```sh
git add sources/toktrak/toktrak/App.java sources/toktrak/toktrak/store/Writer.java sources/toktrak/toktrak/store/WriteCommand.java sources/toktrak/toktrak/projection/Projection.java tests/toktrak/WriterTest.java tests/toktrak/ProjectionTest.java
git commit -m "feat: add writer queue and projection rebuild"
```

## Task 6: Runtime degraded health, body limits, dev auth strip, disposable corpus, and `--fail-writes`

**Files:**

- Modify: `sources/toktrak/toktrak/App.java`
- Modify: `sources/toktrak/toktrak/http/Router.java`
- Modify: `sources/toktrak/toktrak/http/HttpSupport.java`
- Create: `sources/toktrak/toktrak/dev/DevData.java`
- Test: `tests/toktrak/HealthModeTest.java`
- Test: `tests/toktrak/BodyLimitTest.java`
- Test: `tests/toktrak/DevDataTest.java`

- [ ] **Step 1: Write failing health/dev tests**

`tests/toktrak/HealthModeTest.java`:

```java
package toktrak;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class HealthModeTest {
  @Test
  void failWritesStartsAfterBindingAndReportsDegraded() throws Exception {
    try (var app = App.start(new String[] {"--fail-writes"}, Map.of("TOKTRAK_DEV_AUTH", "true", "TOKTRAK_PORT", "0"))) {
      var res = HttpClient.newHttpClient().send(
          HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/health")).GET().build(),
          BodyHandlers.ofString());
      assertEquals(503, res.statusCode());
      assertTrue(res.body().contains("\"status\":\"degraded\""));
      assertTrue(res.body().contains("\"reason\":\"writes_failed\""));
    }
  }

  @Test
  void rootShowsDevAuthStrip() throws Exception {
    try (var app = App.start(new String[] {}, Map.of("TOKTRAK_DEV_AUTH", "true", "TOKTRAK_PORT", "0"))) {
      var res = HttpClient.newHttpClient().send(
          HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/")).GET().build(),
          BodyHandlers.ofString());
      assertEquals(200, res.statusCode());
      assertTrue(res.body().contains("DEV AUTH"));
    }
  }
}
```

- [ ] **Step 2: Write failing body limit test**

`tests/toktrak/BodyLimitTest.java`:

```java
package toktrak;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import org.junit.jupiter.api.Test;
import toktrak.http.HttpSupport;

final class BodyLimitTest {
  @Test
  void rejectsBodyAboveLimit() throws Exception {
    var body = new ByteArrayInputStream(new byte[1025]);
    var ex = assertThrows(IllegalArgumentException.class, () -> HttpSupport.readLimited(body, 1024));
    assertEquals("request body exceeds 1024 bytes", ex.getMessage());
  }
}
```

- [ ] **Step 3: Write failing dev data test**

`tests/toktrak/DevDataTest.java`:

```java
package toktrak;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import toktrak.dev.DevData;

final class DevDataTest {
  @TempDir Path dir;

  @Test
  void copiesCorpusToDisposableDataDir() throws Exception {
    var corpus = dir.resolve("corpus.ndjson");
    Files.writeString(corpus, "{\"id\":\"00000000-0000-4000-8000-000000000001\",\"at\":\"2026-07-10T00:00:00Z\",\"type\":\"dev-test\",\"schemaVersion\":1,\"actor\":\"system\",\"data\":{}}\n");
    var dataDir = dir.resolve("data");
    DevData.prepareDisposableCorpus(corpus, dataDir);
    assertEquals(Files.readString(corpus), Files.readString(dataDir.resolve("events.ndjson")));
  }

  @Test
  void appStartupCopiesCorpusBeforeOpeningEventLog() throws Exception {
    var corpus = dir.resolve("corpus.ndjson");
    Files.writeString(corpus, "{\"id\":\"00000000-0000-4000-8000-000000000001\",\"at\":\"2026-07-10T00:00:00Z\",\"type\":\"dev-test\",\"schemaVersion\":1,\"actor\":\"system\",\"data\":{}}\n");
    var dataDir = dir.resolve("app-data");
    try (var app = App.start(
        new String[] {"--corpus", corpus.toString()},
        Map.of("TOKTRAK_DEV_AUTH", "true", "TOKTRAK_PORT", "0", "TOKTRAK_DATA_DIR", dataDir.toString()))) {
      assertEquals(Files.readString(corpus), Files.readString(dataDir.resolve("events.ndjson")));
    }
  }
}
```

- [ ] **Step 4: Run tests to verify failure**

Run: `mise run verify`

Expected: FAIL because dev handling/body helpers are incomplete.

- [ ] **Step 5: Implement `HealthState` degraded transitions**

`HealthState` exposes `healthy()`, `reason()`, `degrade(reason)`, and `requireWritable()`; mutations call `requireWritable()` and return HTTP 503 code `degraded` when false. `--fail-writes` marks degraded immediately after server binding.

- [ ] **Step 6: Implement root dev page**

`GET /` returns minimal HTML:

```html
<!doctype html><meta charset="utf-8"><title>TokTrak</title><div style="background:#b00020;color:white;padding:.5rem">DEV AUTH</div><h1>TokTrak</h1>
```

Show the strip only when `TOKTRAK_DEV_AUTH=true`.

- [ ] **Step 7: Implement body limit helper**

`HttpSupport.readLimited(InputStream, int)` reads at most `limit + 1` bytes and throws `IllegalArgumentException("request body exceeds <limit> bytes")` before allocating unbounded memory. Phase 1 uses it for shared route plumbing; Phase 3 applies the 5 MiB upload limit.

- [ ] **Step 8: Implement disposable corpus copy**

`DevData.prepareDisposableCorpus(corpus, dataDir)` deletes only `dataDir/events.ndjson` if it exists, creates directories, copies corpus to `events.ndjson`, and never appends to the committed corpus path. `App.start` must call it after `Config.from(...)` and before `DataLock.acquire(...)`/`EventLog.recoverTornTail(...)` when `--corpus` is present. Reject corpus usage when dev auth is false in `Config`.

- [ ] **Step 9: Run tests**

Run: `mise run verify`

Expected: PASS through health/dev tests.

- [ ] **Step 10: Commit**

```sh
git add sources/toktrak/toktrak/App.java sources/toktrak/toktrak/http sources/toktrak/toktrak/dev tests/toktrak/HealthModeTest.java tests/toktrak/BodyLimitTest.java tests/toktrak/DevDataTest.java
git commit -m "feat: add degraded mode and dev data handling"
```

## Task 7: Restart, second-process lock, jlink, and manual smoke verification

**Files:**

- Modify: `tools/Build.java`
- Test: `tests/toktrak/RestartAndLockTest.java`
- Test: `tests/toktrak/JlinkSmokeTest.java`
- Modify: `README.md` only if commands differ from current text.

- [ ] **Step 1: Write restart and process-lock tests**

`tests/toktrak/RestartAndLockTest.java`:

```java
package toktrak;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import toktrak.store.WriteCommand;

final class RestartAndLockTest {
  @TempDir Path dir;

  @Test
  void restartRebuildsProjectionFromEventLog() throws Exception {
    var env = Map.of("TOKTRAK_DEV_AUTH", "true", "TOKTRAK_PORT", "0", "TOKTRAK_DATA_DIR", dir.toString());
    try (var app = App.start(new String[] {}, env)) {
      app.writer().submit(WriteCommand.devTest("system")).get();
      assertEquals(1, app.projection().eventCount());
    }
    try (var app = App.start(new String[] {}, env)) {
      assertEquals(1, app.projection().eventCount());
    }
  }

  @Test
  void secondAppCannotOpenSameDataDir() throws Exception {
    var env = Map.of("TOKTRAK_DEV_AUTH", "true", "TOKTRAK_PORT", "0", "TOKTRAK_DATA_DIR", dir.toString());
    try (var first = App.start(new String[] {}, env)) {
      var ex = assertThrows(IllegalStateException.class, () -> App.start(new String[] {}, env));
      assertEquals("TokTrak data directory is already locked", ex.getMessage());
    }
  }
}
```

- [ ] **Step 2: Write jlink smoke test**

`tests/toktrak/JlinkSmokeTest.java`:

```java
package toktrak;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class JlinkSmokeTest {
  @Test
  void prodRuntimeImageExistsAfterJlinkTaskWhenRequested() {
    var image = Path.of("output", "runtimes", "prod");
    if (Files.exists(image)) {
      assertTrue(Files.exists(image.resolve("bin").resolve(System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java")));
    }
  }
}
```

- [ ] **Step 3: Run tests to verify restart failure**

Run: `mise run verify`

Expected: FAIL until `App` exposes `writer()` and `projection()` for tests and restart rebuild is wired.

- [ ] **Step 4: Finish lifecycle test hooks**

Expose package/test-safe accessors on `App`:

```java
public int port();
public Writer writer();
public Projection projection();
```

Keep them boring; no interface or service locator.

- [ ] **Step 5: Implement jlink command**

`Build.java jlink-prod` must:

```text
compile app
verify no automatic production modules
run jlink with module path entries `output/classes`, `output/deps/main`, and `<java jmods>` joined by `File.pathSeparator` --add-modules toktrak --output output/runtimes/prod --strip-debug --no-header-files --no-man-pages
run output/runtimes/prod/bin/java -m toktrak/toktrak.Main --help
```

`Main --help` prints `TokTrak dev server` and exits `0` without starting HTTP.

- [ ] **Step 6: Run full verification**

Run: `mise run verify`

Expected: PASS all tests.

- [ ] **Step 7: Run production jlink smoke**

Run: `mise run link-prod`

Expected: PASS, no automatic modules, runtime created at `output/runtimes/prod`.

- [ ] **Step 8: Manual healthy dev smoke**

Run in one terminal:

```sh
TOKTRAK_DEV_AUTH=true mise run dev -- --clock 2026-07-10T00:00:00Z
```

Expected stdout: JSON log line showing bind on port 8080.

Run in another terminal:

```sh
curl -i http://127.0.0.1:8080/health
```

Expected: HTTP 200 and body contains `{"status":"ok"}`.

- [ ] **Step 9: Manual degraded dev smoke**

Run:

```sh
TOKTRAK_DEV_AUTH=true mise run dev -- --fail-writes
curl -i http://127.0.0.1:8080/health
```

Expected: HTTP 503 and body contains `{"status":"degraded","reason":"writes_failed"}`.

- [ ] **Step 10: Commit**

```sh
git add tools/Build.java sources/toktrak tests/toktrak README.md
git commit -m "test: verify durable server phase boundary"
```

## Final verification checklist

- [ ] `mise run install` passes.
- [ ] `mise run build` passes.
- [ ] `mise run verify` passes.
- [ ] `mise run link-prod` passes and rejects automatic modules before linking.
- [ ] Healthy dev `/health` returns 200 `ok`.
- [ ] `--fail-writes` dev `/health` returns 503 `degraded` after binding.
- [ ] Starting a second app on the same data dir fails before binding.
- [ ] Torn tail truncates; malformed complete line hard-fails.
- [ ] Restart rebuilds projection from `events.ndjson`.

## Spec coverage

Covered in this phase plan:

- Java 26 JPMS build and jlink compatibility.
- Pinned Jackson/Nimbus deps with explicit modules.
- JDK `HttpServer`, virtual-thread request executor, scoped request context.
- JSON logging without emails/token material.
- Config validation, dev auth relaxation, non-local HTTP rejection outside dev.
- API error envelope and brutal HTML fallback.
- Baseline browser security headers.
- Exclusive data lock and append-only event log.
- Event envelopes, line limits, torn-tail recovery, malformed-line failure.
- Writer queue, fsync acknowledgement, projection rebuild, compatible snapshots.
- Healthy/degraded state, `/health`, disposable dev corpus, pinned dev clock, `--fail-writes`.
- Tests for persistence, concurrency/queue saturation, restart, lock, corruption, body limits, and jlink.

Intentionally excluded:

- OIDC, users, tracker tokens, usage ingestion, FX, SSE, dashboard UI, workstation tracker, release container.
