package toktrak.tests;

import static org.junit.jupiter.api.Assertions.*;
import static toktrak.store.EventTypes.*;

import java.time.Instant;
import java.util.Map;
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
}
