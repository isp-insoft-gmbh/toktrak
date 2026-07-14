package toktrak.tests;

import toktrak.*;

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
