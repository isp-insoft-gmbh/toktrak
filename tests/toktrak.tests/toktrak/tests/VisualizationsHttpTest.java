package toktrak.tests;

import static org.junit.jupiter.api.Assertions.*;
import static toktrak.tests.UsageFixture.daily;

import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/// Range-scoped visualizations: tool attribution must reconcile with the range total.
final class VisualizationsHttpTest {
  private static final Pattern LEGEND =
      Pattern.compile(
          "<span class=\"swatch series-\\d\" aria-hidden=\"true\"></span>([^<]+)"
              + " <strong>([^<]+)</strong>");
  private static final Pattern KPI =
      Pattern.compile("<dt>([^<]+)</dt><dd data-usd=\"[^\"]*\">([^<]+)</dd>");
  private static final Pattern TICK = Pattern.compile("text-anchor=\"end\">([^<]+)</text>");

  @TempDir Path directory;

  @Test
  void
      given_toolCostsWithRoundingNoiseAndAnomalies_when_openingRange_then_attributesEachRowExactlyOnce()
          throws Exception {
    try (var usage = new UsageFixture(directory.resolve("attribution"))) {
      usage.upload(
          // Exact split.
          daily(
              "2026-07-01",
              "10",
              "{\"agent\":\"alpha\",\"totalCost\":4},{\"agent\":\"beta\",\"totalCost\":6}",
              ""),
          // Overshoot by 1e-16 is rounding noise and must not drop beta.
          daily(
              "2026-07-02",
              "0.3",
              "{\"agent\":\"alpha\",\"totalCost\":0.1000000000000001},"
                  + "{\"agent\":\"beta\",\"totalCost\":0.2}",
              ""),
          // Material overshoot: tool costs are ignored; the single reported tool takes the row.
          daily("2026-07-03", "5", "{\"agent\":\"alpha\",\"totalCost\":9}", "\"gamma\""),
          // Material undershoot: the remainder stays visible as Not reported.
          daily("2026-07-04", "7", "{\"agent\":\"alpha\",\"totalCost\":3}", ""),
          // No tool costs: the single reported tool takes the row.
          daily("2026-07-05", "2", "", "\"delta\""),
          // Undershoot by 1e-16 folds into the largest tool.
          daily("2026-07-06", "1", "{\"agent\":\"alpha\",\"totalCost\":0.9999999999999999}", ""),
          // Overshoot exactly at the slack is still rounding; just above it is an anomaly.
          daily("2026-07-07", "1", "{\"agent\":\"beta\",\"totalCost\":1.000001}", ""),
          daily("2026-07-08", "1", "{\"agent\":\"beta\",\"totalCost\":1.0000011}", ""),
          // Undershoot exactly at the slack folds; just above it stays visible.
          daily("2026-07-09", "1", "{\"agent\":\"beta\",\"totalCost\":0.999999}", ""),
          daily("2026-07-10", "1", "{\"agent\":\"beta\",\"totalCost\":0.9999989}", ""),
          // A zero-cost row is not a day with usage and has no tool.
          daily("2026-07-11", "0", "{\"agent\":\"epsilon\",\"totalCost\":0}", "\"epsilon\""),
          // Folding and clamping in isolation, so sub-cent residuals would surface as Not reported.
          daily("2026-06-20", "1", "{\"agent\":\"beta\",\"totalCost\":0.999999}", ""),
          daily("2026-06-21", "1", "{\"agent\":\"beta\",\"totalCost\":0.9999999999999999}", ""),
          daily(
              "2026-06-22",
              "1",
              "{\"agent\":\"beta\",\"totalCost\":1},{\"agent\":\"zeta\",\"totalCost\":0.0000005}",
              ""),
          // A tiny row without tools is Not reported, not folded into nothing.
          daily("2026-06-24", "0.0000005", "", ""),
          // Only the first 64 tool entries and names up to 64 characters count.
          daily("2026-06-26", "65", sixtyFiveAgents(), ""),
          daily(
              "2026-06-27",
              "2",
              "{\"agent\":\"" + "n".repeat(64) + "\",\"totalCost\":1}",
              "\"" + "m".repeat(65) + "\""),
          daily(
              "2026-06-28",
              "2",
              "{\"agent\":\"" + "o".repeat(65) + "\",\"totalCost\":1}",
              "\"" + "p".repeat(64) + "\""));

      String page = usage.visualizations("range=month&month=2026-07");

      assertEquals(
          List.of("beta=$9.20", "alpha=$8.10", "Not reported=$5.00", "gamma=$5.00", "delta=$2.00"),
          legend(page),
          page);
      Map<String, String> kpis = kpis(page);
      assertEquals("$29.30", kpis.get("Estimated cost"), page);
      assertEquals("$1.95", kpis.get("Per calendar day"), page);
      assertEquals("10 of 15", kpis.get("Days with usage"), page);
      assertEquals("$1.00", kpis.get("Typical person-day"), page);
      assertEquals("1", kpis.get("People with usage"), page);
      assertTrue(page.contains("<td>2 Jul 2026</td><td>$0.30</td>"), page);
      assertTrue(page.contains("<td>31 Jul 2026</td><td>$0.00</td>"), page);
      // 31 daily buckets label every fourth day.
      assertTrue(page.contains(">5 Jul</text>"), page);
      assertFalse(page.contains(">4 Jul</text>"), page);
      assertFalse(page.contains(">3 Jul</text>"), page);

      assertEquals(
          List.of("beta=$3.00"),
          legend(usage.visualizations("range=custom&from=2026-06-20&to=2026-06-22")));
      assertEquals(
          List.of("Not reported=$0.00"),
          legend(usage.visualizations("range=custom&from=2026-06-24&to=2026-06-24")));
      String capped = usage.visualizations("range=custom&from=2026-06-26&to=2026-06-26");
      assertTrue(capped.contains("Not reported <strong>$1.00</strong>"), capped);
      String names = usage.visualizations("range=custom&from=2026-06-27&to=2026-06-28");
      assertEquals(
          List.of("n".repeat(64) + "=$1.00", "p".repeat(64) + "=$2.00", "Not reported=$1.00")
              .stream()
              .sorted()
              .toList(),
          legend(names).stream().sorted().toList(),
          names);

      String idle = usage.visualizations("range=custom&from=2026-07-11&to=2026-07-12");
      Map<String, String> idleKpis = kpis(idle);
      assertEquals("0 of 2", idleKpis.get("Days with usage"), idle);
      assertEquals("0", idleKpis.get("People with usage"), idle);
      assertEquals("$0.00", idleKpis.get("Typical person-day"), idle);
      assertEquals(List.of(), legend(idle), idle);

      String withZero = usage.visualizations("range=custom&from=2026-07-10&to=2026-07-11");
      assertEquals("$1.00", kpis(withZero).get("Typical person-day"), withZero);
    }
  }

