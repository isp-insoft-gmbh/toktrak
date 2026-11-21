package toktrak.tests;

import static org.junit.jupiter.api.Assertions.*;
import static toktrak.tests.UsageFixture.daily;
import static toktrak.tests.UsageFixture.session;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/// Insight charts built from a small multi-person corpus with daily and session reports.
final class UsageInsightsHttpTest {
  private static final Pattern PERSON_ROW =
      Pattern.compile("<tr><th scope=\"row\">([^<]+)</th><td><svg class=\"spark\"(.*?)</svg>");
  private static final String WARNING = "<strong>Session data incomplete</strong>";

  @TempDir Path directory;

  @Test
  void given_twoPeopleWithVeryDifferentSpend_when_openingRange_then_trendsShareOneLogScale()
      throws Exception {
    Path corpus =
        corpus(
            upload(
                1,
                "viewer",
                "UTC",
                List.of(daily("2026-07-06", "100", "", ""), daily("2026-07-07", "50", "", "")),
                List.of()),
            upload(
                2,
                "synthetic-subject-2",
                "UTC",
                List.of(daily("2026-07-06", "1", "", ""), daily("2026-07-07", "0.5", "", "")),
                List.of()));
    try (var usage =
        new UsageFixture(directory.resolve("people"), List.of("--corpus", corpus.toString()))) {
      String body = usage.visualizations("range=custom&from=2026-07-06&to=2026-07-12");

      var sparks = new ArrayList<String>();
      var names = new ArrayList<String>();
      var matcher = PERSON_ROW.matcher(body);
      while (matcher.find()) {
        names.add(matcher.group(1));
        sparks.add(matcher.group(2));
      }
      assertEquals(List.of("Big Spender", "Small Spender"), names, body);
      // One-week range: seven daily bars of width 120 / 7 on one log scale from $0.10 to the
      // largest day, $100: height = 1 + 23 * log10(cost / 0.1) / 3.
      String big = sparks.get(0);
      String small = sparks.get(1);
      assertTrue(big.contains(bar("0", "0", "24")), big);
      assertTrue(big.contains(bar("17.1", "2.3", "21.7")), big);
      assertTrue(small.contains(bar("0", "15.3", "8.7")), small);
      assertTrue(small.contains(bar("17.1", "17.6", "6.4")), small);
      for (String spark : sparks) {
        assertEquals(2, spark.split("<rect ", -1).length - 1, spark);
        // Reference lines at $1, $10 and $100 are shared by every trend.
        assertTrue(
            spark.startsWith(" viewBox=\"0 0 120 24\" preserveAspectRatio=\"none\" role=\"img\""),
            spark);
        assertTrue(spark.contains(grid("15.3", "$1.00")), spark);
        assertTrue(spark.contains(grid("7.7", "$10.00")), spark);
        assertTrue(spark.contains(grid("0", "$100.00")), spark);
        assertEquals(3, spark.split("<line ", -1).length - 1, spark);
      }
      assertTrue(body.contains("<td data-usd=\"150\">$150.00</td><td>99.0%</td>"), body);
      assertTrue(body.contains("<td data-usd=\"1.5\">$1.50</td><td>1.0%</td>"), body);
    }
  }

  @Test
  void given_morePeopleThanTableRows_when_openingRange_then_groupsRestAndStillAddsUp()
      throws Exception {
    var lines = new ArrayList<String>();
    for (int person = 1; person <= 52; person++) {
      String subject = person == 1 ? "viewer" : "synthetic-subject-" + person;
      lines.add(user(person, subject, "Person " + person));
      lines.add(
          upload(
              person,
              subject,
              "UTC",
              List.of(daily("2026-07-06", Integer.toString(person), "", "")),
              List.of()));
    }
    Path corpus = write(lines);
    try (var usage =
        new UsageFixture(directory.resolve("many"), List.of("--corpus", corpus.toString()))) {
      String body = usage.visualizations("range=custom&from=2026-07-06&to=2026-07-12");

      var names = new ArrayList<String>();
      PERSON_ROW.matcher(body).results().forEach(match -> names.add(match.group(1)));
      assertEquals(51, names.size(), body);
      assertEquals("Person 52", names.getFirst(), body);
      assertEquals("Person 3", names.get(49), body);
      assertEquals("2 more people", names.getLast(), body);
      assertTrue(
          body.contains(
              "<td data-usd=\"3\">$3.00</td><td>0.2%</td><td>2 person-days</td><td>$1.50</td>"),
          body);
      assertTrue(body.contains("<dt>People</dt><dd data-usd=\"\">52</dd>"), body);
      assertTrue(body.contains("<dt>Median person</dt><dd data-usd=\"26.5\">$26.50</dd>"), body);
      assertTrue(body.contains("<dt>Total</dt><dd data-usd=\"1378\">$1,378.00</dd>"), body);
    }
  }

