package toktrak.tests;

import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import org.junit.platform.engine.DiscoverySelector;
import org.junit.platform.engine.FilterResult;
import org.junit.platform.engine.TestTag;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.engine.support.descriptor.MethodSource;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.PostDiscoveryFilter;
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
  private static final long TEST_DESCRIPTOR_COUNT_MAX = 20_000;
  private static final Map<String, String> PARALLEL_CONFIGURATION =
      Map.of(
          "junit.jupiter.execution.parallel.enabled", "true",
          "junit.jupiter.execution.parallel.mode.default", "same_thread",
          "junit.jupiter.execution.parallel.mode.classes.default", "concurrent",
          "junit.jupiter.execution.parallel.config.executor-service", "WORKER_THREAD_POOL",
          "junit.jupiter.execution.parallel.config.strategy", "fixed",
          "junit.jupiter.execution.parallel.config.fixed.parallelism", "4");
  private static final Pattern TEST_NAME =
      Pattern.compile("given_[a-z][A-Za-z0-9]*_when_[a-z][A-Za-z0-9]*_then_[a-z][A-Za-z0-9]*");

  private TestLauncher() {}

  public static void main(String[] args) {
    if (args == null) throw new IllegalArgumentException("args are required");
    if (args.length > Math.addExact(SELECTOR_COUNT_MAX, 1)) {
      throw new IllegalArgumentException(
          "test selectors exceed " + SELECTOR_COUNT_MAX + " entries");
    }
    requireAssertions();
    if (args.length == 0) throw new IllegalArgumentException("test group required");
    TestGroup group = TestGroup.parse(args[0]);
    System.setProperty("toktrak.quiet", "true");
    if (group == TestGroup.UNIT) {
      System.setProperty("junit.jupiter.execution.timeout.default", "10s");
    }
    var selectors = new ArrayList<DiscoverySelector>();
    if (args.length == 1) {
      selectors.add(DiscoverySelectors.selectPackage("toktrak.tests"));
    } else {
      for (int index = 1; index < args.length; index++) {
        String className = args[index];
        if (className == null
            || className.length() > SELECTOR_CHARACTERS_MAX
            || !className.startsWith("toktrak.tests.")) {
          throw new IllegalArgumentException("invalid test class: " + className);
        }
        selectors.add(DiscoverySelectors.selectClass(className));
      }
    }
    var launcher = LauncherFactory.create();
    TestPlan allTests = launcher.discover(request(selectors));
    requireDescriptorCount(allTests.countTestIdentifiers(_ -> true));
    long testsDiscovered = allTests.countTestIdentifiers(TestIdentifier::isTest);
    if (testsDiscovered == 0 || testsDiscovered > TEST_COUNT_MAX) {
      throw new IllegalStateException(
          "discovered tests must be 1.." + TEST_COUNT_MAX + ": " + testsDiscovered);
    }
    requireTestNames(allTests);
    TestPlan testPlan = launcher.discover(request(selectors, group.filter()));
    requireDescriptorCount(testPlan.countTestIdentifiers(_ -> true));
    requireTestNames(testPlan);
    long groupTestsDiscovered = testPlan.countTestIdentifiers(TestIdentifier::isTest);
    if (groupTestsDiscovered > TEST_COUNT_MAX) {
      throw new IllegalStateException(
          "discovered group tests exceed " + TEST_COUNT_MAX + ": " + groupTestsDiscovered);
    }
    if (groupTestsDiscovered == 0) return;
    var summaryListener = new SummaryGeneratingListener();
    var boundedListener = new BoundedExecutionListener();
    launcher.registerTestExecutionListeners(summaryListener, boundedListener);
    launcher.execute(testPlan);
    var summary = summaryListener.getSummary();
    long testsFound = summary.getTestsFoundCount();
    boolean failed =
        testsFound == 0 || testsFound > TEST_COUNT_MAX || !summary.getFailures().isEmpty();
    if (!summary.getFailures().isEmpty())
      summary.printFailuresTo(new PrintWriter(System.err, true, StandardCharsets.UTF_8));
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

  private static LauncherDiscoveryRequest request(
      List<DiscoverySelector> selectors, PostDiscoveryFilter... filters) {
    LauncherDiscoveryRequest request =
        LauncherDiscoveryRequestBuilder.request()
            .selectors(selectors)
            .filters(filters)
            .configurationParameters(PARALLEL_CONFIGURATION)
            .build();
    assert request != null;
    return request;
  }

  enum TestGroup {
    UNIT,
    TAGGED;

    boolean includes(Set<TestTag> tags) {
      assert tags != null;
      return this == UNIT ? tags.isEmpty() : !tags.isEmpty();
    }

    private PostDiscoveryFilter filter() {
      return descriptor ->
          FilterResult.includedIf(!descriptor.isTest() || includes(descriptor.getTags()));
    }

    private static TestGroup parse(String argument) {
      return switch (argument) {
        case "--unit" -> UNIT;
        case "--tagged" -> TAGGED;
        default -> throw new IllegalArgumentException("invalid test group: " + argument);
      };
    }
  }

  static void requireDescriptorCount(long descriptorCount) {
    if (descriptorCount < 0 || descriptorCount > TEST_DESCRIPTOR_COUNT_MAX) {
      throw new IllegalStateException(
          "test descriptors must be 0.." + TEST_DESCRIPTOR_COUNT_MAX + ": " + descriptorCount);
    }
  }

  private static void requireTestNames(TestPlan testPlan) {
    assert testPlan != null;
    for (var root : testPlan.getRoots()) {
      for (var test : testPlan.getDescendants(root)) {
        if (!test.isTest()) continue;
        var source =
            test.getSource()
                .orElseThrow(
                    () ->
                        new IllegalStateException("test has no source: " + test.getDisplayName()));
        if (!(source instanceof MethodSource methodSource)) {
          throw new IllegalStateException("test has no method source: " + test.getDisplayName());
        }
        if (!validTestName(methodSource.getMethodName())) {
          throw new IllegalStateException(
              "invalid test name: "
                  + methodSource.getClassName()
                  + "."
                  + methodSource.getMethodName());
        }
      }
    }
  }

  static boolean validTestName(String name) {
    assert name != null;
    return TEST_NAME.matcher(name).matches();
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
    if (!TestLauncher.class.desiredAssertionStatus()) {
      throw new IllegalStateException("Java assertions must be enabled with -ea");
    }
  }
}
