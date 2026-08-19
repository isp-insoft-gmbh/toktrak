package toktrak.store;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record EventEnvelope(
    UUID id, Instant at, String type, int schemaVersion, String actor, Map<String, Object> data) {
  private static final int SCHEMA_VERSION = 1;
  private static final int TYPE_BYTES_MAX = 128;
  private static final int ACTOR_BYTES_MAX = 256;
  private static final int DATA_ENTRIES_MAX = 1_024;
  private static final int DATA_VALUES_MAX = 100_000;
  private static final int DATA_NESTING_DEPTH_MAX = 32;
  private static final int DATA_STRING_CHARACTERS_MAX = 1024 * 1024;
  private static final int DATA_NUMBER_CHARACTERS_MAX = 256;

  public EventEnvelope {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(at, "at");
    if (type == null || type.isBlank())
      throw new IllegalArgumentException("event type is required");
    requireUtf8Length(type, TYPE_BYTES_MAX, "event type");
    if (schemaVersion <= 0)
      throw new IllegalArgumentException("event schema version must be positive");
    if (schemaVersion != SCHEMA_VERSION)
      throw new IllegalArgumentException("unsupported event schema version: " + schemaVersion);
    if (actor != null) requireUtf8Length(actor, ACTOR_BYTES_MAX, "event actor");
    Objects.requireNonNull(data, "data");
    if (data.size() > DATA_ENTRIES_MAX) {
      throw new IllegalArgumentException("event data exceeds " + DATA_ENTRIES_MAX + " entries");
    }
    validateData(data);
    data = Map.copyOf(data);
    assert data.size() <= DATA_ENTRIES_MAX;
  }

  public static EventEnvelope create(
      String type, Instant at, String actor, Map<String, Object> data) {
    var event = new EventEnvelope(UUID.randomUUID(), at, type, SCHEMA_VERSION, actor, data);
    assert event.schemaVersion == SCHEMA_VERSION;
    return event;
  }

  private static void validateData(Map<String, Object> data) {
    assert data != null && data.size() <= DATA_ENTRIES_MAX;
    var pending = new ArrayDeque<Container>();
    var seen = new IdentityHashMap<Object, Boolean>();
    pending.addLast(new Container(data, 1));
    seen.put(data, Boolean.TRUE);
    int valueCount = 1;
    int containerCount = 0;
    while (!pending.isEmpty() && containerCount < DATA_VALUES_MAX) {
      Container container = pending.removeFirst();
      containerCount = Math.addExact(containerCount, 1);
      if (container.depth > DATA_NESTING_DEPTH_MAX) {
        throw new IllegalArgumentException(
            "event data exceeds " + DATA_NESTING_DEPTH_MAX + " nesting levels");
      }
      if (container.value instanceof Map<?, ?> map) {
        if (map.size() > DATA_VALUES_MAX - valueCount) {
          throw new IllegalArgumentException("event data exceeds " + DATA_VALUES_MAX + " values");
        }
        valueCount = Math.addExact(valueCount, map.size());
        for (Map.Entry<?, ?> entry : map.entrySet()) {
          if (!(entry.getKey() instanceof String key)) {
            throw new IllegalArgumentException("event data object keys must be strings");
          }
          requireDataString(key);
          inspectDataValue(entry.getValue(), container.depth + 1, pending, seen);
        }
      } else if (container.value instanceof Collection<?> collection) {
        if (collection.size() > DATA_VALUES_MAX - valueCount) {
          throw new IllegalArgumentException("event data exceeds " + DATA_VALUES_MAX + " values");
        }
        valueCount = Math.addExact(valueCount, collection.size());
        for (Object value : collection) {
          inspectDataValue(value, container.depth + 1, pending, seen);
        }
      } else {
        throw new AssertionError("non-container queued");
      }
    }
    if (!pending.isEmpty()) {
      throw new IllegalArgumentException("event data exceeds " + DATA_VALUES_MAX + " values");
    }
    assert valueCount <= DATA_VALUES_MAX;
  }

  private static void inspectDataValue(
      Object value, int depth, Deque<Container> pending, Map<Object, Boolean> seen) {
    assert depth > 0;
    assert pending != null;
    assert seen != null;
    if (value == null || value instanceof Boolean) return;
    if (value instanceof String string) {
      requireDataString(string);
      return;
    }
    if (value instanceof Number number) {
      if (number.toString().length() > DATA_NUMBER_CHARACTERS_MAX) {
        throw new IllegalArgumentException(
            "event data number exceeds " + DATA_NUMBER_CHARACTERS_MAX + " characters");
      }
      return;
    }
    if (value instanceof Map<?, ?> || value instanceof Collection<?>) {
      if (seen.put(value, Boolean.TRUE) != null) {
        throw new IllegalArgumentException("event data containers must not repeat");
      }
      pending.addLast(new Container(value, depth));
      return;
    }
    throw new IllegalArgumentException(
        "event data contains unsupported value: " + value.getClass().getName());
  }

  private static void requireDataString(String value) {
    assert value != null;
    if (value.length() > DATA_STRING_CHARACTERS_MAX) {
      throw new IllegalArgumentException(
          "event data string exceeds " + DATA_STRING_CHARACTERS_MAX + " characters");
    }
  }

  private static void requireUtf8Length(String value, int bytesMax, String field) {
    assert value != null;
    assert bytesMax > 0;
    assert field != null && !field.isBlank();
    if (value.length() > bytesMax || value.getBytes(StandardCharsets.UTF_8).length > bytesMax) {
      throw new IllegalArgumentException(field + " exceeds " + bytesMax + " UTF-8 bytes");
    }
  }

  private record Container(Object value, int depth) {
    private Container {
      assert value instanceof Map<?, ?> || value instanceof Collection<?>;
      assert depth > 0;
    }
  }
}