  @Test
  void given_usageOutsideRange_when_openingEmptyRange_then_rendersZeroStatesWithoutWarning()
      throws Exception {
    Path corpus =
        corpus(
            upload(
                1,
                "viewer",
                "UTC",
                List.of(daily("2026-07-06", "100", "", "")),
                List.of(session("s-outside", "40", "2026-07-06T10:00:00Z"))));
    try (var usage =
        new UsageFixture(directory.resolve("empty"), List.of("--corpus", corpus.toString()))) {
      String body = usage.visualizations("range=custom&from=2026-01-05&to=2026-01-11");

      assertFalse(body.contains("<tr><th scope=\"row\">"), body);
      assertTrue(body.contains("<dt>People</dt><dd data-usd=\"\">0</dd>"), body);
      assertTrue(body.contains("No daily usage in this range to compare sessions with."), body);
      assertFalse(body.contains(WARNING), body);
      assertTrue(body.contains("<dt>Sessions</dt><dd data-session-usd=\"\">0</dd>"), body);
      assertTrue(body.contains("<dt>Busiest hour</dt><dd data-usd=\"\">–</dd>"), body);
      // Empty size bins show a plain 0%, as before the chart statistics moved out.
      assertTrue(body.contains("<small>0%</small>"), body);
      assertFalse(body.contains("<small>0.0%</small>"), body);
    }
  }

  @Test
  void given_sessionTotalsAroundThreshold_when_openingEachDay_then_warnsBeyondTwoPercent()
      throws Exception {
    Path corpus =
        corpus(
            upload(
                1,
                "viewer",
                "UTC",
                List.of(
                    daily("2026-07-06", "100", "", ""),
                    daily("2026-07-07", "100", "", ""),
                    daily("2026-07-08", "100", "", ""),
                    daily("2026-07-09", "100", "", "")),
                List.of(
                    session("s-low-edge", "98", "2026-07-06T10:00:00Z"),
                    session("s-low-out", "97.9", "2026-07-07T10:00:00Z"),
                    session("s-high-edge", "102", "2026-07-08T10:00:00Z"),
                    session("s-high-out", "102.1", "2026-07-09T10:00:00Z"),
                    session("s-no-daily", "5", "2026-07-10T10:00:00Z"))));
    try (var usage =
        new UsageFixture(directory.resolve("coverage"), List.of("--corpus", corpus.toString()))) {
      assertCoverage(usage, "2026-07-06", "(98.0%)", false);
      assertCoverage(usage, "2026-07-07", "(97.9%)", true);
      assertCoverage(usage, "2026-07-08", "(102.0%)", false);
      assertCoverage(usage, "2026-07-09", "(102.1%)", true);
      assertCoverage(
          usage, "2026-07-10", "No daily usage in this range to compare sessions with.", true);
    }
  }

  @Test
  void given_sessionNearMidnightInBerlin_when_openingDay_then_countsItInLocalDayAndHour()
      throws Exception {
    Path corpus =
        corpus(
            upload(
                1,
                "viewer",
                "Europe/Berlin",
                List.of(daily("2026-07-07", "10", "", "")),
                List.of(session("s-late", "10", "2026-07-06T22:30:00Z"))));
    try (var usage =
        new UsageFixture(directory.resolve("zone"), List.of("--corpus", corpus.toString()))) {
      String utcDay = usage.visualizations("range=custom&from=2026-07-06&to=2026-07-06");
      assertTrue(utcDay.contains("<dt>Sessions</dt><dd data-session-usd=\"\">0</dd>"), utcDay);

      String localDay = usage.visualizations("range=custom&from=2026-07-07&to=2026-07-07");
      assertTrue(localDay.contains("<dt>Sessions</dt><dd data-session-usd=\"\">1</dd>"), localDay);
      assertTrue(
          localDay.contains("<dt>Busiest hour</dt><dd data-usd=\"\">Tue 00:00</dd>"), localDay);
      assertTrue(localDay.contains("(100.0%)"), localDay);
      assertFalse(localDay.contains(WARNING), localDay);
    }
  }