  @Test
  void given_twoToolsOverEightDays_when_openingRange_then_drawsExactGeometry() throws Exception {
    try (var usage = new UsageFixture(directory.resolve("geometry"))) {
      usage.upload(
          daily(
              "2026-07-01",
              "12.5",
              "{\"agent\":\"alpha\",\"totalCost\":10},{\"agent\":\"beta\",\"totalCost\":2.5}",
              ""),
          daily("2026-07-03", "4", "{\"agent\":\"beta\",\"totalCost\":4}", ""),
          daily("2026-07-08", "1", "", ""));

      String page = usage.visualizations("range=custom&from=2026-07-01&to=2026-07-08");

      assertTrue(
          page.contains(
              "<rect class=\"series-1\" x=\"64.5\" y=\"94.8\" width=\"110\" height=\"113\""
                  + " data-usd=\"10\"><title>1 Jul 2026 · alpha · $10.00</title></rect>"),
          page);
      assertTrue(
          page.contains(
              "<rect class=\"series-2\" x=\"286.5\" y=\"190.8\" width=\"110\" height=\"45.2\""
                  + " data-usd=\"4\"><title>3 Jul 2026 · beta · $4.00</title></rect>"),
          page);
      assertEquals(List.of("$0.00", "$5.00", "$10", "$15", "$20"), ticks(page), page);
      assertTrue(page.contains("d=\"M785.5 209.4L896.5 227.9\""), page);
      assertTrue(
          page.contains(
              "<line class=\"chart-grid-line\" x1=\"64\" y1=\"179.5\" x2=\"952\" y2=\"179.5\"/>"),
          page);
      assertTrue(
          page.contains(
              "<text class=\"chart-axis\" x=\"58\" y=\"183.5\" text-anchor=\"end\">$5.00</text>"),
          page);
      assertTrue(
          page.contains(
              "<text class=\"chart-axis\" x=\"896.5\" y=\"254\" text-anchor=\"middle\">8"
                  + " Jul</text>"),
          page);
      assertTrue(
          page.contains("<dt>Peak day</dt><dd data-usd=\"12.5\">$12.50 · 1 Jul 2026</dd>"), page);
      assertTrue(
          page.contains("<dt>Typical day with usage</dt><dd data-usd=\"4\">$4.00</dd>"), page);

      String week = usage.visualizations("range=custom&from=2026-07-01&to=2026-07-07");
      assertFalse(week.contains("chart-average"), week);
      assertFalse(week.contains("Line: 7-day average"), week);
    }
  }

