package toktrak.tests;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import toktrak.*;
import toktrak.health.HealthState;
import toktrak.projection.Projection;
import toktrak.store.EventEnvelope;
import toktrak.store.EventLog;
import toktrak.store.WriteCommand;
import toktrak.store.Writer;

final class WriterTest {
  @TempDir Path dir;

  @Test
  void given_acceptedCommand_when_awaitingSuccess_then_appendsAndAppliesProjection()
      throws Exception {
    var log = EventLog.open(dir.resolve("events.ndjson"));
    var projection = Projection.empty();
    var writer =
        Writer.start(
            log,
            projection,
            new HealthState(),
            ClockSource.fixed(Instant.parse("2026-07-10T00:00:00Z")),
            false);
    try (writer) {
      var result = writer.submit(WriteCommand.devTest("system")).get(2, TimeUnit.SECONDS);
      assertTrue(result.eventId().isPresent());
      assertEquals(1, writer.projection().eventCount());
      assertEquals(1, log.replay(_ -> {}));
    }
  }

  @Test
  void given_maximumProjection_when_submittingCommand_then_doesNotAppendEvent() throws Exception {
    var log = EventLog.open(dir.resolve("events.ndjson"));
    var projection = Projection.empty();
    projection.apply(
        EventEnvelope.create(
            "projection-snapshot",
            Instant.parse("2026-07-10T00:00:00Z"),
            "system",
            java.util.Map.of(
                "projectionVersion", Projection.VERSION,
                "eventCount", Integer.MAX_VALUE)));
    var writer = Writer.start(log, projection, new HealthState(), ClockSource.system(), false);
    try (writer) {
      assertThrows(
          Exception.class,
          () -> writer.submit(WriteCommand.devTest("system")).get(2, TimeUnit.SECONDS));
      assertEquals(0, log.replay(_ -> {}));
      assertEquals(Integer.MAX_VALUE, projection.eventCount());
    }
  }

  @Test
  void given_fullWriterQueue_when_tryingCommand_then_reportsNotAccepted() throws Exception {
    var log = EventLog.open(dir.resolve("events.ndjson"));
    var writer =
        Writer.startForTest(
            log, Projection.empty(), new HealthState(), ClockSource.system(), false, 1);
    try (writer) {
      writer.pauseForTest();
      assertTrue(writer.trySubmit(WriteCommand.devTest("a")).accepted());
      assertFalse(writer.trySubmit(WriteCommand.devTest("b")).accepted());
    }
  }

  @Test
  void given_snapshotCommand_when_submittingCommand_then_appendsProjectionSnapshot()
      throws Exception {
    var log = EventLog.open(dir.resolve("events.ndjson"));
    var projection = Projection.empty();
    projection.apply(
        EventEnvelope.create(
            "dev-test", Instant.parse("2026-07-10T00:00:00Z"), "system", java.util.Map.of()));
    var writer =
        Writer.start(
            log,
            projection,
            new HealthState(),
            ClockSource.fixed(Instant.parse("2026-07-10T01:00:00Z")),
            false);
    try (writer) {
      var result = writer.submit(WriteCommand.snapshot("system")).get(2, TimeUnit.SECONDS);
      assertTrue(result.eventId().isPresent());
      var replayed = new java.util.ArrayList<EventEnvelope>();
      assertEquals(1, log.replay(replayed::add));
      assertEquals("projection-snapshot", replayed.getFirst().type());
      assertEquals(1, replayed.getFirst().data().get("eventCount"));
    }
  }

  @Test
  void given_invalidWriterSettings_when_startingWriter_then_rejectsSettings() {
    var log = EventLog.open(dir.resolve("events.ndjson"));
    for (int capacity : new int[] {0, 1_025}) {
      assertThrows(
          IllegalArgumentException.class,
          () ->
              Writer.startForTest(
                  log,
                  Projection.empty(),
                  new HealthState(),
                  ClockSource.system(),
                  false,
                  capacity));
    }
    for (Duration timeout :
        new Duration[] {Duration.ZERO, Duration.ofSeconds(-1), Duration.ofSeconds(61)}) {
      assertThrows(
          IllegalArgumentException.class,
          () ->
              Writer.startForTest(
                  log,
                  Projection.empty(),
                  new HealthState(),
                  ClockSource.system(),
                  false,
                  1,
                  timeout,
                  Duration.ofSeconds(1)));
    }
  }

  @Test
  void given_closedWriter_when_submittingCommand_then_reportsClosed() throws Exception {
    var log = EventLog.open(dir.resolve("events.ndjson"));
    var writer =
        Writer.start(log, Projection.empty(), new HealthState(), ClockSource.system(), false);
    writer.close();

    var future = writer.submit(WriteCommand.devTest("system"));
    var ex = assertThrows(Exception.class, () -> future.get(2, TimeUnit.SECONDS));
    assertTrue(ex.getMessage().contains("writer is closed"));
  }