  @Test
  void given_twoPeopleAndSessions_when_openingWeek_then_drawsActivityCacheAndRhythmExactly()
      throws Exception {
    Path corpus =
        corpus(
            upload(
                1,
                "viewer",
                "UTC",
                List.of(
                    usageDay("2026-07-06", "10", 100, 300, ""),
                    usageDay("2026-07-08", "30", 100, 100, ""),
                    // A day without cost counts nowhere.
                    usageDay("2026-07-09", "0", 0, 0, "")),
                List.of(
                    session("s-1", "3", "2026-07-06T10:15:00Z"),
                    session("s-2", "1", "2026-07-06T10:45:00Z"),
                    session("s-3", "2", "2026-07-08T23:59:00Z"))),
            upload(
                2,
                "synthetic-subject-2",
                "UTC",
                List.of(usageDay("2026-07-06", "5", 0, 0, "")),
                List.of()));
    try (var usage =
        new UsageFixture(directory.resolve("geometry"), List.of("--corpus", corpus.toString()))) {
      String body = usage.visualizations("range=custom&from=2026-07-06&to=2026-07-12");
      // Plot 480 x 220 with padding 56/10/10/24: seven columns of 414 / 7, height 186.
      String cumulative = section(body, "cumulative-heading", "people-active-heading");
      assertTrue(
          cumulative.contains(
              "d=\"M56 196L115.1 140.2L174.3 140.2L233.4 28.6L292.6 28.6L351.7 28.6L410.9 28.6"
                  + "L470 28.6\""),
          cumulative);
      assertTrue(cumulative.contains("d=\"M56 196L470 28.6\""), cumulative);

      String active = section(body, "people-active-heading", "sizes-heading");
      assertTrue(active.contains("People active per day"), active);
      assertTrue(
          active.contains(
              "<rect class=\"series-2\" x=\"56.5\" y=\"10\" width=\"58.1\" height=\"186\">"),
          active);
      assertTrue(
          active.contains(
              "<rect class=\"series-2\" x=\"174.8\" y=\"103\" width=\"58.1\" height=\"93\">"),
          active);
      assertEquals(2, active.split("<rect ", -1).length - 1, active);
      assertTrue(
          active.contains(
              "<line class=\"chart-grid-line\" x1=\"56\" y1=\"103\" x2=\"470\" y2=\"103\"/>"),
          active);
      assertTrue(
          active.contains(
              "<text class=\"chart-axis\" x=\"50\" y=\"107\" text-anchor=\"end\">1</text>"),
          active);
      assertEquals(3, active.split("chart-grid-line", -1).length - 1, active);
      assertTrue(
          active.contains(
              "<text class=\"chart-axis\" x=\"50\" y=\"14\" text-anchor=\"end\">2</text>"),
          active);
      for (String label :
          List.of(
              "85.6\" y=\"214\" text-anchor=\"middle\">6 Jul",
              "203.9\" y=\"214\" text-anchor=\"middle\">8 Jul",
              "322.1\" y=\"214\" text-anchor=\"middle\">10 Jul",
              "440.4\" y=\"214\" text-anchor=\"middle\">12 Jul")) {
        assertTrue(active.contains("<text class=\"chart-axis\" x=\"" + label + "</text>"), active);
      }
      assertTrue(active.contains("<dt>Most in one day</dt><dd data-usd=\"\">2</dd>"), active);
      assertTrue(
          active.contains("<dt>Typical day with usage</dt><dd data-usd=\"\">2</dd>"), active);
      assertTrue(
          active.contains("<dt>Cost per active person-day</dt><dd data-usd=\"15\">$15.00</dd>"),
          active);

      assertEquals(4, active.split("y=\"214\" text-anchor=\"middle\"", -1).length - 1, active);
      // A $50 axis in four steps of 186 / 4.
      assertTrue(
          cumulative.contains(
              "<line class=\"chart-grid-line\" x1=\"56\" y1=\"149.5\" x2=\"470\" y2=\"149.5\"/>"),
          cumulative);
      assertEquals(5, cumulative.split("chart-grid-line", -1).length - 1, cumulative);

      // Person-days $5, $10 and $30: one per size range; the costliest third causes two thirds.
      String sizes = section(body, "sizes-heading", "concentration-heading");
      assertTrue(
          sizes.contains(
              "data-usd=\"5\"><span role=\"cell\">$3–10</span><span role=\"cell\">"
                  + meter("100", "33.3%")
                  + "</span><span role=\"cell\">"
                  + meter("16.7", "11.1%")),
          sizes);
      assertTrue(
          sizes.contains(
              "data-usd=\"0\"><span role=\"cell\">under $0.3</span><span role=\"cell\">"
                  + meter("0", "0.0%")),
          sizes);
      assertTrue(sizes.contains("<dt>Person-days</dt><dd data-usd=\"\">3</dd>"), sizes);
      String people = section(body, "per-person-heading", "model-mix-heading");
      assertTrue(
          people.contains("<td data-usd=\"40\">$40.00</td><td>88.9%</td><td>2</td><td>$20.00</td>"),
          people);
      String concentration = section(body, "concentration-heading", "per-person-heading");
      assertTrue(concentration.contains("d=\"M56 196L470 10\""), concentration);
      assertTrue(
          concentration.contains(
              "<dt>Top 1% of person-days</dt><dd data-usd=\"\">66.7% of cost</dd>"),
          concentration);
      assertTrue(
          concentration.contains(
              "<dt>Top 50% of person-days</dt><dd data-usd=\"\">88.9% of cost</dd>"),
          concentration);
      assertEquals(3, concentration.split("<dt>Top ", -1).length - 1, concentration);
      assertTrue(
          concentration.contains(
              "<text class=\"chart-axis\" x=\"159.5\" y=\"214\" text-anchor=\"middle\">25%</text>"),
          concentration);
      String mix = section(body, "model-mix-heading", "token-price-heading");
      assertTrue(mix.contains("data-usd=\"45\"><span>Week of 6 Jul</span>"), mix);

      // Cache reads over input-side tokens: 300 of 400, a gap, then 100 of 200.
      String cache = section(body, "cache-heading", "sessions-heading");
      assertTrue(cache.contains("d=\"M85.6 56.5M203.9 103\""), cache);
      assertTrue(cache.contains("<dt>Across the range</dt><dd data-usd=\"\">66.7%</dd>"), cache);

      // Heatmap 960 wide: 24 cells of 920 / 24 after a 40-unit label column, rows of 20.
      String rhythm = section(body, "rhythm-hours-heading", null);
      assertTrue(rhythm.contains("viewBox=\"0 0 960 160\""), rhythm);
      assertTrue(
          rhythm.contains(
              "x=\"423.3\" y=\"0\" width=\"36.8\" height=\"18.5\" fill-opacity=\"1\">"
                  + "<title>Mon 10:00 · $4.00 · 2 sessions</title>"),
          rhythm);
      assertTrue(
          rhythm.contains(
              "x=\"921.7\" y=\"40\" width=\"36.8\" height=\"18.5\" fill-opacity=\"0.74\">"
                  + "<title>Wed 23:00 · $2.00 · 1 sessions</title>"),
          rhythm);
      assertTrue(
          rhythm.contains(
              "x=\"40\" y=\"0\" width=\"36.8\" height=\"18.5\" fill-opacity=\"0.04\">"
                  + "<title>Mon 00:00 · $0.00 · 0 sessions</title>"),
          rhythm);
      assertEquals(168, rhythm.split("class=\"heat-cell\"", -1).length - 1, rhythm);
      assertTrue(rhythm.contains("<text class=\"chart-axis\" x=\"0\" y=\"14\">Mon</text>"), rhythm);
      assertTrue(
          rhythm.contains("<text class=\"chart-axis\" x=\"0\" y=\"134\">Sun</text>"), rhythm);
      assertTrue(
          rhythm.contains(
              "<text class=\"chart-axis\" x=\"59.2\" y=\"154\" text-anchor=\"middle\">00</text>"),
          rhythm);
      assertTrue(
          rhythm.contains(
              "<text class=\"chart-axis\" x=\"174.2\" y=\"154\" text-anchor=\"middle\">03</text>"),
          rhythm);
      assertEquals(8, rhythm.split("text-anchor=\"middle\"", -1).length - 1, rhythm);
      assertTrue(rhythm.contains("<dt>Busiest hour</dt><dd data-usd=\"\">Mon 10:00</dd>"), rhythm);
      assertTrue(rhythm.contains("<dt>Its cost</dt><dd data-usd=\"4\">$4.00</dd>"), rhythm);

      // Today (15 Jul) is three days into a later week: the even pace ends at 3 / 7 of the width.
      String partial = usage.visualizations("range=custom&from=2026-07-13&to=2026-07-19");
      assertTrue(
          section(partial, "cumulative-heading", "people-active-heading")
              .contains("d=\"M56 196L233.4 196\""),
          partial);
    }
  }

