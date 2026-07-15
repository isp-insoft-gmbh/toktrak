package toktrak.tests;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class AssertionsTest {
  @Test
  void assertionsAreEnabled() {
    assertTrue(AssertionsTest.class.desiredAssertionStatus());
  }
}