  @Test
  void given_tiedPeakAndThousandScale_when_openingRange_then_reportsFirstPeakAndCompactsTicks()
      throws Exception {
    try (var usage = new UsageFixture(directory.resolve("scale"))) {
      usage.upload(
          daily("2026-07-01", "900", "", ""),
          daily("2026-07-02", "900", "", ""),
          daily("2026-07-03", "1800", "", ""),
          daily("2026-07-05", "5", "", ""),
          daily("2026-07-06", "5", "", ""));

      String thousands = usage.visualizations("range=custom&from=2026-07-01&to=2026-07-03");
      assertEquals(List.of("$0.00", "$500", "$1k", "$1.5k", "$2k"), ticks(thousands), thousands);

      String tied = usage.visualizations("range=custom&from=2026-07-04&to=2026-07-06");
      assertTrue(
          tied.contains("<dt>Peak day</dt><dd data-usd=\"5\">$5.00 · 5 Jul 2026</dd>"), tied);
      assertEquals(List.of("$0.00", "$1.25", "$2.50", "$3.75", "$5.00"), ticks(tied), tied);
    }
  }

  @Test
  void given_rangeQueries_when_requestingVisualizations_then_scopesValidatesAndPreservesRange()
      throws Exception {
    try (var usage = new UsageFixture(directory.resolve("ranges"))) {
      usage.upload(
          daily("2026-06-30", "1", "{\"agent\":\"alpha\",\"totalCost\":1}", ""),
          daily("2026-07-01", "2", "{\"agent\":\"alpha\",\"totalCost\":2}", ""));

      String defaultRange = usage.visualizations(null);
      assertTrue(defaultRange.contains("<strong>July 2026</strong>"), defaultRange);
      assertEquals("$2.00", kpis(defaultRange).get("Estimated cost"));
      assertTrue(
          defaultRange.contains(
              "href=\"/visualizations?range&#x3D;month&amp;month&#x3D;2026-07\""
                  + " aria-current=\"page\">Month</a>"),
          defaultRange);

      String year = usage.visualizations("range=year&year=2026");
      assertEquals("$3.00", kpis(year).get("Estimated cost"));
      assertTrue(year.contains("Cost per week by tool"), year);
      assertTrue(year.contains("<td>29 Jun 2026 – 5 Jul 2026</td><td>$3.00</td>"), year);
      assertTrue(year.contains("<td>1 Jan 2026 – 4 Jan 2026</td><td>$0.00</td>"), year);
      assertEquals(1, year.split("aria-current=\"page\">").length - 2, year);
      assertTrue(year.contains("aria-current=\"page\">Year</a>"), year);

      String custom = usage.visualizations("range=custom&from=2026-06-30&to=2026-06-30");
      assertEquals("$1.00", kpis(custom).get("Estimated cost"));
      assertEquals("1 of 1", kpis(custom).get("Days with usage"));

      String all = usage.visualizations("range=all");
      assertEquals("$3.00", kpis(all).get("Estimated cost"));
      assertTrue(all.contains("30 Jun 2026 – 1 Jul 2026"), all);

      String future = usage.visualizations("range=month&month=2026-08");
      assertEquals("$0.00", kpis(future).get("Estimated cost"));
      assertEquals("0 of 0", kpis(future).get("Days with usage"));

      assertTrue(
          usage
              .visualizations("range=custom&from=2026-04-01&to=2026-07-01")
              .contains("Cost per day by tool"));
      assertTrue(
          usage
              .visualizations("range=custom&from=2026-03-31&to=2026-07-01")
              .contains("Cost per week by tool"));
      assertTrue(
          usage
              .visualizations("range=custom&from=2024-07-01&to=2026-07-01")
              .contains("Cost per week by tool"));
      assertTrue(
          usage
              .visualizations("range=custom&from=2024-06-30&to=2026-07-01")
              .contains("Cost per month by tool"));
      assertEquals(
          "3660 days · applies",
          rangeDays(usage.visualizations("range=custom&from=2016-07-12&to=2026-07-19")));
      assertEquals(200, usage.send("range=year&year=2000&datastar=%7B%7D").statusCode());
      assertEquals(200, usage.send("range=year&year=9999").statusCode());
      assertEquals(400, usage.send("range=year&year=10000").statusCode());

      for (String invalid :
          List.of(
              "range=week",
              "range=month",
              "range=month&month=2026-13",
              "range=custom&from=2026-07-02&to=2026-07-01",
              "range=custom&from=2016-01-01&to=2026-01-08",
              "range=all&month=2026-07",
              "month=2026-07",
              "range=year&year=1999",
              "range=month&month=2026-07&year=2026",
              "range=year&year=2026&month=2026-07",
              "range=custom&from=2026-07-01&to=2026-07-02&month=2026-07",
              "datastar=%7B%22x%22%3A1%7D",
              "currency=GBP")) {
        assertEquals(400, usage.send(invalid).statusCode(), invalid);
      }

      HttpResponse<String> switched = usage.send("range=year&year=2026&currency=EUR");
      assertEquals(303, switched.statusCode());
      assertEquals(
          "/visualizations?range=year&year=2026",
          switched.headers().firstValue("Location").orElseThrow());
      assertTrue(
          year.contains(
              "href=\"/visualizations?range&#x3D;year&amp;year&#x3D;2026&amp;currency&#x3D;EUR\""),
          year);
    }
  }

