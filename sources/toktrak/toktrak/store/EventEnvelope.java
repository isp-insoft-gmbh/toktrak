package toktrak.store;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record EventEnvelope(
    UUID id,
    Instant at,
    String type,
    int schemaVersion,
    String actor,
    Map<String, Object> data) {
  public EventEnvelope {
    Objects.requireNonNull(id);
    Objects.requireNonNull(at);
    if (type == null || type.isBlank()) throw new IllegalArgumentException("event type is required");
    if (schemaVersion <= 0) throw new IllegalArgumentException("event schema version must be positive");
    data = Map.copyOf(Objects.requireNonNull(data));
  }

  public static EventEnvelope create(String type, Instant at, String actor, Map<String, Object> data) {
    return new EventEnvelope(UUID.randomUUID(), at, type, 1, actor, data);
  }
}
