package toktrak.projection;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Objects;
import toktrak.store.EventEnvelope;

public final class Projection {
  public static final int VERSION = 1;
  private int eventCount;

  private Projection(int eventCount) {
    assert eventCount >= 0;
    this.eventCount = eventCount;
  }

  public static Projection empty() {
    var projection = new Projection(0);
    assert projection.eventCount == 0;
    return projection;
  }

  public synchronized int eventCount() {
    assert eventCount >= 0;
    return eventCount;
  }

  public synchronized Transition prepare(EventEnvelope event) {
    Objects.requireNonNull(event, "event");
    int eventCountAfter;
    if (event.type().equals("projection-snapshot")) {
      eventCountAfter = compatible(event) ? snapshotCount(event) : eventCount;
    } else {
      eventCountAfter = Math.addExact(eventCount, 1);
    }
    var transition = new Transition(eventCount, eventCountAfter);
    assert transition.eventCountBefore == eventCount;
    return transition;
  }

  public synchronized void commit(Transition transition) {
    Objects.requireNonNull(transition, "transition");
    if (eventCount != transition.eventCountBefore) {
      throw new IllegalStateException("projection changed before prepared transition commit");
    }
    eventCount = transition.eventCountAfter;
    assert eventCount >= 0;
  }

  public synchronized void apply(EventEnvelope event) {
    commit(prepare(event));
  }

  public synchronized Map<String, Object> snapshotData() {
    assert eventCount >= 0;
    var data = Map.<String, Object>of("projectionVersion", VERSION, "eventCount", eventCount);
    assert data.size() == 2;
    return data;
  }

  private static boolean compatible(EventEnvelope event) {
    assert event != null;
    try {
      return exactInt(event.data().get("projectionVersion")) == VERSION;
    } catch (IllegalStateException exception) {
      return false;
    }
  }

  private static int snapshotCount(EventEnvelope event) {
    assert event != null;
    int count = exactInt(event.data().get("eventCount"));
    if (count < 0) throw new IllegalStateException("projection snapshot is corrupt");
    return count;
  }

  private static int exactInt(Object value) {
    if (!(value instanceof Number number)) {
      throw new IllegalStateException("projection snapshot is corrupt");
    }
    try {
      return new BigDecimal(number.toString()).intValueExact();
    } catch (ArithmeticException | NumberFormatException exception) {
      throw new IllegalStateException("projection snapshot is corrupt", exception);
    }
  }

  public record Transition(int eventCountBefore, int eventCountAfter) {
    public Transition {
      if (eventCountBefore < 0 || eventCountAfter < 0) {
        throw new IllegalArgumentException("projection transition counts must not be negative");
      }
    }
  }
}
