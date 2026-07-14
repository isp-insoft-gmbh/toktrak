package toktrak.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import toktrak.store.EventEnvelope;

final class EventEnvelopeTest {
  private static final Instant AT = Instant.parse("2026-07-10T00:00:00Z");

  @Test
  void rejectsTypeAboveUtf8Limit() {
    var ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> EventEnvelope.create("t".repeat(129), AT, "system", Map.of()));
    assertEquals("event type exceeds 128 UTF-8 bytes", ex.getMessage());
  }

  @Test
  void rejectsActorAboveUtf8Limit() {
    var ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> EventEnvelope.create("event", AT, "a".repeat(257), Map.of()));
    assertEquals("event actor exceeds 256 UTF-8 bytes", ex.getMessage());
  }

  @Test
  void rejectsTooManyNestedValues() {
    var data = Map.<String, Object>of("values", Collections.nCopies(100_001, 0));
    var exception =
        assertThrows(
            IllegalArgumentException.class,
            () -> EventEnvelope.create("event", AT, "system", data));
    assertEquals("event data exceeds 100000 values", exception.getMessage());
  }

  @Test
  void rejectsNestedDataAboveDepthLimit() {
    Object value = 0;
    for (int depth = 0; depth < 33; depth++) value = new ArrayList<>(java.util.List.of(value));
    var data = Map.<String, Object>of("nested", value);
    var exception =
        assertThrows(
            IllegalArgumentException.class,
            () -> EventEnvelope.create("event", AT, "system", data));
    assertEquals("event data exceeds 32 nesting levels", exception.getMessage());
  }

  @Test
  void rejectsTooManyTopLevelDataEntries() {
    var data = new LinkedHashMap<String, Object>();
    for (int index = 0; index < 1_025; index++) data.put(Integer.toString(index), index);

    var ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> new EventEnvelope(UUID.randomUUID(), AT, "event", 1, "system", data));
    assertEquals("event data exceeds 1024 entries", ex.getMessage());
  }
}
