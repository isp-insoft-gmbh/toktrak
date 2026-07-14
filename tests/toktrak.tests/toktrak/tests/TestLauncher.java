package toktrak.tests;

import java.io.PrintWriter;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.TestPlan;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;

public final class TestLauncher {
  private static final long TEST_COUNT_MAX = 10_000;

  private TestLauncher() {}

  public static void main(String[] args) {
    if (args == null || args.length != 0)
      throw new IllegalArgumentException("no arguments expected");
    requireAssertions();
    System.setProperty("toktrak.quiet", "true");
    System.setProperty("junit.jupiter.execution.timeout.default", "10s");
    LauncherDiscoveryRequest request =
        LauncherDiscoveryRequestBuilder.request()
            .selectors(DiscoverySelectors.selectPackage("toktrak.tests"))
            .build();
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
    if (testsFound == 0 || testsFound > TEST_COUNT_MAX || !summary.getFailures().isEmpty()) {
      summary.printTo(new PrintWriter(System.out, true));
      summary.printFailuresTo(new PrintWriter(System.err, true));
      System.exit(1);
    }
    assert summary.getTestsSucceededCount() == testsFound;
    summary.printTo(new PrintWriter(System.out, true));
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