  @Test
  void given_messyModelBreakdowns_when_openingRange_then_keepsOnlyValidModelsAndPricesThem()
      throws Exception {
    String breakdowns =
        String.join(
            ",",
            model("m-a", "3", 1_000_000),
            model("m-a", "1", 1_000_000),
            model("   ", "5", 1),
            model("x".repeat(257), "5", 1),
            model("y".repeat(256), "1", 1_000_000),
            "7",
            model("m-zero", "0", 500_000),
            model("m-notokens", "2", 0),
            "{\"cost\":9}");
    var many = new ArrayList<String>();
    for (int index = 1; index <= 65; index++) {
      many.add(model(String.format("e%02d", index), "1", 0));
    }
    Path corpus =
        corpus(
            upload(
                1,
                "viewer",
                "UTC",
                List.of(
                    usageDay("2026-07-06", "7", 3_500_000, 0, breakdowns),
                    usageDay("2026-07-20", "65", 0, 0, String.join(",", many))),
                List.of()));
    try (var usage =
        new UsageFixture(directory.resolve("models"), List.of("--corpus", corpus.toString()))) {
      String body = usage.visualizations("range=custom&from=2026-07-06&to=2026-07-12");
      String prices = section(body, "token-price-heading", "cache-heading");
      // m-a: $4 over 2M tokens; the 256-character name: $1 over 1M; no tokens means no price.
      assertEquals(
          List.of("2", "1"),
          Pattern.compile("class=\"bar-list-row\" role=\"row\" data-usd=\"([^\"]+)\"")
              .matcher(prices)
              .results()
              .map(match -> match.group(1))
              .toList(),
          prices);
      assertTrue(prices.contains("<span role=\"cell\">m-a</span>"), prices);
      assertTrue(prices.contains("<dt>Team average</dt><dd data-usd=\"2\">$2.00</dd>"), prices);
      String mix = section(body, "model-mix-heading", "token-price-heading");
      assertEquals(List.of("4", "2", "1"), modelLegend(mix), mix);
      assertTrue(mix.contains("<dt>Models</dt><dd data-usd=\"\">3</dd>"), mix);

      // Only the first 64 breakdown entries count; the 65th dollar stays Not reported.
      String capped =
          section(
              usage.visualizations("range=custom&from=2026-07-20&to=2026-07-20"),
              "model-mix-heading",
              "token-price-heading");
      assertTrue(capped.contains("Not reported <strong>$1.00</strong>"), capped);
      assertTrue(capped.contains("Other models <strong>$60.00</strong>"), capped);
      assertTrue(capped.contains("<dt>Models</dt><dd data-usd=\"\">65</dd>"), capped);
    }
  }

