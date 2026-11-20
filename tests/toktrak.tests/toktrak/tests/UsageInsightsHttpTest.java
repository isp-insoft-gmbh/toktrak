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
  void given_twoPeopleWithVeryDifferentSpend_when_openingRange_then_eachTrendUsesOwnScale()
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
      // One-week range: seven daily bars of width 120 / 7, each person's maximum at full height.
      for (String spark : sparks) {
        assertTrue(
            spark.contains(
                "<rect class=\"series-1\" x=\"0\" y=\"0\" width=\"16.6\" height=\"24\">"),
            spark);
        assertTrue(
            spark.contains(
                "<rect class=\"series-1\" x=\"17.1\" y=\"12\" width=\"16.6\" height=\"12\">"),
            spark);
        assertEquals(2, spark.split("<rect ", -1).length - 1, spark);
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
          body.contains("<td data-usd=\"3\">$3.00</td><td>0.2%</td><td>2 person-days</td>"), body);
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
