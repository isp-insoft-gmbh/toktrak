package toktrak.store;

import java.time.Instant;
import java.util.Map;
import toktrak.projection.Projection;

public final class WriteCommand {
  private enum Kind { DEV_TEST, SNAPSHOT }
  private final Kind kind;
  private final String actor;

  private WriteCommand(Kind kind, String actor) {
    this.kind = kind;
    this.actor = actor;
  }

  public static WriteCommand devTest(String actor) {
    return new WriteCommand(Kind.DEV_TEST, actor);
  }

  public static WriteCommand snapshot(String actor) {
    return new WriteCommand(Kind.SNAPSHOT, actor);
  }

  EventEnvelope event(Instant at, Projection projection) {
    return switch (kind) {
      case DEV_TEST -> EventEnvelope.create("dev-test", at, actor, Map.of());
      case SNAPSHOT -> EventEnvelope.create("projection-snapshot", at, actor, projection.snapshotData());
    };
  }
}