  @Test
  void given_rangeLengthsAtMixLimits_when_openingRanges_then_switchesPeriodGranularity()
      throws Exception {
    Path corpus =
        corpus(upload(1, "viewer", "UTC", List.of(daily("2026-07-01", "1", "", "")), List.of()));
    try (var usage =
        new UsageFixture(directory.resolve("mix"), List.of("--corpus", corpus.toString()))) {
      assertTrue(
          usage.visualizations(custom("2026-04-01", "2026-07-01")).contains("Model mix per week"));
      assertTrue(
          usage.visualizations(custom("2026-03-31", "2026-07-01")).contains("Model mix per month"));
      assertTrue(
          usage.visualizations(custom("2024-07-01", "2026-07-01")).contains("Model mix per month"));
      assertTrue(
          usage.visualizations(custom("2024-06-30", "2026-07-01")).contains("Model mix per year"));
    }
  }

  private static String meter(String width, String value) {
    return "<svg class=\"bar\" viewBox=\"0 0 100 8\" preserveAspectRatio=\"none\""
        + " aria-hidden=\"true\" focusable=\"false\"><rect class=\"bar-track\" width=\"100\""
        + " height=\"8\"/><rect class=\"bar-fill\" width=\""
        + width
        + "\" height=\"8\"/></svg><small>"
        + value
        + "</small>";
  }

  private static String custom(String from, String to) {
    return "range=custom&from=" + from + "&to=" + to;
  }