  @Test
  void given_manyToolsOverYears_when_openingLongRange_then_bucketsMonthlyAndFoldsTail()
      throws Exception {
    try (var usage = new UsageFixture(directory.resolve("long"))) {
      var agents = new StringBuilder();
      for (int tool = 1; tool <= 7; tool++) {
        if (tool > 1) agents.append(',');
        agents
            .append("{\"agent\":\"tool-")
            .append(tool)
            .append("\",\"totalCost\":")
            .append(tool * 100)
            .append('}');
      }
      usage.upload(
          daily("2020-03-15", "1", "", ""),
          daily("2026-07-01", "2800", agents.toString(), ""),
          daily(
              "2026-07-02",
              "21",
              "{\"agent\":\"six-a\",\"totalCost\":1},"
                  + "{\"agent\":\"six-b\",\"totalCost\":2},{\"agent\":\"six-c\",\"totalCost\":3},"
                  + "{\"agent\":\"six-d\",\"totalCost\":4},{\"agent\":\"six-e\",\"totalCost\":5},"
                  + "{\"agent\":\"six-f\",\"totalCost\":6}",
              ""));

      String page = usage.visualizations("range=custom&from=2020-01-01&to=2026-07-01");
      assertTrue(page.contains("Cost per month by tool"), page);
      assertTrue(page.contains("<td>Mar 2020</td><td>$1.00</td>"), page);
      assertTrue(page.contains("<td>Jul 2026</td><td>$2,800.00</td>"), page);
      assertEquals(
          List.of(
              "tool-7=$700.00",
              "tool-6=$600.00",
              "tool-5=$500.00",
              "tool-4=$400.00",
              "tool-3=$300.00",
              "Other tools=$301.00"),
          legend(page),
          page);
      assertTrue(page.contains(">$5k</text>"), page);
      assertTrue(page.contains("<title>Jul 2026 · Other tools · $300.00</title>"), page);
      assertTrue(page.contains("<title>Mar 2020 · Other tools · $1.00</title>"), page);
      assertFalse(page.contains("chart-average"), page);

      String sixTools = usage.visualizations("range=custom&from=2026-07-02&to=2026-07-02");
      assertEquals(
          List.of(
              "six-f=$6.00",
              "six-e=$5.00",
              "six-d=$4.00",
              "six-c=$3.00",
              "six-b=$2.00",
              "six-a=$1.00"),
          legend(sixTools),
          sixTools);
    }
  }

