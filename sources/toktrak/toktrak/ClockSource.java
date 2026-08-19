package toktrak;

import java.time.Instant;
import java.util.Objects;

@FunctionalInterface
public interface ClockSource {
  Instant instant();

  static ClockSource system() {
    ClockSource clock = Instant::now;
    assert clock.instant() != null;
    return clock;
  }

  static ClockSource fixed(Instant instant) {
    Objects.requireNonNull(instant, "instant");
    ClockSource clock = () -> instant;
    assert clock.instant().equals(instant);
    return clock;
  }
}
