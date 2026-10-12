package toktrak.tests;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import toktrak.App;

final class DashboardHttpTest {
  @TempDir Path directory;

  @Test
  void given_corpusViewer_when_exploringDashboard_then_rendersCanonicalNarrativeAndCurrency()
      throws Exception {
    try (var app =
        App.start(
            new String[] {"--corpus", "tests/corpus/dev.jsonl", "--clock", "2026-07-15T00:00:00Z"},
            environment(false))) {
      URI base = URI.create("http://127.0.0.1:" + app.port());
      var client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
      assertEquals(302, send(client, base.resolve("/visualizations"), null).statusCode());
      HttpResponse<String> backgroundSignedOut =
          client.send(
              HttpRequest.newBuilder(base.resolve("/?datastar=%7B%7D"))
                  .header("Datastar-Request", "true")
                  .GET()
                  .build(),
              BodyHandlers.ofString());
      assertEquals(401, backgroundSignedOut.statusCode());
      String session = login(client, base);

      HttpResponse<String> overview = send(client, base.resolve("/"), session);
      assertEquals(200, overview.statusCode());
      assertEquals(
          "default-src 'self'; script-src 'self' 'unsafe-eval'; frame-ancestors 'none'; base-uri"
              + " 'none'",
          overview.headers().firstValue("Content-Security-Policy").orElseThrow());
      assertTrue(overview.body().contains("<h1>Overview</h1>"), overview.body());
      assertTrue(overview.body().contains("href=\"/\" aria-current=\"page\">Overview</a>"));
      assertTrue(overview.body().contains("href=\"/tokens\">My Tracker</a>"));
      assertTrue(overview.body().contains("$1,068.19"), overview.body());
      assertTrue(overview.body().contains("560,418,883"), overview.body());
      assertTrue(overview.body().contains("Some reports missing"), overview.body());
      assertFalse(overview.body().contains("Read-only mode"), overview.body());
      assertTrue(overview.body().contains("role=\"status\""), overview.body());
      assertTrue(
          overview
              .body()
              .contains("<details class=\"ingestion-details\"><summary>Details</summary>"),
          overview.body());
      assertTrue(overview.body().contains("Synthetic User 1"), overview.body());
      assertTrue(overview.body().contains("<div id=\"dashboard\""), overview.body());
      assertTrue(
          overview.body().contains("id=\"kpi-cost\" data-counter=\"1068.19\""), overview.body());
      assertTrue(
          overview.body().contains("id=\"kpi-tokens\" data-counter=\"560418883\""),
          overview.body());
      assertTrue(
          overview.body().contains("id=\"kpi-average\" data-counter=\"213.64\""), overview.body());
      assertTrue(
          overview.body().contains("<time class=\"period\" datetime=\"2026-07\">"),
          overview.body());
      assertTrue(overview.body().contains("</svg> July 2026</time>"), overview.body());
      assertTrue(
          overview.body().contains("class=\"status status-ok\" aria-label=\"Complete\""),
          overview.body());
      assertTrue(
          overview.body().contains("Tracker vdev-corpus</small></details>"), overview.body());
      assertTrue(
          overview.body().contains("class=\"status status-warn\" aria-label=\"Partial\""),
          overview.body());
      int ingestionStart = overview.body().indexOf("<ul class=\"ingestion-list\">");
      int ingestionEnd = overview.body().indexOf("</ul>", ingestionStart);
      String ingestion = overview.body().substring(ingestionStart, ingestionEnd);
      assertTrue(ingestion.contains("</svg> Partial</span>"), ingestion);
      assertFalse(ingestion.contains("</svg> Complete</span>"), ingestion);
      assertTrue(
          ingestion.indexOf("<strong>Synthetic User 5</strong>")
              < ingestion.indexOf("<strong>Synthetic User 1</strong>"),
          ingestion);
      assertTrue(overview.body().contains(">██████████</span>"), overview.body());
      assertTrue(overview.body().contains("datastar-patch-signals"), overview.body());
      assertTrue(
          overview.body().matches("(?s).*src=\"/assets/datastar\\.[0-9a-f]{32}\\.js\".*"),
          overview.body());
      assertTrue(
          overview.body().matches("(?s).*src=\"/assets/dashboard\\.[0-9a-f]{32}\\.js\".*"),
          overview.body());
      assertTrue(overview.body().contains("data-signals__ifmissing"), overview.body());
      assertTrue(overview.body().contains("_observedUsageRevision"), overview.body());
      assertTrue(overview.body().contains("_renderedUsageRevision"), overview.body());
      assertTrue(
          overview.body().contains("data-on:toktrak-dashboard-revision__document"),
          overview.body());
      assertTrue(overview.body().contains("data-on:datastar-fetch__document"), overview.body());
      assertTrue(overview.body().contains("@peek(() => document.dispatchEvent"), overview.body());
      assertTrue(
          overview.body().contains("@get(window.location.pathname, {retry: 'never'})"),
          overview.body());
      assertFalse(overview.body().contains("location.reload()"), overview.body());

      HttpResponse<String> visualizations = send(client, base.resolve("/visualizations"), session);
      assertEquals(200, visualizations.statusCode());
      assertTrue(
          visualizations
              .body()
              .contains("href=\"/visualizations\" aria-current=\"page\">Visualizations</a>"));
      assertTrue(visualizations.body().contains("href=\"/tokens\">My Tracker</a>"));
      for (String heading :
          new String[] {
            "Daily projected cost",
            "Where tokens go",
            "Usage rhythm by UTC weekday",
            "Model and harness mix",
            "Individual streams, shared context",
            "Costliest sessions",
            "Recent coding blocks"
          }) {
        assertTrue(visualizations.body().contains(heading), heading);
      }
      assertTrue(visualizations.body().contains("<svg class=\"chart-rule\""));
      assertTrue(
          visualizations.body().contains("<time datetime=\"2026-07-14\">14 Jul 2026</time>"));
      assertTrue(visualizations.body().contains("4,263,701,600"));
      assertTrue(visualizations.body().contains(">Monday<"));
      assertTrue(visualizations.body().contains("<strong>claude-fable-5</strong>"));
      assertTrue(visualizations.body().contains("<strong>opencode</strong>"));
      assertTrue(visualizations.body().contains("Synthetic User 4"));
      assertTrue(visualizations.body().contains("<td>$940.18</td>"));
      assertTrue(visualizations.body().contains("synthetic-project-4-045"));
      assertFalse(visualizations.body().contains("synthetic-project-4-045.jsonl"));
      assertTrue(visualizations.body().contains("<th>Source</th><th>Project</th>"));
      assertFalse(visualizations.body().contains("Unknown project"));
      assertTrue(visualizations.body().contains("2026-07-14T07:00:00.000Z"));

      HttpResponse<String> scope = send(client, base.resolve("/scope"), session);
      assertEquals(200, scope.statusCode());
      assertTrue(scope.body().contains("href=\"/scope\" aria-current=\"page\">Data scope</a>"));
      assertTrue(scope.body().contains("href=\"/tokens\">My Tracker</a>"));
      assertTrue(scope.body().contains("What TokTrak never sees"));
      assertTrue(scope.body().contains("Prompts, code, file contents"));

      HttpResponse<String> preference = send(client, base.resolve("/?currency=EUR"), session);
      assertEquals(303, preference.statusCode());
      assertEquals("/", preference.headers().firstValue("Location").orElseThrow());
      String currency =
          preference.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
      HttpResponse<String> eur = send(client, base.resolve("/"), session + "; " + currency);
      assertTrue(eur.body().contains("€982.74"), eur.body());
      assertTrue(eur.body().contains("id=\"kpi-cost\" data-counter=\"982.74\""), eur.body());
      assertTrue(eur.body().contains("id=\"kpi-average\" data-counter=\"196.55\""), eur.body());
      assertTrue(eur.body().contains("Showing EUR estimates"), eur.body());
      assertEquals(200, send(client, base.resolve("/?datastar=%7B%7D"), session).statusCode());
      assertEquals(
          400,
          send(client, base.resolve("/?datastar=%7B%22unexpected%22%3A1%7D"), session)
              .statusCode());
      assertEquals(400, send(client, base.resolve("/?currency=GBP"), session).statusCode());
    }
  }