  @Test
  void
      given_rowsFarInThePastAndFuture_when_requestingDefaultAndAllTime_then_clampsInsteadOfFailing()
          throws Exception {
    try (var usage = new UsageFixture(directory.resolve("outliers"))) {
      usage.upload(
          daily("1990-01-01", "1", "", ""),
          daily("2026-07-01", "2", "", ""),
          daily("2999-01-01", "4", "", ""));

      String defaultRange = usage.visualizations(null);
      assertTrue(defaultRange.contains("<strong>July 2026</strong>"), defaultRange);

      String all = usage.visualizations("range=all");
      assertTrue(all.contains("<strong>24 Jun 2016 – 1 Jul 2026</strong>"), all);
      assertEquals("$2.00", kpis(all).get("Estimated cost"));
    }
  }

  @Test
  void
      given_modelCostsOfVeryDifferentSize_when_drawingMeters_then_scalesToLargestAndKeepsSmallVisible()
          throws Exception {
    try (var usage = new UsageFixture(directory.resolve("meters"))) {
      usage.upload(
          "{\"period\":\"2026-07-01\",\"agent\":\"all\",\"inputTokens\":1,\"outputTokens\":1,"
              + "\"cacheCreationTokens\":0,\"cacheReadTokens\":0,\"totalTokens\":2,\"totalCost\":4.001,"
              + "\"modelBreakdowns\":[{\"modelName\":\"large\",\"cost\":3},"
              + "{\"modelName\":\"third\",\"cost\":1},{\"modelName\":\"tiny\",\"cost\":0.001},"
              + "{\"modelName\":\"free\",\"cost\":0}]}");

      String page = usage.visualizations(null);
      assertEquals("100", meter(page, "large"));
      assertEquals("33.3", meter(page, "third"));
      assertEquals("1", meter(page, "tiny"));
      assertEquals("0", meter(page, "free"));
    }
    try (var usage = new UsageFixture(directory.resolve("zero-meters"))) {
      usage.upload(
          "{\"period\":\"2026-07-01\",\"agent\":\"all\",\"inputTokens\":0,\"outputTokens\":0,"
              + "\"cacheCreationTokens\":0,\"cacheReadTokens\":0,\"totalTokens\":0,\"totalCost\":0,"
              + "\"modelBreakdowns\":[{\"modelName\":\"free\",\"cost\":0}]}");
      assertEquals("0", meter(usage.visualizations(null), "free"));
    }
  }

  private static String meter(String html, String label) {
    var match =
        Pattern.compile(
                "<strong>"
                    + Pattern.quote(label)
                    + "</strong><svg class=\"bar\"[^>]*><rect class=\"bar-track\"[^>]*/>"
                    + "<rect class=\"bar-fill\" width=\"([^\"]+)\"")
            .matcher(html);
    assertTrue(match.find(), html);
    return match.group(1);
  }

  private static String sixtyFiveAgents() {
    var agents = new StringBuilder();
    for (int index = 1; index <= 65; index++) {
      if (index > 1) agents.append(',');
      agents.append("{\"agent\":\"tool-").append(index).append("\",\"totalCost\":1}");
    }
    return agents.toString();
  }

  private static List<String> legend(String html) {
    return LEGEND
        .matcher(html)
        .results()
        .map(match -> match.group(1) + "=" + match.group(2))
        .toList();
  }

  private static List<String> ticks(String html) {
    return TICK.matcher(html).results().map(match -> match.group(1)).toList();
  }

  private static String rangeDays(String html) {
    var match = Pattern.compile("<span>(\\d+ days · applies)").matcher(html);
    assertTrue(match.find(), html);
    return match.group(1);
  }

  private static Map<String, String> kpis(String html) {
    int start = html.indexOf("<dl class=\"chart-kpis\">");
    int end = html.indexOf("</dl>", start);
    assertTrue(start >= 0 && end > start, html);
    var result = new LinkedHashMap<String, String>();
    KPI.matcher(html.substring(start, end).replaceAll(">\\s+<", "><"))
        .results()
        .forEach(match -> result.put(match.group(1), match.group(2)));
    return result;
  }
}
