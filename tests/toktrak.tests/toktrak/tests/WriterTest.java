package toktrak.tests;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
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
            ClockSource.fixed(Instant.parse("2026-07-10T00:00:00Z")));
    try (writer) {
      var result = writer.submit(WriteCommand.devTest("system")).get(2, TimeUnit.SECONDS);
      assertTrue(result.eventId().isPresent());
      assertEquals(1, writer.projection().eventCount());
      assertEquals(1, log.replay(_ -> {}));
    }
  }

  @Test
  void given_alreadyRevokedToken_when_rejectingSecondRevocation_then_keepsWriterHealthy()
      throws Exception {
    var log = EventLog.open(dir.resolve("events.ndjson"));
    var projection = Projection.empty();
    var health = new HealthState();
    var writer =
        Writer.start(
            log, projection, health, ClockSource.fixed(Instant.parse("2026-07-10T00:00:00Z")));
    try (writer) {
      var owner = new Projection.UserKey("issuer", "subject");
      var tokenId = java.util.UUID.randomUUID();
      writer.write(WriteCommand.userAuthenticated(owner, "user@example.com", "User", "#a8dadc"));
      writer.write(WriteCommand.trackerTokenCreated(owner, tokenId, "Laptop", new byte[32]));
      writer.write(WriteCommand.trackerTokenRevoked(owner, tokenId));

      var exception =
          assertThrows(
              IllegalStateException.class,
              () -> writer.write(WriteCommand.trackerTokenRevoked(owner, tokenId)));

      assertEquals("tracker token cannot be revoked", exception.getMessage());
      assertTrue(health.healthy());
      assertDoesNotThrow(() -> writer.write(WriteCommand.devTest("system")));
      assertEquals(4, projection.eventCount());
      assertEquals(4, log.replay(_ -> {}));
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
    var writer = Writer.start(log, projection, new HealthState(), ClockSource.system());
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
        Writer.startForTest(log, Projection.empty(), new HealthState(), ClockSource.system(), 1);
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
            ClockSource.fixed(Instant.parse("2026-07-10T01:00:00Z")));
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
      var exception =
          assertThrows(
              IllegalArgumentException.class,
              () ->
                  Writer.startForTest(
                      log, Projection.empty(), new HealthState(), ClockSource.system(), capacity));
      assertEquals("capacity must be 1..1024", exception.getMessage());
    }
    for (int capacity : new int[] {1, 1_024}) {
      Writer.startForTest(
              log, Projection.empty(), new HealthState(), ClockSource.system(), capacity)
          .close();
    }
    Writer.startForTest(
            log,
            Projection.empty(),
            new HealthState(),
            ClockSource.system(),
            1,
            Duration.ofMinutes(1),
            Duration.ofNanos(1))
        .close();
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
                  1,
                  timeout,
                  Duration.ofSeconds(1)));
    }
  }

  @Test
  void given_closedWriter_when_submittingCommand_then_reportsClosed() throws Exception {
    var log = EventLog.open(dir.resolve("events.ndjson"));
    var writer = Writer.start(log, Projection.empty(), new HealthState(), ClockSource.system());
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
          for (int waitCount = 0; waitCount < 1_000; waitCount++) {
            try {
              if (releaseClock.await(10, TimeUnit.MILLISECONDS)) break;
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
            log, projection, health, blockedClock, 1, Duration.ofMillis(20), Duration.ofMillis(20));
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
  void given_storageFailure_when_submittingAfterStorageRecovers_then_rejectsWithoutMutation()
      throws Exception {
    Path eventLogPath = dir.resolve("events.ndjson");
    var log = EventLog.open(eventLogPath);
    var projection = Projection.empty();
    var health = new HealthState();
    var writer = Writer.start(log, projection, health, ClockSource.system());
    try (writer) {
      Files.delete(eventLogPath);
      Files.createDirectory(eventLogPath);
      assertThrows(IllegalStateException.class, () -> writer.write(WriteCommand.devTest("first")));
      assertFalse(health.healthy());

      Files.delete(eventLogPath);
      Files.createFile(eventLogPath);
      var exception =
          assertThrows(
              IllegalStateException.class, () -> writer.write(WriteCommand.devTest("second")));

      assertEquals("writes_failed", exception.getMessage());
      assertEquals(0, projection.eventCount());
      assertEquals(0, log.replay(_ -> {}));
    }
  }

  @Test
  void given_commandsQueuedBehindStuckClaim_when_forcingClose_then_failsEveryCommandAtOnce()
      throws Exception {
    var claimEntered = new CountDownLatch(1);
    var releaseClaim = new CountDownLatch(1);
    var writer =
        Writer.startForTest(
            EventLog.open(dir.resolve("events.ndjson")),
            Projection.empty(),
            new HealthState(),
            ClockSource.system(),
            4,
            Duration.ofMillis(20),
            Duration.ofMillis(20),
            blockingHook(claimEntered, releaseClaim),
            () -> {});
    try {
      var claimed = writer.submit(WriteCommand.devTest("first"));
      assertTrue(claimEntered.await(2, TimeUnit.SECONDS));
      var queued =
          List.of(
              writer.submit(WriteCommand.devTest("second")),
              writer.submit(WriteCommand.devTest("third")));
      // The claim ignores interrupts, so close itself must fail what is still waiting.
      writer.close();
      for (var future : queued) assertFailed("writer is closed", future);
      assertFailed("writer is closed", claimed);
    } finally {
      releaseClaim.countDown();
      writer.close();
    }
  }

  @Test
  void given_writerThreadCrashes_when_commandsAreWaiting_then_failsThemWithoutClose()
      throws Exception {
    var claimEntered = new CountDownLatch(1);
    var releaseClaim = new CountDownLatch(1);
    Runnable blocking = blockingHook(claimEntered, releaseClaim);
    var writer =
        Writer.startForTest(
            EventLog.open(dir.resolve("events.ndjson")),
            Projection.empty(),
            new HealthState(),
            ClockSource.system(),
            4,
            Duration.ofSeconds(1),
            Duration.ofSeconds(1),
            () -> {
              blocking.run();
              throw new IllegalStateException("simulated writer crash");
            },
            () -> {});
    try {
      var claimed = writer.submit(WriteCommand.devTest("first"));
      assertTrue(claimEntered.await(2, TimeUnit.SECONDS));
      var queued = writer.submit(WriteCommand.devTest("second"));
      releaseClaim.countDown();
      assertFailed("writer stopped", claimed);
      assertFailed("writer stopped", queued);
    } finally {
      writer.close();
    }
  }

  @Test
  void given_idleOrPausedWriter_when_submittingOrClosing_then_wakesWithoutWaitingForThePoll()
      throws Exception {
    // The writer re-checks its state every 100 ms; notifications must wake it well before that.
    var log = EventLog.open(dir.resolve("events.ndjson"));
    var writer = Writer.start(log, Projection.empty(), new HealthState(), ClockSource.system());
    Thread.sleep(20);
    long writeMillis = millis(() -> writer.write(WriteCommand.devTest("system")));
    Thread.sleep(20);
    long closeMillis = millis(writer::close);
    assertTrue(writeMillis < 70, "write took " + writeMillis + " ms");
    assertTrue(closeMillis < 70, "close took " + closeMillis + " ms");

    var paused =
        Writer.startForTest(log, Projection.empty(), new HealthState(), ClockSource.system(), 4);
    paused.pauseForTest();
    Thread.sleep(20);
    long pausedCloseMillis = millis(paused::close);
    assertTrue(pausedCloseMillis < 70, "paused close took " + pausedCloseMillis + " ms");
  }

  private static long millis(Runnable action) {
    long start = System.nanoTime();
    action.run();
    return (System.nanoTime() - start) / 1_000_000;
  }

  private static void assertFailed(
      String message, java.util.concurrent.CompletableFuture<Writer.WriteResult> future) {
    var failure =
        assertThrows(
            java.util.concurrent.ExecutionException.class,
            () -> future.get(500, TimeUnit.MILLISECONDS));
    assertEquals(message, failure.getCause().getMessage());
  }

  private static Runnable blockingHook(CountDownLatch entered, CountDownLatch release) {
    return () -> {
      entered.countDown();
      boolean interrupted = false;
      for (int waitCount = 0; waitCount < 1_000; waitCount++) {
        try {
          if (release.await(10, TimeUnit.MILLISECONDS)) break;
        } catch (InterruptedException exception) {
          interrupted = true;
        }
      }
      if (interrupted) Thread.currentThread().interrupt();
    };
  }
}
