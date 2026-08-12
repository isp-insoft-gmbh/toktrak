package toktrak.tests;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import toktrak.json.Json;
import toktrak.store.EventEnvelope;

final class JsonTest {
  @Test
  void given_eventEnvelope_when_serializingAndParsingString_then_roundTripsEnvelope() {
    var event =
        new EventEnvelope(
            java.util.UUID.fromString("00000000-0000-4000-8000-000000000001"),
            Instant.parse("2026-07-10T00:00:00Z"),
            "event",
            1,
            "system",
            Map.of("enabled", true));

    String json = Json.write(event);

    assertEquals(event, Json.read(json, EventEnvelope.class));
    assertEquals(
        "{\"id\":\"00000000-0000-4000-8000-000000000001\",\"at\":\"2026-07-10T00:00:00Z\",\"type\":\"event\",\"schemaVersion\":1,\"actor\":\"system\",\"data\":{\"enabled\":true}}",
        json);
  }

  @Test
  void given_invalidJson_when_parsingStringAndBytes_then_reportsParseFailure() {
    assertThrows(IllegalStateException.class, () -> Json.read("{", EventEnvelope.class));
    assertThrows(
        IllegalStateException.class,
        () -> Json.read("{".getBytes(StandardCharsets.UTF_8), EventEnvelope.class));
  }

  @Test
  void given_oversizedBytes_when_parsingDocument_then_rejectsDocument() {
    var exception =
        assertThrows(
            IllegalArgumentException.class,
            () -> Json.read(new byte[10 * 1024 * 1024], EventEnvelope.class));
    assertEquals("JSON document exceeds 10485759 bytes", exception.getMessage());
  }
}
