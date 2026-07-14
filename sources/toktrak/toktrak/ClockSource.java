package toktrak;

import java.time.Instant;
import java.util.Objects;

public interface ClockSource {
  Instant instant();

  static ClockSource system() {
    return Instant::now;
  }

  static ClockSource fixed(Instant instant) {
    Objects.requireNonNull(instant);
    return () -> instant;
  }
}
