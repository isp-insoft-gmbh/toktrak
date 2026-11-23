package toktrak.tests;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/// Pins the dashboard presentation helpers: avatar initials, half-up money rounding, grouped token
/// counts, and the label length limits.
final class DashboardPresentationPageTest {
  private static final String CLOCK = "2026-07-15T00:00:00Z";
  private static final String MODEL_AT_LIMIT = "m".repeat(256);
  private static final String MODEL_BEYOND_LIMIT = "n".repeat(257);
  private static final String SESSION_MODEL_AT_LIMIT = "x".repeat(256);
  private static final String SESSION_MODEL_BEYOND_LIMIT = "y".repeat(257);

  @TempDir Path directory;

  @Test
  void
      given_displayNamesOfVariedShapes_when_openingVisualizations_then_rendersFirstAndLastInitials()
          throws Exception {
    Path corpus = directory.resolve("initials.jsonl");
    Files.write(
        corpus,
        Files.readAllLines(Path.of("tests/corpus/dev.jsonl")).stream()
            .map(line -> line.replace("\"Synthetic User 2\"", "\"  Ada   Lovelace \""))
            .map(line -> line.replace("\"Synthetic User 3\"", "\"Grace\""))
            .map(line -> line.replace("\"Synthetic User 4\"", "\"𝔄da von Neumann\""))
            .toList());
    var pages = PageFixture.corpus(directory.resolve("initials"), corpus, CLOCK);
    String streams = section(pages.visualizations(null), "streams-heading", "</ol>");

    assertTrue(streams.contains(avatar("S1", "Synthetic User 1")), streams);
    assertTrue(streams.contains(avatar("AL", "  Ada   Lovelace ")), streams);
    assertTrue(streams.contains(avatar("G", "Grace")), streams);
    assertTrue(streams.contains(avatar("𝔄N", "𝔄da von Neumann")), streams);
    assertTrue(streams.contains("class=\"avatar color-5\""), streams);
  }

  @Test
  void given_uploadAtLabelAndRoundingBoundaries_when_openingDashboards_then_formatsEveryValue()
      throws Exception {
    var pages = PageFixture.viewer(directory.resolve("boundaries"));
    pages.uploadBody(upload());

    String overview = pages.overview();
    assertTrue(overview.contains("id=\"kpi-cost\" data-counter=\"0.13\""), overview);
    assertTrue(overview.contains("$0.13"), overview);
    assertFalse(overview.contains("$0.12"), overview);
    assertTrue(overview.contains("id=\"kpi-tokens\" data-counter=\"1234567\""), overview);
    assertTrue(overview.contains("1,234,567"), overview);

    String visualizations = pages.visualizations(null);
    String models = section(visualizations, "<h3>Models</h3>", "</article>");
    assertTrue(models.contains("<strong>" + MODEL_AT_LIMIT + "</strong>"), models);
    assertTrue(models.contains("<strong>Unknown model</strong>"), models);
    assertFalse(models.contains(MODEL_BEYOND_LIMIT), models);
    assertTrue(models.contains("<small>1,234,567 tokens</small>"), models);

    String sessions = section(visualizations, "details-heading", "</table>");
    assertTrue(
        sessions.contains(
            "<td>codex</td><td>Not reported</td><td>gpt-test, "
                + SESSION_MODEL_AT_LIMIT
                + "</td><td>$0.20</td><td>7,654,321</td><td>2026-07-14T22:00:00Z</td>"),
        sessions);
    assertFalse(sessions.contains(SESSION_MODEL_BEYOND_LIMIT), sessions);

    String weekday = section(visualizations, "<strong>Tuesday</strong>", "</div>");
    assertTrue(weekday.contains("<span>$0.13</span><small>1,234,567 tokens</small>"), weekday);

    String blocks = section(visualizations, "blocks-heading", "</table>");
    assertTrue(
        blocks.contains(
            "<td>2026-07-14T07:00:00.000Z</td><td>gpt-test</td><td>$0.50</td><td>4,294,967,296</td>"
                + "<td>12</td>"),
        blocks);

    String spikes = section(visualizations, "Largest individual daily cost rows", "</table>");
    assertTrue(spikes.contains("<td>$0.13</td><td>1,234,567</td>"), spikes);
  }

  private static String upload() {
    String daily =
        "{\"period\":\"2026-07-14\",\"agent\":\"claude\",\"inputTokens\":1234560,\"outputTokens\":7,"
            + "\"cacheCreationTokens\":0,\"cacheReadTokens\":0,\"totalTokens\":1234567,"
            + "\"totalCost\":0.125,\"modelBreakdowns\":[{\"modelName\":\""
            + MODEL_AT_LIMIT
            + "\",\"cost\":0.125,\"inputTokens\":1234560,\"outputTokens\":7,"
            + "\"cacheCreationTokens\":0,\"cacheReadTokens\":0},{\"modelName\":\""
            + MODEL_BEYOND_LIMIT
            + "\",\"cost\":0,\"inputTokens\":0,\"outputTokens\":0,"
            + "\"cacheCreationTokens\":0,\"cacheReadTokens\":0}]}";
    String session =
        "{\"period\":\"codex-session\",\"agent\":\"codex\",\"modelsUsed\":[\""
            + SESSION_MODEL_BEYOND_LIMIT
            + "\",\""
            + SESSION_MODEL_AT_LIMIT
            + "\",\"gpt-test\"],\"totalCost\":0.2,\"totalTokens\":7654321,"
            + "\"metadata\":{\"lastActivity\":\"2026-07-14T22:00:00Z\"}}";
    String block =
        "{\"id\":\"block-1\",\"startTime\":\"2026-07-14T07:00:00.000Z\",\"models\":[\"gpt-test\"],"
            + "\"costUSD\":0.5,\"totalTokens\":4294967296,\"entries\":12}";
    return "{\"trackerVersion\":\"1\",\"ccusageVersion\":\"20.0.17\",\"clientTimeZone\":\"UTC\","
        + "\"full\":true,\"generatedAt\":\"2026-07-14T23:00:00Z\",\"reports\":{"
        + "\"daily\":{\"ok\":true,\"json\":{\"daily\":["
        + daily
        + "]}},\"session\":{\"ok\":true,\"json\":{\"session\":["
        + session
        + "]}},\"blocks\":{\"ok\":true,\"json\":{\"blocks\":["
        + block
        + "]}}}}";
  }

  private static String avatar(String initials, String name) {
    return "aria-hidden=\"true\">"
        + initials
        + "</span><span class=\"person\"><strong>"
        + name
        + "</strong>";
  }

  private static String section(String html, String startMarker, String endMarker) {
    int start = html.indexOf(startMarker);
    assertTrue(start >= 0, html);
    int end = html.indexOf(endMarker, start);
    assertTrue(end > start, html);
    return html.substring(start, end);
  }
}
