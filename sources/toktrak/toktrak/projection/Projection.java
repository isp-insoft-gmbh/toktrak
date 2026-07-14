package toktrak.projection;

import java.util.List;
import java.util.Map;
import toktrak.store.EventEnvelope;

public final class Projection {
  public static final int VERSION = 1;
  private int eventCount;

  private Projection(int eventCount) {
    this.eventCount = eventCount;
  }

  public static Projection empty() {
    return new Projection(0);
  }

  public static Projection rebuild(List<EventEnvelope> events) {
    int snapshotIndex = -1;
    int count = 0;
    for (int i = 0; i < events.size(); i++) {
      var event = events.get(i);
      if (event.type().equals("projection-snapshot") && compatible(event)) {
        count = snapshotCount(event);
        snapshotIndex = i;
      }
    }
    var projection = new Projection(count);
    int start = snapshotIndex < 0 ? 0 : snapshotIndex + 1;
    for (int i = start; i < events.size(); i++) {
      var event = events.get(i);
      if (event.type().equals("projection-snapshot") && !compatible(event)) continue;
      projection.apply(event);
    }
    return projection;
  }

  public int eventCount() {
    return eventCount;
  }

  public synchronized void apply(EventEnvelope event) {
    if (event.type().equals("projection-snapshot")) {
      eventCount = snapshotCount(event);
    } else {
      eventCount++;
    }
  }

  public synchronized Map<String, Object> snapshotData() {
    return Map.of("projectionVersion", VERSION, "eventCount", eventCount);
  }

  private static boolean compatible(EventEnvelope event) {
    Object version = event.data().get("projectionVersion");
    return version instanceof Number number && number.intValue() == VERSION;
  }

  private static int snapshotCount(EventEnvelope event) {
    Object value = event.data().get("eventCount");
    if (!(value instanceof Number number) || number.intValue() < 0) {
      throw new IllegalStateException("projection snapshot is corrupt");
    }
    return number.intValue();
  }
}
