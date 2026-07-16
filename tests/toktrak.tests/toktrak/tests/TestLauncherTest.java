package toktrak.tests;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.platform.engine.TestTag;

final class TestLauncherTest {
  @Test
  void given_testNameForms_when_validatingConvention_then_acceptsOnlyCanonicalForm() {
    assertTrue(
        TestLauncher.validTestName("given_existingWorld_when_behaviorRuns_then_stateChanges"));
    assertFalse(TestLauncher.validTestName("existingWorld_when_behaviorRuns_then_stateChanges"));
    assertFalse(
        TestLauncher.validTestName("given_ExistingWorld_when_behaviorRuns_then_stateChanges"));
    assertFalse(
        TestLauncher.validTestName("given_existing_world_when_behaviorRuns_then_stateChanges"));
  }

  @Test
  void
      given_maximumAndExcessDescriptorCounts_when_checkingLimit_then_acceptsMaximumAndRejectsExcess() {
    assertDoesNotThrow(() -> TestLauncher.requireDescriptorCount(20_000));
    assertThrows(IllegalStateException.class, () -> TestLauncher.requireDescriptorCount(20_001));
  }

  @Test
  void given_unitGroup_when_checkingTestTags_then_includesOnlyUntaggedTests() {
    assertTrue(TestLauncher.TestGroup.UNIT.includes(Set.of()));
    assertFalse(TestLauncher.TestGroup.UNIT.includes(Set.of(TestTag.create("integration"))));
  }

  @Test
  void given_taggedGroup_when_checkingTestTags_then_includesOnlyTaggedTests() {
    assertFalse(TestLauncher.TestGroup.TAGGED.includes(Set.of()));
    assertTrue(TestLauncher.TestGroup.TAGGED.includes(Set.of(TestTag.create("integration"))));
  }

  @Test
  void given_zeroExceptionalCounts_when_formattingSummary_then_omitsCounts() {
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
  void given_nonzeroExceptionalCounts_when_formattingSummary_then_includesCounts() {
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
