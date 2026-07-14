package toktrak;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
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
