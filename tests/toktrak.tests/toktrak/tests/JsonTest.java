package toktrak.tests;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import toktrak.json.Json;
import toktrak.store.EventEnvelope;

final class JsonTest {
  // One level and thirteen plus three-per-value tokens above the EventEnvelope data limits.
  private static final int NESTING_DEPTH_MAX = 33;
  private static final int TOKENS_MAX = 300_013;
  private static final int NUMBER_DIGITS_MAX = 256;
  private static final int STRING_CHARACTERS_MAX = 1024 * 1024;
  private static final int DOCUMENT_BYTES_MAX = 10 * 1024 * 1024 - 1;
  // Jackson checks the document length of a String only when it refills its read buffer.
  private static final int READ_BUFFER_CHARACTERS = 64 * 1024;

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
  void given_instantWithNanoseconds_when_serializingAndParsing_then_preservesPrecision() {
    var at = Instant.parse("2026-07-10T01:02:03.123456789Z");
    var event = EventEnvelope.create("event", at, "system", Map.of());

    String json = Json.write(event);

    assertTrue(json.contains("\"at\":\"2026-07-10T01:02:03.123456789Z\""), json);
    assertEquals(at, Json.read(json, EventEnvelope.class).at());
  }

  @Test
  void given_unparsableInstant_when_parsingStringAndBytes_then_reportsParseFailure() {
    String json =
        "{\"id\":\"00000000-0000-4000-8000-000000000001\",\"at\":\"2026-07-10\",\"type\":\"event\",\"schemaVersion\":1,\"actor\":\"system\",\"data\":{}}";

    var fromString =
        assertThrows(IllegalStateException.class, () -> Json.read(json, EventEnvelope.class));
    var fromBytes =
        assertThrows(
            IllegalStateException.class, () -> Json.read(bytes(json), EventEnvelope.class));

    assertEquals("cannot parse JSON", fromString.getMessage());
    assertEquals("cannot parse JSON", fromBytes.getMessage());
    assertTrue(fromString.getCause().getMessage().contains("'2026-07-10' could not be parsed"));
    assertTrue(fromBytes.getCause().getMessage().contains("'2026-07-10' could not be parsed"));
  }

  @Test
  void given_decimalFraction_when_parsing_then_preservesExactDecimal() {
    Map<?, ?> parsed = Json.read(bytes("{\"cost\":0.10,\"tokens\":1}"), Map.class);

    assertEquals(new BigDecimal("0.10"), parsed.get("cost"));
    assertEquals(1, parsed.get("tokens"));
  }

  @Test
  void given_invalidJson_when_parsingStringAndBytes_then_reportsParseFailure() {
    assertThrows(IllegalStateException.class, () -> Json.read("{", EventEnvelope.class));
    assertThrows(
        IllegalStateException.class,
        () -> Json.read("{".getBytes(StandardCharsets.UTF_8), EventEnvelope.class));
  }

  @Test
  void given_nullDocument_when_parsingStringAndBytes_then_reportsParseFailure() {
    var fromString = assertThrows(IllegalStateException.class, () -> Json.read("null", Map.class));
    var fromBytes =
        assertThrows(IllegalStateException.class, () -> Json.read(bytes(" null "), Map.class));

    assertEquals("JSON document has no value", fromString.getMessage());
    assertEquals("JSON document has no value", fromBytes.getMessage());
  }

  @Test
  void given_nestingAtLimit_when_parsingStringAndBytes_then_acceptsDocument() {
    String json = nestedArrays(NESTING_DEPTH_MAX);

    assertEquals(NESTING_DEPTH_MAX, depth(Json.read(json, List.class)));
    assertEquals(NESTING_DEPTH_MAX, depth(Json.read(bytes(json), List.class)));
  }

  @Test
  void given_nestingAboveLimit_when_parsingStringAndBytes_then_rejectsDocument() {
    String json = nestedArrays(NESTING_DEPTH_MAX + 1);

    assertParseFailure(() -> Json.read(json, List.class), NESTING_DEPTH_MAX);
    assertParseFailure(() -> Json.read(bytes(json), List.class), NESTING_DEPTH_MAX);
  }

