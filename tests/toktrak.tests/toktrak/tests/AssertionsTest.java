package toktrak.tests;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class AssertionsTest {
  @Test
  void assertionsAreEnabled() {
    boolean[] enabled = {false};
    assert enabled[0] = true;
    assertTrue(enabled[0]);
  }
}
