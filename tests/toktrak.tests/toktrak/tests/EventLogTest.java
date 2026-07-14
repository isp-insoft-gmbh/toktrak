package toktrak.tests;

import toktrak.*;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
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
