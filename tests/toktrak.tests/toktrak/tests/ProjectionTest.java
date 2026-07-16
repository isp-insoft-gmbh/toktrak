package toktrak.tests;

import static org.junit.jupiter.api.Assertions.*;

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
