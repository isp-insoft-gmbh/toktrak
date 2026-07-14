package toktrak.tests;

import java.io.PrintWriter;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;

public final class TestLauncher {
  private TestLauncher() {}

  public static void main(String[] args) {
    System.setProperty("toktrak.quiet", "true");
    LauncherDiscoveryRequest request = LauncherDiscoveryRequestBuilder.request()
        .selectors(DiscoverySelectors.selectPackage("toktrak.tests"))
        .build();
    var launcher = LauncherFactory.create();
    var listener = new SummaryGeneratingListener();
    launcher.registerTestExecutionListeners(listener);
    launcher.execute(request);
    var summary = listener.getSummary();
    summary.printTo(new PrintWriter(System.out, true));
    summary.printFailuresTo(new PrintWriter(System.err, true));
    if (summary.getTestsFoundCount() == 0 || summary.getFailures().size() > 0) System.exit(1);
  }
}
