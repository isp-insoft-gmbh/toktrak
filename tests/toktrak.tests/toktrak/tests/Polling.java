package toktrak.tests;

import java.time.Duration;
import java.util.function.BooleanSupplier;

/// Waits for state that offers no event, such as a thread's scheduling state. Event-driven waits
/// use latches, futures, or hooks instead; the per-test limit is the only deadline.
final class Polling {
  static final Duration INTERVAL = Duration.ofMillis(1);

  private Polling() {}

  /// Returns once `condition` holds; a condition that never holds fails at the test limit.
  static void until(BooleanSupplier condition) throws InterruptedException {
    while (!condition.getAsBoolean()) Thread.sleep(INTERVAL);
  }
}