  private static List<String> modelLegend(String html) {
    return Pattern.compile("<li data-model-usd=\"([^\"]+)\">")
        .matcher(html)
        .results()
        .map(match -> match.group(1))
        .toList();
  }

  private static String section(String html, String fromId, String toId) {
    int from = html.indexOf("id=\"" + fromId + "\"");
    int to = toId == null ? html.length() : html.indexOf("id=\"" + toId + "\"", from);
    assertTrue(from >= 0 && to > from, fromId + "\n" + html);
    return html.substring(from, to);
  }

  private static String usageDay(
      String date, String cost, long input, long cacheRead, String breakdowns) {
    return "{\"period\":\""
        + date
        + "\",\"agent\":\"all\",\"inputTokens\":"
        + input
        + ",\"outputTokens\":0,\"cacheCreationTokens\":0,\"cacheReadTokens\":"
        + cacheRead
        + ",\"totalTokens\":"
        + (input + cacheRead)
        + ",\"totalCost\":"
        + cost
        + (breakdowns.isEmpty() ? "" : ",\"modelBreakdowns\":[" + breakdowns + "]")
        + "}";
  }

  private static String model(String name, String cost, long inputTokens) {
    return "{\"modelName\":\""
        + name
        + "\",\"cost\":"
        + cost
        + ",\"inputTokens\":"
        + inputTokens
        + ",\"outputTokens\":0,\"cacheCreationTokens\":0,\"cacheReadTokens\":0}";
  }

  private static String bar(String x, String y, String height) {
    return "<rect class=\"series-1\" x=\""
        + x
        + "\" y=\""
        + y
        + "\" width=\"16.6\" height=\""
        + height
        + "\">";
  }

  private static String grid(String y, String label) {
    return "<line class=\"spark-grid\" x1=\"0\" y1=\""
        + y
        + "\" x2=\"120\" y2=\""
        + y
        + "\"><title>"
        + label
        + "</title></line>";
  }

  private static void assertCoverage(
      UsageFixture usage, String date, String coverage, boolean warning) throws Exception {
    String body = usage.visualizations("range=custom&from=" + date + "&to=" + date);
    assertTrue(body.contains(coverage), date + "\n" + body);
    assertEquals(warning, body.contains(WARNING), date + "\n" + body);
  }

  private Path corpus(String... uploads) throws Exception {
    var lines = new ArrayList<String>();
    lines.add(user(1, "viewer", "Big Spender"));
    lines.add(user(2, "synthetic-subject-2", "Small Spender"));
    lines.addAll(List.of(uploads));
    return write(lines);
  }

  private Path write(List<String> lines) throws Exception {
    Path path = Files.createTempFile(directory, "insights", ".jsonl");
    Files.write(path, lines);
    return path;
  }

  private static String user(int index, String subject, String name) {
    return "{\"id\":\""
        + id(index)
        + "\",\"at\":\"2026-07-01T00:00:00Z\",\"type\":\"identity-user-authenticated\","
        + "\"schemaVersion\":1,\"actor\":\""
        + subject
        + "\",\"data\":{\"issuer\":\"urn:toktrak:development\",\"subject\":\""
        + subject
        + "\",\"email\":\""
        + subject
        + "@example.invalid\",\"displayName\":\""
        + name
        + "\",\"color\":\"#a8dadc\"}}";
  }

  private static String upload(
      int index, String subject, String zone, List<String> dailyRows, List<String> sessionRows) {
    return "{\"id\":\""
        + id(100 + index)
        + "\",\"at\":\"2026-07-14T00:00:00Z\",\"type\":\"usage-uploaded\",\"schemaVersion\":1,"
        + "\"actor\":\""
        + subject
        + "\",\"data\":{\"issuer\":\"urn:toktrak:development\",\"subject\":\""
        + subject
        + "\",\"upload\":{\"trackerVersion\":\"1\",\"ccusageVersion\":\"20.0.17\","
        + "\"clientTimeZone\":\""
        + zone
        + "\",\"full\":true,\"generatedAt\":\"2026-07-14T00:00:00Z\",\"reports\":{"
        + "\"daily\":{\"ok\":true,\"json\":{\"daily\":["
        + String.join(",", dailyRows)
        + "]}},\"session\":{\"ok\":true,\"json\":{\"session\":["
        + String.join(",", sessionRows)
        + "]}}}}}}";
  }

  private static String id(int index) {
    return String.format("00000000-0000-4000-8000-%012d", index);
  }
}
