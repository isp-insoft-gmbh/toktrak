package toktrak.tests;

import static org.junit.jupiter.api.Assertions.*;
import static toktrak.tests.UsageFixture.daily;
import static toktrak.tests.UsageFixture.session;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/// `PageFixture` renders pages without a server; these checks keep it equal to what the server
/// serves, so fast page tests stay trustworthy.
final class PageFixtureTest {
  private static final Path CORPUS = Path.of("tests/corpus/dev.jsonl");
  private static final List<String> QUERIES =
      java.util.Arrays.asList(
          null, "range=all", "range=year&year=2026", "range=custom&from=2026-06-20&to=2026-07-14");

  @TempDir Path directory;

  @Test
  void given_developmentCorpus_when_renderingWithoutServer_then_matchesServedPages()
      throws Exception {
    var direct = PageFixture.corpus(directory.resolve("direct"), CORPUS, UsageFixture.CLOCK);
    try (var served =
        new UsageFixture(
            directory.resolve("served"),
            List.of("--corpus", MemoryTempDirFactory.devCorpusIn(directory).toString()))) {
      for (String query : QUERIES) {
        assertSamePage(
            served.visualizations(query), direct.visualizations(query), String.valueOf(query));
      }
      assertSamePage(served.overview(), direct.overview(), "overview");
    }
  }

  @Test
  void given_uploadedUsage_when_renderingWithoutServer_then_matchesServedPages() throws Exception {
    List<String> rows =
        List.of(
            daily("2026-07-01", "12.5", "{\"agent\":\"pi\",\"totalCost\":12.5}", ""),
            daily("2026-07-03", "0.333333333333333", "", "\"claude\""));
    List<String> sessions = List.of(session("s-1", "4.25", "2026-07-03T09:30:00Z"));
    var direct = PageFixture.viewer(directory.resolve("direct"));
    direct.upload(rows, sessions);
    try (var served = new UsageFixture(directory.resolve("served"))) {
      served.upload(rows, sessions);
      for (String query : QUERIES) {
        String page = direct.visualizations(query);
        assertSamePage(served.visualizations(query), page, String.valueOf(query));
      }
      assertTrue(direct.visualizations("range=all").contains("data-usd=\"12.833333333333333\""));
    }
  }

  /// The served log also holds login and token events, so only its revision counter may differ.
  private static void assertSamePage(String served, String direct, String label) {
    String expected = served.replaceAll("[Rr]evision[^0-9]{0,40}[0-9]+", "revision");
    String actual = direct.replaceAll("[Rr]evision[^0-9]{0,40}[0-9]+", "revision");
    if (expected.equals(actual)) return;
    int index = 0;
    while (index < Math.min(expected.length(), actual.length())
        && expected.charAt(index) == actual.charAt(index)) {
      index++;
    }
    int from = Math.max(0, index - 200);
    fail(
        label
            + " differs at "
            + index
            + "\nserved: "
            + expected.substring(from, Math.min(expected.length(), index + 200))
            + "\ndirect: "
            + actual.substring(from, Math.min(actual.length(), index + 200)));
  }
}
