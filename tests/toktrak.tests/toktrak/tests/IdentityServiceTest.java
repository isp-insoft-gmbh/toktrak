package toktrak.tests;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.time.Instant;
import java.util.HashSet;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import toktrak.ClockSource;
import toktrak.health.HealthState;
import toktrak.identity.IdentityService;
import toktrak.projection.Projection;
import toktrak.projection.Projection.UserKey;
import toktrak.store.EventLog;
import toktrak.store.Writer;

final class IdentityServiceTest {
  private static final Instant NOW = Instant.parse("2026-07-10T12:00:00Z");
  private static final UserKey USER = new UserKey("https://issuer.example", "subject-1");
  @TempDir Path directory;

  @Test
  void given_activeUser_when_usingAndRevokingTrackerToken_then_enforcesLifecycle() {
    var projection = Projection.empty();
    try (var log = EventLog.open(directory.resolve("events.ndjson"));
        var writer = writer(log, projection)) {
      var identities = new IdentityService(writer, projection, new byte[32]);
      identities.authenticateUser(USER, "user@example.com", "Example User", null);

      var created = identities.createTrackerToken(USER, "Laptop");
      assertTrue(created.plaintext().startsWith("tt_"));
      assertFalse(projection.snapshotData().toString().contains(created.plaintext()));
      byte[] digest = java.util.Base64.getUrlDecoder().decode(created.token().digest());
      assertThrows(
          java.util.concurrent.CompletionException.class,
          () ->
              writer
                  .submit(
                      toktrak.store.WriteCommand.trackerTokenUsed(
                          java.util.UUID.randomUUID(), digest))
                  .join());
      assertEquals(USER, identities.authenticateTrackerToken(created.plaintext()));
      assertEquals(NOW, projection.trackerToken(created.token().id()).orElseThrow().lastUsedAt());

      UserKey other = new UserKey("https://issuer.example", "subject-2");
      identities.authenticateUser(other, "other@example.com", "Other User", null);
      assertThrows(
          IllegalStateException.class,
          () -> identities.revokeTrackerToken(other, created.token().id()));
      identities.revokeTrackerToken(USER, created.token().id());
      assertThrows(
          IllegalArgumentException.class,
          () -> identities.authenticateTrackerToken(created.plaintext()));
      identities.deactivate(USER);
      assertTrue(projection.activeUser(USER).isEmpty());
      assertThrows(IllegalStateException.class, () -> identities.createTrackerToken(USER, "Other"));
    }
  }

  @Test
  void given_invalidIdentityInputs_when_mutating_then_rejectsBeforePersistence() {
    var projection = Projection.empty();
    try (var log = EventLog.open(directory.resolve("events.ndjson"));
        var writer = writer(log, projection)) {
      assertThrows(
          IllegalArgumentException.class,
          () -> new IdentityService(writer, projection, new byte[31]));
      var identities = new IdentityService(writer, projection, new byte[32]);
      assertThrows(
          IllegalArgumentException.class,
          () -> identities.authenticateUser(USER, "", "Example User", null));
      assertThrows(
          IllegalArgumentException.class,
          () -> identities.authenticateUser(USER, "user@example.com", "Example User", "red"));
      identities.authenticateUser(USER, "user@example.com", "Example User", null);
      assertThrows(IllegalArgumentException.class, () -> identities.createTrackerToken(USER, " "));
      assertThrows(
          IllegalArgumentException.class,
          () -> identities.authenticateTrackerToken("x".repeat(257)));
      identities.deactivate(USER);
      assertThrows(IllegalStateException.class, () -> identities.deactivate(USER));
      assertThrows(IllegalStateException.class, () -> identities.trackerTokens(USER));
    }
  }

  @Test
  void given_persistedIdentityEvents_when_replaying_then_restoresUsersAndTokens() {
    var projection = Projection.empty();
    java.util.UUID tokenId;
    Path path = directory.resolve("events.ndjson");
    try (var log = EventLog.open(path);
        var writer = writer(log, projection)) {
      var identities = new IdentityService(writer, projection, new byte[32]);
      identities.authenticateUser(USER, "user@example.com", "Example User", "#a8dadc");
      tokenId = identities.createTrackerToken(USER, "Laptop").token().id();
      writer.submit(toktrak.store.WriteCommand.snapshot("system")).join();
    }

    var rebuilt = Projection.empty();
    try (var log = EventLog.open(path)) {
      log.replay(rebuilt::apply);
    }
    assertTrue(rebuilt.activeUser(USER).isPresent());
    assertEquals("Laptop", rebuilt.trackerToken(tokenId).orElseThrow().label());
  }

  @Test
  void given_concurrentTokenCreates_when_writerSerializesMutations_then_preservesEveryToken()
      throws Exception {
    var projection = Projection.empty();
    try (var log = EventLog.open(directory.resolve("events.ndjson"));
        var writer = writer(log, projection);
        var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var identities = new IdentityService(writer, projection, new byte[32]);
      identities.authenticateUser(USER, "user@example.com", "Example User", null);
      var futures =
          java.util.stream.IntStream.range(0, 16)
              .mapToObj(
                  index ->
                      executor.submit(() -> identities.createTrackerToken(USER, "Token " + index)))
              .toList();
      var plaintext = new HashSet<String>();
      for (var future : futures) plaintext.add(future.get().plaintext());
      assertEquals(16, plaintext.size());
      assertEquals(16, identities.trackerTokens(USER).size());
    }
  }

  private static Writer writer(EventLog log, Projection projection) {
    return Writer.start(log, projection, new HealthState(), ClockSource.fixed(NOW), false);
  }
}