  @Test
  void given_inFlightCommandBlockedOnClock_when_forcingClose_then_failsWithoutProjection()
      throws Exception {
    var enteredClock = new CountDownLatch(1);
    var releaseClock = new CountDownLatch(1);
    var at = Instant.parse("2026-07-10T00:00:00Z");
    ClockSource blockedClock =
        () -> {
          enteredClock.countDown();
          boolean interrupted = false;
          for (int waitCount = 0; waitCount < 1_000 && releaseClock.getCount() != 0; waitCount++) {
            try {
              releaseClock.await(10, TimeUnit.MILLISECONDS);
            } catch (InterruptedException ex) {
              interrupted = true;
            }
          }
          if (interrupted) Thread.currentThread().interrupt();
          return at;
        };
    var log = EventLog.open(dir.resolve("events.ndjson"));
    var projection = Projection.empty();
    var health = new HealthState();
    var writer =
        Writer.startForTest(
            log,
            projection,
            health,
            blockedClock,
            false,
            1,
            Duration.ofMillis(20),
            Duration.ofMillis(20));
    try {
      var future = writer.submit(WriteCommand.devTest("system"));
      assertTrue(enteredClock.await(2, TimeUnit.SECONDS));
      assertTimeoutPreemptively(Duration.ofSeconds(1), writer::close);
      assertThrows(Exception.class, () -> future.get(2, TimeUnit.SECONDS));
      assertEquals(0, projection.eventCount());
      assertEquals(0, log.replay(_ -> {}));
      assertFalse(health.healthy());
    } finally {
      releaseClock.countDown();
      writer.close();
    }
  }

  @Test
  void given_claimedRequest_when_forcingClose_then_failsWithoutProjection() throws Exception {
    var claimEntered = new CountDownLatch(1);
    var releaseClaim = new CountDownLatch(1);
    var log = EventLog.open(dir.resolve("events.ndjson"));
    var projection = Projection.empty();
    var writer =
        Writer.startForTest(
            log,
            projection,
            new HealthState(),
            ClockSource.system(),
            false,
            1,
            Duration.ofMillis(20),
            Duration.ofMillis(20),
            blockingHook(claimEntered, releaseClaim),
            () -> {});
    try {
      var future = writer.submit(WriteCommand.devTest("system"));
      assertTrue(claimEntered.await(2, TimeUnit.SECONDS));
      assertTimeoutPreemptively(Duration.ofSeconds(1), writer::close);
      assertThrows(Exception.class, () -> future.get(2, TimeUnit.SECONDS));
      assertEquals(0, projection.eventCount());
      assertEquals(0, log.replay(_ -> {}));
    } finally {
      releaseClaim.countDown();
      writer.close();
    }
  }

  @Test
  void given_fsyncedCommandNotApplied_when_forcingClose_then_commitsNeitherProjectionNorSuccess()
      throws Exception {
    var fsyncCompleted = new CountDownLatch(1);
    var releaseFsync = new CountDownLatch(1);
    var log = EventLog.open(dir.resolve("events.ndjson"));
    var projection = Projection.empty();
    var writer =
        Writer.startForTest(
            log,
            projection,
            new HealthState(),
            ClockSource.system(),
            false,
            1,
            Duration.ofMillis(20),
            Duration.ofMillis(20),
            () -> {},
            blockingHook(fsyncCompleted, releaseFsync));
    try {
      var future = writer.submit(WriteCommand.devTest("system"));
      assertTrue(fsyncCompleted.await(2, TimeUnit.SECONDS));
      assertTimeoutPreemptively(Duration.ofSeconds(1), writer::close);
      assertThrows(Exception.class, () -> future.get(2, TimeUnit.SECONDS));
      assertEquals(0, projection.eventCount());
      assertEquals(1, log.replay(_ -> {}));
    } finally {
      releaseFsync.countDown();
      writer.close();
    }
  }

  @Test
  void given_injectedWriteFailure_when_submittingCommand_then_marksHealthDegraded()
      throws Exception {
    var health = new HealthState();
    var log = EventLog.open(dir.resolve("events.ndjson"));
    var writer = Writer.start(log, Projection.empty(), health, ClockSource.system(), true);
    try (writer) {
      var exception =
          assertThrows(
              Exception.class,
              () -> writer.submit(WriteCommand.devTest("system")).get(2, TimeUnit.SECONDS));
      assertTrue(exception.getMessage().contains("writes disabled by --fail-writes"));
      assertFalse(health.healthy());
      assertEquals("writes_failed", health.reason());
    }
  }

  private static Runnable blockingHook(CountDownLatch entered, CountDownLatch release) {
    return () -> {
      entered.countDown();
      boolean interrupted = false;
      for (int waitCount = 0; waitCount < 1_000 && release.getCount() != 0; waitCount++) {
        try {
          release.await(10, TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
          interrupted = true;
        }
      }
      if (interrupted) Thread.currentThread().interrupt();
    };
  }
}
