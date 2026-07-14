package toktrak.health;

import java.util.concurrent.atomic.AtomicReference;

public final class HealthState {
  private static final int REASON_CHARACTERS_MAX = 128;
  private final AtomicReference<String> reason = new AtomicReference<>();

  public HealthState() {
    assert healthy();
  }

  public boolean healthy() {
    String currentReason = reason.get();
    assert currentReason == null || !currentReason.isBlank();
    return currentReason == null;
  }

  public String reason() {
    String currentReason = reason.get();
    assert currentReason == null || !currentReason.isBlank();
    return currentReason;
  }

  public void degrade(String reason) {
    if (reason == null || reason.isBlank())
      throw new IllegalArgumentException("health reason is required");
    if (reason.length() > REASON_CHARACTERS_MAX) {
      throw new IllegalArgumentException(
          "health reason exceeds " + REASON_CHARACTERS_MAX + " characters");
    }
    this.reason.compareAndSet(null, reason);
    assert !healthy();
  }

  public void requireWritable() {
    String currentReason = reason();
    if (currentReason != null) throw new IllegalStateException(currentReason);
    assert healthy();
  }
}