  @Test
  void given_degradedCorpus_when_openingOverview_then_warnsWithoutHidingStoredUsage()
      throws Exception {
    try (var app =
        App.start(
            new String[] {
              "--corpus",
              "tests/corpus/dev.jsonl",
              "--clock",
              "2026-07-15T00:00:00Z",
              "--fail-writes"
            },
            environment(true))) {
      URI base = URI.create("http://127.0.0.1:" + app.port());
      var client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
      String session = login(client, base);

      String body = send(client, base.resolve("/"), session).body();
      assertTrue(body.contains("Read-only mode"), body);
      assertTrue(body.contains("$1,068.19"), body);
    }
  }

  @Test
  void given_completeSnapshot_when_moreThanFiveWeekdaysOld_then_marksItStaleAndItsDate()
      throws Exception {
    String atLimit = corpusOverview("2026-07-21T23:59:59Z", "five-weekdays");
    assertTrue(atLimit.contains("class=\"status status-ok\" aria-label=\"Complete\""), atLimit);
    assertFalse(atLimit.contains("class=\"status status-warn\" aria-label=\"Stale\""), atLimit);

    String stale = corpusOverview("2026-07-22T00:00:00Z", "six-weekdays");
    assertTrue(stale.contains("class=\"status status-warn\" aria-label=\"Stale\""), stale);
    assertTrue(stale.contains("No uploads for more than 5 weekdays (UTC)"), stale);
    assertTrue(stale.contains("</svg> Stale</span>"), stale);
    assertTrue(
        stale.contains(
            "<time class=\"ingestion-date ingestion-date-warn\""
                + " datetime=\"2026-07-14T14:14:39Z\">14 Jul 2026 · 14:14 UTC</time>"),
        stale);
  }

