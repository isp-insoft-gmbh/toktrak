package toktrak.tests;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
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
      var result = writer.submit(WriteCommand.devTest("system")).get();
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
    assertTrue(health.healthy(), "a clean close keeps the writer healthy");
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
      assertThrows(Exception.class, () -> writer.submit(WriteCommand.devTest("system")).get());
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
      assertFalse(writer.pausedForTest());
      writer.requestPauseForTest();
      Polling.until(writer::pausedForTest);
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
      var result = writer.submit(WriteCommand.snapshot("system")).get();
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
    Writer.startForTest(
            log,
            Projection.empty(),
            new HealthState(),
            ClockSource.system(),
            1,
            Duration.ofMillis(1))
        .close();
    var subMillisecond =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                Writer.startForTest(
                    log,
                    Projection.empty(),
                    new HealthState(),
                    ClockSource.system(),
                    1,
                    Duration.ofNanos(999_999)));
    assertEquals("pollInterval must be at least 1 ms", subMillisecond.getMessage());
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
    var ex = assertThrows(Exception.class, () -> future.get());
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
          awaitIgnoringInterrupts(releaseClock);
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
      enteredClock.await();
      writer.close();
      assertThrows(Exception.class, () -> future.get());
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
      // Waiting until idle first makes the busy check below observe the flag being reset.
      Polling.until(writer::idleForTest);
      var future = writer.submit(WriteCommand.devTest("system"));
      claimEntered.await();
      assertFalse(writer.idleForTest(), "a writer inside a claim is not idle");
      writer.close();
      assertThrows(Exception.class, () -> future.get());
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
      fsyncCompleted.await();
      writer.close();
      assertThrows(Exception.class, () -> future.get());
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
      claimEntered.await();
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
      claimEntered.await();
      var queued = writer.submit(WriteCommand.devTest("second"));
      releaseClaim.countDown();
      assertFailed("writer stopped; outcome is unknown", claimed);
      assertFailed("writer stopped", queued);
    } finally {
      writer.close();
    }
  }

  @Test
  void given_unexpectedErrorWhileWriting_when_writing_then_failsAtOnceAndRefusesLaterCommands()
      throws Exception {
    var health = new HealthState();
    var writer =
        Writer.startForTest(
            EventLog.open(dir.resolve("events.ndjson")),
            Projection.empty(),
            health,
            ClockSource.system(),
            4,
            Duration.ofSeconds(1),
            Duration.ofSeconds(1),
            () -> {},
            () -> {
              throw new AssertionError("simulated invariant breach");
            });
    try {
      // A request left waiting would block for the 10-second write timeout, past the test limit.
      var failure =
          assertThrows(IllegalStateException.class, () -> writer.write(WriteCommand.devTest("a")));
      assertEquals("writer stopped; outcome is unknown", failure.getMessage());
      assertFalse(health.healthy());
      var later = writer.trySubmit(WriteCommand.devTest("b"));
      assertFalse(later.accepted());
      assertTrue(later.future().isCompletedExceptionally());
    } finally {
      writer.close();
    }
  }

  @Test
  void given_idleOrPausedWriter_when_submittingOrClosing_then_wakesWithoutWaitingForThePoll()
      throws Exception {
    // With a one-minute poll, a missed wake-up blocks write or close past the per-test limit.
    // Each action starts only once the writer waits, so it must be woken.
    var log = EventLog.open(dir.resolve("events.ndjson"));
    var projection = Projection.empty();
    var writer =
        Writer.startForTest(
            log, projection, new HealthState(), ClockSource.system(), 4, Duration.ofMinutes(1));
    Polling.until(writer::idleForTest);
    writer.write(WriteCommand.devTest("system"));
    assertEquals(1, projection.eventCount());
    Polling.until(writer::idleForTest);
    writer.close();

    var paused =
        Writer.startForTest(
            log,
            Projection.empty(),
            new HealthState(),
            ClockSource.system(),
            4,
            Duration.ofMinutes(1));
    Polling.until(paused::idleForTest);
    paused.requestPauseForTest();
    Polling.until(paused::pausedForTest);
    paused.close();
  }

  private static void assertFailed(
      String message, java.util.concurrent.CompletableFuture<Writer.WriteResult> future) {
    var failure = assertThrows(java.util.concurrent.ExecutionException.class, () -> future.get());
    assertEquals(message, failure.getCause().getMessage());
  }

  private static Runnable blockingHook(CountDownLatch entered, CountDownLatch release) {
    return () -> {
      entered.countDown();
      awaitIgnoringInterrupts(release);
    };
  }

  /// Blocks like a stuck I/O call until `release` opens: interrupts are remembered, not obeyed.
  /// Each test opens `release` in `finally`, and the per-test limit bounds the wait.
  private static void awaitIgnoringInterrupts(CountDownLatch release) {
    boolean interrupted = false;
    while (release.getCount() > 0) {
      try {
        release.await();
      } catch (InterruptedException exception) {
        interrupted = true;
      }
    }
    if (interrupted) Thread.currentThread().interrupt();
  }
}
