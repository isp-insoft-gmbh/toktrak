package toktrak.tests;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.StringJoiner;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.platform.engine.DiscoverySelector;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.TestPlan;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;

public final class TestLauncher {
  private static final int SELECTOR_COUNT_MAX = 1_000;
  private static final int SELECTOR_CHARACTERS_MAX = 1_024;
  private static final long TEST_COUNT_MAX = 10_000;

  private TestLauncher() {}

  public static void main(String[] args) {
    if (args == null) throw new IllegalArgumentException("args are required");
    if (args.length > SELECTOR_COUNT_MAX) {
      throw new IllegalArgumentException(
          "test selectors exceed " + SELECTOR_COUNT_MAX + " entries");
    }
    requireAssertions();
    System.setProperty("toktrak.quiet", "true");
    System.setProperty("junit.jupiter.execution.timeout.default", "10s");
    var selectors = new ArrayList<DiscoverySelector>();
    if (args.length == 0) {
      selectors.add(DiscoverySelectors.selectPackage("toktrak.tests"));
    } else {
      for (String className : args) {
        if (className == null
            || className.length() > SELECTOR_CHARACTERS_MAX
            || !className.startsWith("toktrak.tests.")) {
          throw new IllegalArgumentException("invalid test class: " + className);
        }
        selectors.add(DiscoverySelectors.selectClass(className));
      }
    }
    LauncherDiscoveryRequest request =
        LauncherDiscoveryRequestBuilder.request().selectors(selectors).build();
    assert request != null;
    var launcher = LauncherFactory.create();
    TestPlan testPlan = launcher.discover(request);
    long testsDiscovered = testPlan.countTestIdentifiers(TestIdentifier::isTest);
    if (testsDiscovered == 0 || testsDiscovered > TEST_COUNT_MAX) {
      throw new IllegalStateException(
          "discovered tests must be 1.." + TEST_COUNT_MAX + ": " + testsDiscovered);
    }
    var summaryListener = new SummaryGeneratingListener();
    var boundedListener = new BoundedExecutionListener();
    launcher.registerTestExecutionListeners(summaryListener, boundedListener);
    launcher.execute(testPlan);
    var summary = summaryListener.getSummary();
    long testsFound = summary.getTestsFoundCount();
    boolean failed =
        testsFound == 0 || testsFound > TEST_COUNT_MAX || !summary.getFailures().isEmpty();
    if (!summary.getFailures().isEmpty())
      summary.printFailuresTo(new PrintWriter(System.err, true));
    long durationMillis = Math.subtractExact(summary.getTimeFinished(), summary.getTimeStarted());
    System.out.println(
        formatSummary(
            durationMillis,
            summary.getContainersFoundCount(),
            testsFound,
            summary.getTestsSucceededCount(),
            summary.getTestsSkippedCount(),
            summary.getTestsAbortedCount(),
            summary.getTestsFailedCount()));
    if (failed) System.exit(1);
    assert summary.getTestsSucceededCount() == testsFound;
  }

  static String formatSummary(
      long durationMillis,
      long containersFound,
      long testsFound,
      long testsPassed,
      long testsSkipped,
      long testsAborted,
      long testsFailed) {
    assert durationMillis >= 0;
    assert containersFound >= 0;
    assert testsFound >= 0;
    assert testsPassed >= 0;
    assert testsSkipped >= 0;
    assert testsAborted >= 0;
    assert testsFailed >= 0;
    var lines = new StringJoiner(System.lineSeparator());
    lines.add("duration: " + durationMillis + " ms");
    lines.add("junit containers found: " + containersFound);
    lines.add("tests found: " + testsFound);
    lines.add("tests passed: " + testsPassed);
    if (testsSkipped != 0) lines.add("tests skipped: " + testsSkipped);
    if (testsAborted != 0) lines.add("tests aborted: " + testsAborted);
    if (testsFailed != 0) lines.add("tests failed: " + testsFailed);
    return lines.toString();
  }

  private static final class BoundedExecutionListener implements TestExecutionListener {
    private final AtomicLong testsStarted = new AtomicLong();

    @Override
    public void executionStarted(TestIdentifier testIdentifier) {
      if (!testIdentifier.isTest()) return;
      long count = testsStarted.incrementAndGet();
      if (count > TEST_COUNT_MAX) {
        throw new IllegalStateException("started tests exceed " + TEST_COUNT_MAX + " entries");
      }
    }
  }

  private static void requireAssertions() {
    boolean enabled = false;
    assert enabled = true;
    if (!enabled) throw new IllegalStateException("Java assertions must be enabled with -ea");
  }
}