  @Test
  void given_priorMonthUsers_when_openingOverview_then_showsOnlyDisplayedMonthIngestion()
      throws Exception {
    Path corpus = directory.resolve("monthly-ingestion.jsonl");
    var events =
        Files.readAllLines(Path.of("tests/corpus/dev.jsonl")).stream()
            .map(
                line ->
                    line.contains("\"type\":\"usage-uploaded\"")
                            && !line.contains("\"subject\":\"viewer\"")
                        ? line.replace("\"period\":\"2026-07", "\"period\":\"2025-07")
                        : line)
            .toList();
    Files.write(corpus, events);
    try (var app =
        App.start(
            new String[] {"--corpus", corpus.toString(), "--clock", "2026-12-01T00:00:00Z"},
            environment(false))) {
      URI base = URI.create("http://127.0.0.1:" + app.port());
      var client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
      String body = send(client, base.resolve("/"), login(client, base)).body();
      int start = body.indexOf("<ul class=\"ingestion-list\">");
      assertTrue(start >= 0, body);
      int end = body.indexOf("</ul>", start);
      assertTrue(end > start, body);
      String ingestion = body.substring(start, end);
      assertTrue(body.contains("2026-07"), body);
      assertTrue(ingestion.contains("<strong>Synthetic User 1</strong>"), ingestion);
      assertTrue(
          ingestion.contains("class=\"status status-warn\" aria-label=\"Stale\""), ingestion);
      for (int user = 2; user <= 5; user++) {
        assertFalse(ingestion.contains("<strong>Synthetic User " + user + "</strong>"), ingestion);
      }
      String visualizations =
          send(client, base.resolve("/visualizations"), login(client, base)).body();
      assertTrue(visualizations.contains("Synthetic User 4"), visualizations);
    }
  }

