package toktrak.tests;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import toktrak.health.HealthState;
import toktrak.http.DashboardCurrency;
import toktrak.http.DashboardFactory;
import toktrak.http.DashboardSnapshot;
import toktrak.http.DashboardSnapshot.DashboardMetricRow;

/// Builds dashboard snapshots straight from a projection. These checks need no rendering, so
/// mutation testing reaches them first for every dashboard mutant.
final class DashboardFactoryTest {
  private static final String MODEL_AT_LIMIT = "m".repeat(256);
  private static final String MODEL_BEYOND_LIMIT = "n".repeat(257);
  private static final String SESSION_MODEL_AT_LIMIT = "x".repeat(256);
  private static final String SESSION_MODEL_BEYOND_LIMIT = "y".repeat(257);

  @TempDir Path directory;

  @Test
  void given_uploadAtLabelAndRoundingBoundaries_when_building_then_formatsEveryValue()
      throws Exception {
    var pages = PageFixture.viewer(directory.resolve("boundaries"));
    pages.uploadBody(upload());
    DashboardSnapshot snapshot = snapshot(pages);

    assertEquals("$0.13", snapshot.cost());
    assertEquals("0.13", snapshot.costCounter());
    assertEquals("0.125", snapshot.costUsd());
    assertEquals("1,234,567", snapshot.tokens());
    assertEquals("1234567", snapshot.tokensCounter());
    assertEquals(1, snapshot.activeUsers());
    assertEquals("2026-07", snapshot.period());

    List<String> models = snapshot.models().stream().map(DashboardMetricRow::label).toList();
    assertTrue(models.contains(MODEL_AT_LIMIT), models.toString());
    assertTrue(models.contains("Unknown model"), models.toString());
    assertFalse(models.contains(MODEL_BEYOND_LIMIT), models.toString());
    var tuesday =
        snapshot.rhythm().stream().filter(row -> row.label().equals("Tuesday")).findFirst();
    assertEquals("$0.13", tuesday.orElseThrow().value());
    assertEquals("1,234,567 tokens", tuesday.orElseThrow().detail());

    var session = snapshot.sessions().getFirst();
    assertEquals(
        List.of(
            "codex",
            "Not reported",
            "gpt-test, " + SESSION_MODEL_AT_LIMIT,
            "$0.20",
            "7,654,321",
            "2026-07-14T22:00:00Z"),
        List.of(
            session.source(),
            session.project(),
            session.models(),
            session.cost(),
            session.tokens(),
            session.lastActivity()));
    assertFalse(session.models().contains(SESSION_MODEL_BEYOND_LIMIT), session.models());

    assertEquals(1, snapshot.blocks().size(), "gap blocks hold no usage and are not shown");
    var block = snapshot.blocks().getFirst();
    assertEquals(
        List.of("2026-07-14T07:00:00.000Z", "gpt-test", "$0.50", "4,294,967,296", "12"),
        List.of(block.start(), block.models(), block.cost(), block.tokens(), block.entries()));

    var spike = snapshot.spikes().getFirst();
    assertEquals(
        List.of("2026-07-14", "$0.13", "1,234,567"),
        List.of(spike.date(), spike.cost(), spike.tokens()));
  }

  @Test
  void given_developmentCorpus_when_building_then_reportsCanonicalTotals() throws Exception {
    var pages =
        PageFixture.corpus(
            directory.resolve("corpus"), Path.of("tests/corpus/dev.jsonl"), UsageFixture.CLOCK);
    DashboardSnapshot snapshot = snapshot(pages);

    assertEquals("$1,068.19", snapshot.cost());
    assertEquals("1068.19", snapshot.costCounter());
    assertEquals("560,418,883", snapshot.tokens());
    assertEquals("560418883", snapshot.tokensCounter());
    assertEquals("213.64", snapshot.averageCounter());
    assertEquals(DashboardSnapshot.ReportCompleteness.PARTIAL, snapshot.reportCompleteness());
    assertEquals(DashboardSnapshot.DashboardHealth.HEALTHY, snapshot.health());
    // Issue #132: half of the newest 20 corpus blocks are ccusage gaps; real windows fill the
    // table.
    assertEquals(20, snapshot.blocks().size());
    for (var row : snapshot.blocks()) {
      assertFalse(row.start().equals("2026-07-14T00:37:01.086Z"), row.toString());
      assertFalse(row.start().equals("2026-07-07T23:41:46.671Z"), row.toString());
    }
  }

  private static DashboardSnapshot snapshot(PageFixture pages) {
    return DashboardFactory.create(
        pages.projection(), new HealthState(), DashboardCurrency.USD, pages.now());
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
    // Newer than block-1, so it would be the first row if gaps were shown.
    String gap =
        "{\"id\":\"gap-1\",\"isGap\":true,\"isActive\":false,\"entries\":0,\"models\":[],"
            + "\"costUSD\":0,\"totalTokens\":0,\"startTime\":\"2026-07-14T12:00:00.000Z\"}";
    return "{\"trackerVersion\":\"1\",\"ccusageVersion\":\"20.0.17\",\"clientTimeZone\":\"UTC\","
        + "\"full\":true,\"generatedAt\":\"2026-07-14T23:00:00Z\",\"reports\":{"
        + "\"daily\":{\"ok\":true,\"json\":{\"daily\":["
        + daily
        + "]}},\"session\":{\"ok\":true,\"json\":{\"session\":["
        + session
        + "]}},\"blocks\":{\"ok\":true,\"json\":{\"blocks\":["
        + block
        + ","
        + gap
        + "]}}}}";
  }
}
