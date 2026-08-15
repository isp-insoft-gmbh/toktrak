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
  void given_invalidCoreFields_when_creatingEnvelope_then_rejectsFields() {
    for (String type : new String[] {null, ""}) {
      var exception =
          assertThrows(
              IllegalArgumentException.class,
              () -> new EventEnvelope(UUID.randomUUID(), AT, type, 1, "system", Map.of()));
      assertEquals("event type is required", exception.getMessage());
    }
    var exception =
        assertThrows(
            IllegalArgumentException.class,
            () -> new EventEnvelope(UUID.randomUUID(), AT, "event", 0, "system", Map.of()));
    assertEquals("event schema version must be positive", exception.getMessage());
    exception =
        assertThrows(
            IllegalArgumentException.class,
            () -> new EventEnvelope(UUID.randomUUID(), AT, "event", 2, "system", Map.of()));
    assertEquals("unsupported event schema version: 2", exception.getMessage());
  }

  @Test
  void given_invalidDataValues_when_creatingEnvelope_then_rejectsValues() {
    var repeated = new ArrayList<>();
    var data = new LinkedHashMap<String, Object>();
    data.put("first", repeated);
    data.put("second", repeated);
    assertEquals(
        "event data containers must not repeat",
        assertThrows(
                IllegalArgumentException.class,
                () -> EventEnvelope.create("event", AT, "system", data))
            .getMessage());
    assertEquals(
        "event data contains unsupported value: java.lang.Object",
        assertThrows(
                IllegalArgumentException.class,
                () -> EventEnvelope.create("event", AT, "system", Map.of("value", new Object())))
            .getMessage());
    assertEquals(
        "event data string exceeds 1048576 characters",
        assertThrows(
                IllegalArgumentException.class,
                () ->
                    EventEnvelope.create(
                        "event", AT, "system", Map.of("value", "x".repeat(1_048_577))))
            .getMessage());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            EventEnvelope.create(
                "event", AT, "system", Map.of("value", java.math.BigInteger.TEN.pow(5_000))));
  }

  @Test
  void given_typeAboveUtf8Limit_when_creatingEnvelope_then_rejectsType() {
    var ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> EventEnvelope.create("t".repeat(129), AT, "system", Map.of()));
    assertEquals("event type exceeds 128 UTF-8 bytes", ex.getMessage());
  }

  @Test
  void given_actorAboveUtf8Limit_when_creatingEnvelope_then_rejectsActor() {
    var ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> EventEnvelope.create("event", AT, "a".repeat(257), Map.of()));
    assertEquals("event actor exceeds 256 UTF-8 bytes", ex.getMessage());
  }

  @Test
  void given_nestedValuesAboveCountLimit_when_creatingEnvelope_then_rejectsData() {
    var data = Map.<String, Object>of("values", Collections.nCopies(100_001, 0));
    var exception =
        assertThrows(
            IllegalArgumentException.class,
            () -> EventEnvelope.create("event", AT, "system", data));
    assertEquals("event data exceeds 100000 values", exception.getMessage());
  }

  @Test
  void given_dataAtValueAndDepthLimits_when_creatingEnvelope_then_preservesData() {
    var values = Collections.nCopies(99_998, 0);
    var valueLimited = EventEnvelope.create("event", AT, "system", Map.of("values", values));
    assertEquals(values, valueLimited.data().get("values"));

    Object nested = 0;
    for (int depth = 0; depth < 31; depth++) nested = new ArrayList<>(java.util.List.of(nested));
    var depthLimited = EventEnvelope.create("event", AT, "system", Map.of("nested", nested));
    assertEquals(nested, depthLimited.data().get("nested"));
  }

  @Test
  void given_topLevelDataAtEntryLimit_when_creatingEnvelope_then_acceptsData() {
    var data = new LinkedHashMap<String, Object>();
    for (int index = 0; index < 1_024; index++) data.put(Integer.toString(index), index);
    var event = new EventEnvelope(UUID.randomUUID(), AT, "event", 1, "system", data);
    assertEquals(1_024, event.data().size());
  }

  @Test
  void given_nestedDataAboveDepthLimit_when_creatingEnvelope_then_rejectsData() {
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
  void given_topLevelDataAboveEntryLimit_when_creatingEnvelope_then_rejectsData() {
    var data = new LinkedHashMap<String, Object>();
    for (int index = 0; index < 1_025; index++) data.put(Integer.toString(index), index);

    var ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> new EventEnvelope(UUID.randomUUID(), AT, "event", 1, "system", data));
    assertEquals("event data exceeds 1024 entries", ex.getMessage());
  }
}