  @Test
  void given_clientClockAndTimeZoneDisagree_when_openingOverview_then_usesServerReceiptInUtc()
      throws Exception {
    Path corpus = directory.resolve("client-clock-behind.jsonl");
    var events =
        Files.readAllLines(Path.of("tests/corpus/dev.jsonl")).stream()
            .map(
                line ->
                    line.contains("\"type\":\"usage-uploaded\"")
                        ? line.replace(
                                "\"generatedAt\":\"2026-07-14T14:14:39Z\"",
                                "\"generatedAt\":\"2026-06-01T00:00:00Z\"")
                            .replace(
                                "\"clientTimeZone\":\"UTC\"",
                                "\"clientTimeZone\":\"Pacific/Kiritimati\"")
                        : line)
            .toList();
    Files.write(corpus, events);
    String body = corpusOverview(corpus, "2026-07-21T23:59:59Z", "client-clock-behind");
    assertTrue(body.contains("class=\"status status-ok\" aria-label=\"Complete\""), body);
    assertFalse(body.contains("class=\"status status-warn\" aria-label=\"Stale\""), body);
    assertTrue(
        body.contains(
            "<time class=\"ingestion-date\" datetime=\"2026-07-14T14:14:39Z\">"
                + "14 Jul 2026 · 14:14 UTC</time>"),
        body);
  }

  @Test
  void given_mixedReceiptAges_when_openingOverview_then_sortsStaleAndPartialBeforeFresh()
      throws Exception {
    Path corpus = directory.resolve("mixed-receipt-ages.jsonl");
    var events =
        Files.readAllLines(Path.of("tests/corpus/dev.jsonl")).stream()
            .map(
                line ->
                    line.contains("\"type\":\"usage-uploaded\"")
                            && !line.contains("\"subject\":\"viewer\"")
                        ? line.replace(
                            "\"at\":\"2026-07-14T14:14:39Z\"", "\"at\":\"2026-07-21T14:14:39Z\"")
                        : line)
            .toList();
    Files.write(corpus, events);
    String body = corpusOverview(corpus, "2026-07-22T00:00:00Z", "mixed-receipt-ages");
    int start = body.indexOf("<ul class=\"ingestion-list\">");
    assertTrue(start >= 0, body);
    int end = body.indexOf("</ul>", start);
    assertTrue(end > start, body);
    String ingestion = body.substring(start, end);
    int stale = ingestion.indexOf("<strong>Synthetic User 1</strong>");
    int partial = ingestion.indexOf("<strong>Synthetic User 5</strong>");
    int fresh = ingestion.indexOf("<strong>Synthetic User 2</strong>");
    assertTrue(stale >= 0 && partial >= 0 && fresh >= 0, ingestion);
    assertTrue(stale < fresh && partial < fresh, ingestion);
    assertTrue(ingestion.contains("Missing: daily, blocks"), ingestion);
    assertTrue(ingestion.contains("class=\"status status-ok\" aria-label=\"Complete\""), ingestion);
    assertTrue(ingestion.contains("class=\"status status-warn\" aria-label=\"Stale\""), ingestion);
  }

  private String corpusOverview(String clock, String dataName) throws Exception {
    return corpusOverview(Path.of("tests/corpus/dev.jsonl"), clock, dataName);
  }

  private String corpusOverview(Path corpus, String clock, String dataName) throws Exception {
    try (var app =
        App.start(
            new String[] {"--corpus", corpus.toString(), "--clock", clock},
            Map.of(
                "TOKTRAK_DEV_AUTH", "true",
                "TOKTRAK_PORT", "0",
                "TOKTRAK_DATA_DIR", directory.resolve(dataName).toString()))) {
      URI base = URI.create("http://127.0.0.1:" + app.port());
      var client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
      return send(client, base.resolve("/"), login(client, base)).body();
    }
  }

  private Map<String, String> environment(boolean degraded) {
    Path data = degraded ? directory.resolve("degraded") : directory.resolve("healthy");
    return Map.of(
        "TOKTRAK_DEV_AUTH", "true",
        "TOKTRAK_PORT", "0",
        "TOKTRAK_DATA_DIR", data.toString());
  }

  private static String login(HttpClient client, URI base) throws Exception {
    HttpResponse<String> login = send(client, base.resolve("/login"), null);
    assertEquals(302, login.statusCode());
    return login.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
  }

  private static HttpResponse<String> send(HttpClient client, URI uri, String cookie)
      throws Exception {
    var request = HttpRequest.newBuilder(uri).GET();
    if (cookie != null) request.header("Cookie", cookie);
    return client.send(request.build(), BodyHandlers.ofString());
  }
}
