package toktrak.tests;

import static org.junit.jupiter.api.Assertions.*;
import static toktrak.store.EventTypes.*;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import toktrak.projection.Projection;
import toktrak.store.EventEnvelope;

final class ProjectionTest {
  @Test
  void given_compatibleSnapshotAndRawEvents_when_rebuildingProjection_then_countsFromSnapshot() {
    var t = Instant.parse("2026-07-10T00:00:00Z");
    var old = EventEnvelope.create("dev-test", t, "system", Map.of());
    var snap =
        EventEnvelope.create(
            "projection-snapshot",
            t,
            "system",
            Map.of("projectionVersion", Projection.VERSION, "eventCount", 5));
    var newer = EventEnvelope.create("dev-test", t, "system", Map.of());
    var projection = Projection.empty();
    projection.apply(old);
    projection.apply(snap);
    projection.apply(newer);
    assertEquals(6, projection.eventCount());
  }

  @Test
  void given_zeroEventSnapshot_when_rebuildingProjection_then_preservesZeroCount() {
    var projection = Projection.empty();
    projection.apply(
        EventEnvelope.create(
            "projection-snapshot",
            Instant.parse("2026-07-10T00:00:00Z"),
            "system",
            Map.of("projectionVersion", Projection.VERSION, "eventCount", 0)));
    assertEquals(0, projection.eventCount());
  }

  @Test
  void given_maximumSnapshotCount_when_applyingRawEvent_then_throwsArithmeticException() {
    var at = Instant.parse("2026-07-10T00:00:00Z");
    var projection = Projection.empty();
    projection.apply(
        EventEnvelope.create(
            "projection-snapshot",
            at,
            "system",
            Map.of("projectionVersion", Projection.VERSION, "eventCount", Integer.MAX_VALUE)));

    var event = EventEnvelope.create("dev-test", at, "system", Map.of());
    assertThrows(ArithmeticException.class, () -> projection.apply(event));
  }

  @Test
  void given_projectionState_when_snapshottingData_then_returnsVersionAndCount() {
    var projection = Projection.empty();
    projection.apply(
        EventEnvelope.create(
            "dev-test", Instant.parse("2026-07-10T00:00:00Z"), "system", Map.of()));

    assertEquals(
        Map.of(
            "projectionVersion",
            Projection.VERSION,
            "eventCount",
            1,
            "users",
            java.util.List.of(),
            "trackerTokens",
            java.util.List.of()),
        projection.snapshotData());
  }

  @Test
  void given_stalePreparedTransition_when_committingTransition_then_rejectsCommit() {
    var projection = Projection.empty();
    var at = Instant.parse("2026-07-10T00:00:00Z");
    var transition = projection.prepare(EventEnvelope.create("first", at, "system", Map.of()));
    projection.apply(EventEnvelope.create("second", at, "system", Map.of()));
    assertEquals(1, projection.revision());

    var exception = assertThrows(IllegalStateException.class, () -> projection.commit(transition));
    assertEquals("projection changed before prepared transition commit", exception.getMessage());
  }

  @Test
  void given_corruptSnapshotValues_when_applyingSnapshot_then_rejectsSnapshot() {
    var at = Instant.parse("2026-07-10T00:00:00Z");
    for (Object value : java.util.List.of("one", 1.5, Long.MAX_VALUE, -1)) {
      var event =
          EventEnvelope.create(
              "projection-snapshot",
              at,
              "system",
              Map.of("projectionVersion", Projection.VERSION, "eventCount", value));
      assertThrows(IllegalStateException.class, () -> Projection.empty().apply(event));
    }
  }

