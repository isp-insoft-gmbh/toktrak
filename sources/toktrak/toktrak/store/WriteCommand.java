package toktrak.store;

import java.time.Instant;
import java.util.Map;
import toktrak.projection.Projection;

public final class WriteCommand {
  private enum Kind {
    DEV_TEST,
    SNAPSHOT
  }

  private final Kind kind;
  private final String actor;

  private WriteCommand(Kind kind, String actor) {
    assert kind != null;
    if (actor == null || actor.isBlank()) throw new IllegalArgumentException("actor is required");
    this.kind = kind;
    this.actor = actor;
  }

  public static WriteCommand devTest(String actor) {
    var command = new WriteCommand(Kind.DEV_TEST, actor);
    assert command.kind == Kind.DEV_TEST;
    return command;
  }

  public static WriteCommand snapshot(String actor) {
    var command = new WriteCommand(Kind.SNAPSHOT, actor);
    assert command.kind == Kind.SNAPSHOT;
    return command;
  }

  EventEnvelope event(Instant at, Projection projection) {
    assert at != null;
    assert projection != null;
    EventEnvelope event =
        switch (kind) {
          case DEV_TEST -> EventEnvelope.create("dev-test", at, actor, Map.of());
          case SNAPSHOT ->
              EventEnvelope.create("projection-snapshot", at, actor, projection.snapshotData());
        };
    assert event != null;
    return event;
  }
}
