package toktrak.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

final class TestLauncherTest {
  @Test
  void omitsZeroExceptionalCounts() {
    assertEquals(
        String.join(
            System.lineSeparator(),
            "duration: 1153 ms",
            "junit containers found: 17",
            "tests found: 55",
            "tests passed: 55"),
        TestLauncher.formatSummary(1153, 17, 55, 55, 0, 0, 0));
  }

  @Test
  void includesNonzeroExceptionalCounts() {
    assertEquals(
        String.join(
            System.lineSeparator(),
            "duration: 1200 ms",
            "junit containers found: 18",
            "tests found: 60",
            "tests passed: 54",
            "tests skipped: 2",
            "tests aborted: 1",
            "tests failed: 3"),
        TestLauncher.formatSummary(1200, 18, 60, 54, 2, 1, 3));
  }
}