  @Test
  void given_invalidIdentityEvents_when_applyingProjection_then_rejectsBrokenInvariants() {
    var at = Instant.parse("2026-07-10T00:00:00Z");
    var projection = Projection.empty();
    Map<String, Object> key = Map.of("issuer", "https://issuer.example", "subject", "subject-1");
    assertThrows(
        IllegalStateException.class,
        () -> projection.apply(EventEnvelope.create(IDENTITY_USER_DEACTIVATED, at, "actor", key)));
    assertThrows(
        IllegalStateException.class,
        () ->
            projection.apply(
                EventEnvelope.create(
                    IDENTITY_TRACKER_TOKEN_CREATED,
                    at,
                    "actor",
                    Map.of(
                        "issuer", "https://issuer.example",
                        "subject", "subject-1",
                        "tokenId", "00000000-0000-4000-8000-000000000001",
                        "label", "Laptop",
                        "digest",
                            java.util.Base64.getUrlEncoder()
                                .withoutPadding()
                                .encodeToString(new byte[32])))));
    assertThrows(
        IllegalStateException.class,
        () ->
            projection.apply(
                EventEnvelope.create(
                    PROJECTION_SNAPSHOT,
                    at,
                    "system",
                    Map.of(
                        "projectionVersion",
                        Projection.VERSION,
                        "eventCount",
                        1,
                        "users",
                        "invalid",
                        "trackerTokens",
                        java.util.List.of()))));
    assertThrows(
        IllegalArgumentException.class,
        () -> new Projection.UserKey("https://issuer.example", "é".repeat(129)));
    assertThrows(IllegalArgumentException.class, () -> new Projection.UserKey(null, "subject"));
    assertThrows(IllegalArgumentException.class, () -> new Projection.UserKey(" ", "subject"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new Projection.UserKey("https://issuer.example", null));
    assertThrows(
        IllegalArgumentException.class,
        () -> new Projection.UserKey("https://issuer.example", " "));
  }

  @Test
  void given_userKeysAtDocumentedLimits_when_constructing_then_acceptsEveryExactLimit() {
    assertDoesNotThrow(() -> new Projection.UserKey("i".repeat(2_048), "subject-1"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new Projection.UserKey("i".repeat(2_049), "subject-1"));
    assertDoesNotThrow(() -> new Projection.UserKey("https://issuer.example", "s".repeat(256)));
    assertThrows(
        IllegalArgumentException.class,
        () -> new Projection.UserKey("https://issuer.example", "s".repeat(257)));
    assertDoesNotThrow(() -> new Projection.UserKey("https://issuer.example", "é".repeat(128)));
  }

  @Test
  void given_tokenPageAtPageSizeMaximum_when_constructing_then_acceptsFullPage() {
    var owner = new Projection.UserKey("https://issuer.example", "subject-1");
    var at = Instant.parse("2026-07-10T00:00:00Z");
    String digest = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);
    var tokens = new java.util.ArrayList<Projection.TrackerToken>();
    for (int index = 0; index <= 100; index++) {
      tokens.add(
          new Projection.TrackerToken(
              new java.util.UUID(0, index), owner, "Token " + index, digest, at, null, null));
    }
    var full = tokens.subList(0, 100);
    var page = new Projection.TokenPage(full, 250, 2, 3);
    assertEquals(100, page.tokens().size());
    assertThrows(IllegalArgumentException.class, () -> new Projection.TokenPage(tokens, 250, 2, 3));
  }

  @Test
  void given_corruptIdentitySnapshots_when_applyingProjection_then_rejectsInvalidFields() {
    var at = Instant.parse("2026-07-10T00:00:00Z");
    Map<String, Object> user =
        Map.of(
            "issuer", "https://issuer.example",
            "subject", "subject-1",
            "email", "user@example.com",
            "displayName", "Example User",
            "color", "#a8dadc",
            "active", true,
            "authenticatedAt", at.toString());
    var invalidActive = new java.util.HashMap<>(user);
    invalidActive.put("active", "yes");
    var invalidColor = new java.util.HashMap<>(user);
    invalidColor.put("color", "blue");
    var invalidInstant = new java.util.HashMap<>(user);
    invalidInstant.put("authenticatedAt", "today");
    for (Object users :
        java.util.List.of(
            java.util.List.of("invalid"),
            java.util.List.of(Map.copyOf(invalidActive)),
            java.util.List.of(Map.copyOf(invalidColor)),
            java.util.List.of(Map.copyOf(invalidInstant)),
            java.util.List.of(new java.util.HashMap<>(user), new java.util.HashMap<>(user)))) {
      var event =
          EventEnvelope.create(
              PROJECTION_SNAPSHOT,
              at,
              "system",
              Map.of(
                  "projectionVersion",
                  Projection.VERSION,
                  "eventCount",
                  1,
                  "users",
                  users,
                  "trackerTokens",
                  java.util.List.of()));
      assertThrows(IllegalStateException.class, () -> Projection.empty().apply(event));
    }
    Map<String, Object> orphanToken =
        Map.of(
            "tokenId", "00000000-0000-4000-8000-000000000001",
            "issuer", "https://issuer.example",
            "subject", "missing",
            "label", "Laptop",
            "digest",
                java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]),
            "createdAt", at.toString());
    var orphan =
        EventEnvelope.create(
            PROJECTION_SNAPSHOT,
            at,
            "system",
            Map.of(
                "projectionVersion",
                Projection.VERSION,
                "eventCount",
                1,
                "users",
                java.util.List.of(user),
                "trackerTokens",
                java.util.List.of(orphanToken)));
    assertThrows(IllegalStateException.class, () -> Projection.empty().apply(orphan));
  }

  @Test
  void given_incompatibleSnapshotAndRawEvents_when_rebuildingProjection_then_countsRawEvents() {
    var t = Instant.parse("2026-07-10T00:00:00Z");
    var raw = EventEnvelope.create("dev-test", t, "system", Map.of());
    var snap =
        EventEnvelope.create(
            "projection-snapshot", t, "system", Map.of("projectionVersion", -1, "eventCount", 100));
    var projection = Projection.empty();
    projection.apply(raw);
    projection.apply(snap);
    assertEquals(1, projection.eventCount());
  }

  @Test
  void given_waiterAtCurrentRevision_when_projectionCommits_then_wakesWithNewRevision()
      throws Exception {
    var at = Instant.parse("2026-07-10T00:00:00Z");
    var projection = Projection.empty();
    projection.apply(EventEnvelope.create("first", at, "system", Map.of()));
    var observed = new AtomicLong(-1);
    Thread waiter =
        Thread.ofPlatform()
            .start(
                () -> {
                  try {
                    observed.set(projection.awaitRevision(1, Duration.ofSeconds(30)));
                  } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                  }
                });
    assertTrue(awaitTimedWaiting(waiter), "waiter must block on the projection monitor");
    assertEquals(-1, observed.get());

    projection.apply(EventEnvelope.create("second", at, "system", Map.of()));
    waiter.join(Duration.ofSeconds(2).toMillis());

    assertFalse(waiter.isAlive(), "commit must wake the waiter");
    assertEquals(2, observed.get());
  }

  @Test
  void given_waiterAtCurrentRevision_when_timeoutElapses_then_returnsUnchangedRevision()
      throws Exception {
    var at = Instant.parse("2026-07-10T00:00:00Z");
    var projection = Projection.empty();
    projection.apply(EventEnvelope.create("first", at, "system", Map.of()));
    var timeout = Duration.ofMillis(50);

    long started = System.nanoTime();
    long revision = projection.awaitRevision(1, timeout);
    long elapsedNanos = System.nanoTime() - started;

    assertEquals(1, revision);
    assertTrue(elapsedNanos >= timeout.toNanos(), "wait must honour the full timeout");
    assertEquals(1, projection.awaitRevision(0, timeout));
    assertEquals(0, Projection.empty().awaitRevision(0, Duration.ofMillis(1)));
  }

  @Test
  void given_revisionWaitArguments_when_outsideDocumentedBounds_then_rejectsThem() {
    var projection = Projection.empty();
    assertThrows(
        IllegalArgumentException.class, () -> projection.awaitRevision(-1, Duration.ofSeconds(1)));
    assertThrows(IllegalArgumentException.class, () -> projection.awaitRevision(0, Duration.ZERO));
    assertThrows(
        IllegalArgumentException.class, () -> projection.awaitRevision(0, Duration.ofMillis(-1)));
    assertThrows(
        IllegalArgumentException.class,
        () -> projection.awaitRevision(0, Duration.ofSeconds(30).plusNanos(1)));
  }

  @Test
  void given_usersAndTokens_when_snapshottingAndReplaying_then_restoresSortedState() {
    var at = Instant.parse("2026-07-10T00:00:00Z");
    var projection = Projection.empty();
    for (int index = 6; index >= 1; index--) {
      projection.apply(
          EventEnvelope.create(IDENTITY_USER_AUTHENTICATED, at, "actor", user("subject-" + index)));
    }
    var owner = new Projection.UserKey("https://issuer.example", "subject-3");
    for (int index = 6; index >= 1; index--) {
      projection.apply(
          EventEnvelope.create(
              IDENTITY_TRACKER_TOKEN_CREATED,
              at.plusSeconds(index),
              "actor",
              token("subject-3", new UUID(0, index), (byte) index)));
    }
    projection.apply(
        EventEnvelope.create(
            IDENTITY_USER_DEACTIVATED,
            at,
            "actor",
            Map.of("issuer", "https://issuer.example", "subject", "subject-2")));
    var inactive = new Projection.UserKey("https://issuer.example", "subject-2");
    assertTrue(projection.user(inactive).isPresent());
    assertTrue(projection.activeUser(inactive).isEmpty());

    var subjects = projection.users().stream().map(user -> user.key().subject()).toList();
    assertEquals(
        List.of("subject-1", "subject-2", "subject-3", "subject-4", "subject-5", "subject-6"),
        subjects);
    var tokenIds =
        projection.trackerTokens(owner).stream().map(Projection.TrackerToken::id).toList();
    assertEquals(
        List.of(
            new UUID(0, 1),
            new UUID(0, 2),
            new UUID(0, 3),
            new UUID(0, 4),
            new UUID(0, 5),
            new UUID(0, 6)),
        tokenIds);

    Map<String, Object> snapshot = projection.snapshotData();
    assertEquals(13, snapshot.get("eventCount"));
    @SuppressWarnings("unchecked")
    var snapshotUsers = (List<Map<String, Object>>) snapshot.get("users");
    assertEquals(subjects, snapshotUsers.stream().map(user -> user.get("subject")).toList());
    assertEquals(false, snapshotUsers.get(1).get("active"));
    @SuppressWarnings("unchecked")
    var snapshotTokens = (List<Map<String, Object>>) snapshot.get("trackerTokens");
    assertEquals(
        tokenIds.stream().map(UUID::toString).toList(),
        snapshotTokens.stream().map(token -> token.get("tokenId")).toList());

    var restored = Projection.empty();
    restored.apply(EventEnvelope.create(PROJECTION_SNAPSHOT, at, "system", snapshot));
    assertEquals(13, restored.eventCount());
    assertEquals(projection.users(), restored.users());
    assertEquals(projection.trackerTokens(owner), restored.trackerTokens(owner));
    assertTrue(restored.activeUser(inactive).isEmpty());
    assertEquals(snapshot, restored.snapshotData());
  }

  @Test
  void given_snapshotWithNonNumericVersion_when_applying_then_ignoresSnapshot() {
    var at = Instant.parse("2026-07-10T00:00:00Z");
    var projection = Projection.empty();
    projection.apply(EventEnvelope.create("dev-test", at, "system", Map.of()));
    projection.apply(
        EventEnvelope.create(
            PROJECTION_SNAPSHOT,
            at,
            "system",
            Map.of("projectionVersion", Integer.toString(Projection.VERSION), "eventCount", 100)));
    assertEquals(1, projection.eventCount());
    assertEquals(2, projection.revision());
  }

  @Test
  void given_userLimit_when_snapshottingAndAuthenticating_then_acceptsExactlyTenThousandUsers() {
    var at = Instant.parse("2026-07-10T00:00:00Z");
    var users = new ArrayList<Map<String, Object>>();
    for (int index = 0; index < 10_000; index++) {
      users.add(snapshotUser("subject-" + index, at));
    }
    var projection = Projection.empty();
    projection.apply(EventEnvelope.create(PROJECTION_SNAPSHOT, at, "system", snapshot(users)));
    assertEquals(10_000, projection.users().size());

    projection.apply(
        EventEnvelope.create(IDENTITY_USER_AUTHENTICATED, at, "actor", user("subject-0")));
    assertEquals(10_000, projection.users().size());
    var overflow =
        EventEnvelope.create(IDENTITY_USER_AUTHENTICATED, at, "actor", user("subject-10000"));
    var exception = assertThrows(IllegalStateException.class, () -> projection.apply(overflow));
    assertEquals("users exceed 10000", exception.getMessage());

    users.add(snapshotUser("subject-10000", at));
    var oversized = EventEnvelope.create(PROJECTION_SNAPSHOT, at, "system", snapshot(users));
    var rejected =
        assertThrows(IllegalStateException.class, () -> Projection.empty().apply(oversized));
    assertEquals("projection snapshot exceeds collection limits", rejected.getMessage());
  }

  @Test
  void given_stringFieldsAtCharacterLimits_when_applying_then_acceptsExactLimitOnly() {
    var at = Instant.parse("2026-07-10T00:00:00Z");
    var projection = Projection.empty();
    projection.apply(
        EventEnvelope.create(IDENTITY_USER_AUTHENTICATED, at, "actor", user("subject-1")));
    var exact = new HashMap<>(token("subject-1", new UUID(0, 1), (byte) 1));
    exact.put("label", "l".repeat(128));
    projection.apply(EventEnvelope.create(IDENTITY_TRACKER_TOKEN_CREATED, at, "actor", exact));
    assertEquals(128, projection.trackerToken(new UUID(0, 1)).orElseThrow().label().length());

    var over = new HashMap<>(token("subject-1", new UUID(0, 2), (byte) 2));
    over.put("label", "l".repeat(129));
    var event = EventEnvelope.create(IDENTITY_TRACKER_TOKEN_CREATED, at, "actor", over);
    var exception = assertThrows(IllegalStateException.class, () -> projection.apply(event));
    assertEquals("invalid label", exception.getMessage());
  }

  @Test
  void given_fxRateAtTenEurPerUsd_when_applying_then_acceptsExactUpperBound() {
    var at = Instant.parse("2026-07-10T00:00:00Z");
    var projection = Projection.empty();
    projection.apply(
        EventEnvelope.create(
            FX_RATE_UPDATED, at, "system", Map.of("date", "2026-07-10", "eurPerUsd", "10")));
    assertEquals(
        0, projection.fxRate().orElseThrow().eurPerUsd().compareTo(java.math.BigDecimal.TEN));

    var over =
        EventEnvelope.create(
            FX_RATE_UPDATED, at, "system", Map.of("date", "2026-07-10", "eurPerUsd", "10.01"));
    var exception = assertThrows(IllegalStateException.class, () -> projection.apply(over));
    assertEquals("FX rate is invalid", exception.getMessage());
    assertEquals(
        0, projection.fxRate().orElseThrow().eurPerUsd().compareTo(java.math.BigDecimal.TEN));
  }

  private static boolean awaitTimedWaiting(Thread thread) throws InterruptedException {
    for (int attempt = 0; attempt < 2_000; attempt++) {
      if (thread.getState() == Thread.State.TIMED_WAITING) return true;
      if (!thread.isAlive()) return false;
      Thread.sleep(1);
    }
    return false;
  }

  private static Map<String, Object> user(String subject) {
    return Map.of(
        "issuer",
        "https://issuer.example",
        "subject",
        subject,
        "email",
        subject + "@example.com",
        "displayName",
        "User " + subject,
        "color",
        "#a8dadc");
  }

  private static Map<String, Object> snapshotUser(String subject, Instant at) {
    var data = new HashMap<>(user(subject));
    data.put("active", true);
    data.put("authenticatedAt", at.toString());
    return Map.copyOf(data);
  }

  private static Map<String, Object> token(String subject, UUID id, byte seed) {
    var digest = new byte[32];
    digest[0] = seed;
    return Map.of(
        "issuer",
        "https://issuer.example",
        "subject",
        subject,
        "tokenId",
        id.toString(),
        "label",
        "Laptop",
        "digest",
        Base64.getUrlEncoder().withoutPadding().encodeToString(digest));
  }

  private static Map<String, Object> snapshot(List<Map<String, Object>> users) {
    return Map.of(
        "projectionVersion",
        Projection.VERSION,
        "eventCount",
        1,
        "users",
        List.copyOf(users),
        "trackerTokens",
        List.of());
  }
}
