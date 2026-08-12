package toktrak.tests;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import toktrak.health.HealthState;

final class HealthStateTest {
  @Test
  void given_healthyState_when_requiringWritable_then_returnsNormally() {
    var health = new HealthState();

    assertDoesNotThrow(health::requireWritable);
    assertTrue(health.healthy());
    assertNull(health.reason());
  }

  @Test
  void given_degradedState_when_requiringWritable_then_reportsFirstReason() {
    var health = new HealthState();
    health.degrade("disk_failed");
    health.degrade("ignored_later_failure");

    var exception = assertThrows(IllegalStateException.class, health::requireWritable);
    assertEquals("disk_failed", exception.getMessage());
    assertFalse(health.healthy());
    assertEquals("disk_failed", health.reason());
  }

  @Test
  void given_invalidReasons_when_degradingHealth_then_rejectsReason() {
    for (String reason : new String[] {"", " ", "x".repeat(129)}) {
      assertThrows(IllegalArgumentException.class, () -> new HealthState().degrade(reason));
    }
    assertThrows(IllegalArgumentException.class, () -> new HealthState().degrade(null));
  }
}