  @Test
  void given_numberAtDigitLimit_when_parsingBytes_then_acceptsNumber() {
    String integer = "9".repeat(NUMBER_DIGITS_MAX);
    String decimal = "1." + "0".repeat(NUMBER_DIGITS_MAX - 1);

    List<?> parsed = Json.read(bytes("[" + integer + "," + decimal + "]"), List.class);

    assertEquals(List.of(new BigInteger(integer), new BigDecimal(decimal)), parsed);
  }

  @Test
  void given_numberAboveDigitLimit_when_parsingBytes_then_rejectsDocument() {
    String integer = "9".repeat(NUMBER_DIGITS_MAX + 1);
    String decimal = "1." + "0".repeat(NUMBER_DIGITS_MAX);

    assertParseFailure(() -> Json.read(bytes("[" + integer + "]"), List.class), NUMBER_DIGITS_MAX);
    assertParseFailure(() -> Json.read(bytes("[" + decimal + "]"), List.class), NUMBER_DIGITS_MAX);
  }

  @Test
  void given_stringAtCharacterLimit_when_parsingBytes_then_acceptsString() {
    String text = "a".repeat(STRING_CHARACTERS_MAX);

    List<?> parsed = Json.read(bytes("[\"" + text + "\"]"), List.class);

    assertEquals(List.of(text), parsed);
  }

  @Test
  void given_stringAboveCharacterLimit_when_parsingBytes_then_rejectsDocument() {
    String text = "a".repeat(STRING_CHARACTERS_MAX + 1);

    assertParseFailure(
        () -> Json.read(bytes("[\"" + text + "\"]"), List.class), STRING_CHARACTERS_MAX);
  }

  @Test
  void given_tokenCountAtLimit_when_parsingBytes_then_acceptsDocument() {
    List<?> parsed = Json.read(bytes(numberArray(TOKENS_MAX - 2)), List.class);

    assertEquals(TOKENS_MAX - 2, parsed.size());
  }

  @Test
  void given_tokenCountAboveLimit_when_parsingBytes_then_rejectsDocument() {
    byte[] json = bytes(numberArray(TOKENS_MAX - 1));

    assertParseFailure(() -> Json.read(json, List.class), TOKENS_MAX);
  }

  @Test
  void given_oversizedString_when_parsingDocument_then_rejectsDocument() {
    String json = "[" + " ".repeat(DOCUMENT_BYTES_MAX + READ_BUFFER_CHARACTERS) + "]";

    assertParseFailure(() -> Json.read(json, List.class), DOCUMENT_BYTES_MAX);
  }

  @Test
  void given_oversizedBytes_when_parsingDocument_then_rejectsDocument() {
    var exception =
        assertThrows(
            IllegalArgumentException.class,
            () -> Json.read(new byte[10 * 1024 * 1024], EventEnvelope.class));
    assertEquals("JSON document exceeds 10485759 bytes", exception.getMessage());
  }

  private static void assertParseFailure(Executable parse, int limit) {
    var exception = assertThrows(IllegalStateException.class, parse);
    assertEquals("cannot parse JSON", exception.getMessage());
    String cause = String.valueOf(exception.getCause());
    assertTrue(cause.contains("exceeds the maximum allowed (" + limit + ","), cause);
  }

  private static String nestedArrays(int depth) {
    return "[".repeat(depth) + "]".repeat(depth);
  }

  private static String numberArray(int count) {
    var json = new StringBuilder("[");
    for (int index = 0; index < count; index++) {
      if (index > 0) json.append(',');
      json.append('0');
    }
    return json.append(']').toString();
  }

  private static int depth(Object value) {
    int depth = 0;
    while (value instanceof List<?> list) {
      depth++;
      value = list.isEmpty() ? null : list.getFirst();
    }
    return depth;
  }

  private static byte[] bytes(String json) {
    return json.getBytes(StandardCharsets.UTF_8);
  }
}
