package toktrak.tests;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
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
      assertTrue(overview.body().contains("PARTIAL REPORTS"), overview.body());
      assertTrue(overview.body().contains("Synthetic User 1"), overview.body());
      assertTrue(overview.body().contains("<span class=\"rank\">1</span>"), overview.body());
      assertTrue(overview.body().contains(">S1</span>"), overview.body());
      assertTrue(
          overview.body().contains("<span class=\"status status-ok\">Complete</span>"),
          overview.body());
      assertTrue(overview.body().contains("All reports · Tracker vdev-corpus"), overview.body());
      assertTrue(
          overview.body().contains("<span class=\"status status-warn\">Partial</span>"),
          overview.body());
      assertTrue(overview.body().contains(">██████████</span>"), overview.body());
      assertFalse(overview.body().contains("datastar-patch-signals"), overview.body());
      assertTrue(
          overview.body().matches("(?s).*src=\"/assets/datastar\\.[0-9a-f]{32}\\.js\".*"),
          overview.body());

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
      assertTrue(visualizations.body().contains("<time>2026-07-14</time>"));
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
      assertTrue(eur.body().contains("Showing EUR estimates"), eur.body());
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
      assertTrue(body.contains("READ-ONLY MODE"), body);
      assertTrue(body.contains("$1,068.19"), body);
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
