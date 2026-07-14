package toktrak.health;

import java.util.concurrent.atomic.AtomicReference;

public final class HealthState {
  private final AtomicReference<String> reason = new AtomicReference<>();

  public HealthState() {}

  public boolean healthy() {
    return reason.get() == null;
  }

  public String reason() {
    return reason.get();
  }

  public void degrade(String reason) {
    if (reason == null || reason.isBlank()) throw new IllegalArgumentException("health reason is required");
    this.reason.compareAndSet(null, reason);
  }

  public void requireWritable() {
    if (!healthy()) throw new IllegalStateException(reason());
  }
}
