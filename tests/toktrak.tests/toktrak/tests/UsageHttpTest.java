package toktrak.tests;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import toktrak.App;

final class UsageHttpTest {
  private static final Pattern CSRF = Pattern.compile("name=\\\"csrf\\\" value=\\\"([^\\\"]+)\\\"");
  private static final Pattern TOKEN = Pattern.compile("tt_[A-Za-z0-9_-]{43}");
  private static final String CLOCK = "2026-07-15T00:00:00Z";
  @TempDir Path directory;

  @Test
  void
      given_trackerUpload_when_queryingProtectedAnalyticsAndRestarting_then_preservesCanonicalUsage()
          throws Exception {
    String token;
    try (var app = start()) {
      URI base = URI.create("http://127.0.0.1:" + app.port());
      var client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
      String cookie = login(client, base);
      token = createTrackerToken(client, base, cookie);

      assertEquals(
          401, send(client, base.resolve("/api/analytics"), "GET", null, null, null).statusCode());
      HttpResponse<String> uploaded =
          send(
              client,
              base.resolve("/api/usage"),
              "POST",
              "application/json",
              null,
              usage("2026-07-14T23:00:00Z", "1", true),
              token);
      assertEquals(200, uploaded.statusCode(), uploaded.body());
      assertTrue(uploaded.body().contains("\"partial\":false"), uploaded.body());

      HttpResponse<String> analytics =
          send(client, base.resolve("/api/analytics"), "GET", null, cookie, null);
      assertEquals(200, analytics.statusCode());
      assertTrue(analytics.body().contains("\"costUsd\":1"), analytics.body());
      assertTrue(analytics.body().contains("\"dailyRows\":1"), analytics.body());
      assertTrue(analytics.body().contains("\"partial\":false"), analytics.body());
      HttpResponse<String> visualizations =
          send(client, base.resolve("/visualizations"), "GET", null, cookie, null);
      assertEquals(200, visualizations.statusCode());
      assertTrue(
          visualizations.body().contains("<td>codex</td><td>Not reported</td>"),
          visualizations.body());

      HttpResponse<String> daily =
          send(
              client,
              base.resolve("/api/usage/daily?page=1&pageSize=1"),
              "GET",
              null,
              cookie,
              null);
      assertEquals(200, daily.statusCode());
      assertTrue(daily.body().contains("\"unknownField\":\"preserved\""), daily.body());
      assertEquals(
          400,
          send(client, base.resolve("/api/usage/daily?pageSize=1001"), "GET", null, cookie, null)
              .statusCode());

      HttpResponse<String> stream =
          send(client, base.resolve("/api/stream?revision=0"), "GET", null, cookie, null);
      assertEquals(200, stream.statusCode());
      assertEquals(
          "text/event-stream; charset=utf-8",
          stream.headers().firstValue("Content-Type").orElseThrow());
      assertTrue(stream.body().startsWith("event: datastar-patch-signals\n"), stream.body());
      assertTrue(stream.body().contains("\"_usageRevision\":"), stream.body());

      assertEquals(
          400,
          send(
                  client,
                  base.resolve("/api/usage"),
                  "POST",
                  "application/json",
                  null,
                  usage("2026-07-16T00:00:00.000000001Z", "9", true),
                  token)
              .statusCode());
      HttpResponse<String> partial =
          send(
              client,
              base.resolve("/api/usage"),
              "POST",
              "application/json",
              null,
              usage("2026-07-14T22:00:00Z", "99", false),
              token);
      assertEquals(200, partial.statusCode());
      assertTrue(partial.body().contains("\"partial\":true"), partial.body());
      assertTrue(partial.body().contains("\"failedReports\":[\"daily\"]"), partial.body());
      HttpResponse<String> unchanged =
          send(client, base.resolve("/api/analytics"), "GET", null, cookie, null);
      assertTrue(unchanged.body().contains("\"costUsd\":1"), unchanged.body());
    }
    String events = Files.readString(directory.resolve("events.ndjson"));
    assertTrue(events.contains("\"futureDailyField\":\"preserved\""));
    assertTrue(events.contains("\"futureSourceField\":\"preserved\""));

    try (var app = start()) {
      URI base = URI.create("http://127.0.0.1:" + app.port());
      var client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
      String cookie = login(client, base);
      HttpResponse<String> analytics =
          send(client, base.resolve("/api/analytics"), "GET", null, cookie, null);
      assertEquals(200, analytics.statusCode());
      assertTrue(analytics.body().contains("\"costUsd\":1"), analytics.body());
      assertEquals(
          200,
          send(
                  client,
                  base.resolve("/api/tracker/verify"),
                  "POST",
                  "application/json",
                  null,
                  "{}",
                  token)
              .statusCode());
    }
  }

  @Test
  void given_dailyUsageWithAgentOnly_when_renderingVisualizations_then_labelsSourceFromAgent()
      throws Exception {
    try (var app = start()) {
      URI base = URI.create("http://127.0.0.1:" + app.port());
      var client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
      String cookie = login(client, base);
      String token = createTrackerToken(client, base, cookie);

      HttpResponse<String> uploaded =
          send(
              client,
              base.resolve("/api/usage"),
              "POST",
              "application/json",
              null,
              usage("2026-07-14T23:00:00Z", "1", true),
              token);
      assertEquals(200, uploaded.statusCode(), uploaded.body());

      HttpResponse<String> visualizations =
          send(client, base.resolve("/visualizations"), "GET", null, cookie, null);
      assertEquals(200, visualizations.statusCode());
      assertTrue(visualizations.body().contains("<h3>Sources</h3>"), visualizations.body());
      assertTrue(visualizations.body().contains("<strong>claude</strong>"), visualizations.body());
      assertFalse(
          visualizations.body().contains("<strong>Unknown source</strong>"), visualizations.body());
    }
  }

  private App start() {
    return App.start(
        new String[] {"--clock", CLOCK},
        Map.of(
            "TOKTRAK_DEV_AUTH", "true",
            "TOKTRAK_DATA_DIR", directory.toString(),
            "TOKTRAK_PORT", "0"));
  }

  private static String login(HttpClient client, URI base) throws Exception {
    HttpResponse<String> login = send(client, base.resolve("/login"), "GET", null, null, null);
    assertEquals(302, login.statusCode());
    return login.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
  }

  private static String createTrackerToken(HttpClient client, URI base, String cookie)
      throws Exception {
    HttpResponse<String> tokens = send(client, base.resolve("/tokens"), "GET", null, cookie, null);
    String csrf = match(CSRF, tokens.body());
    HttpResponse<String> created =
        send(
            client,
            base.resolve("/tokens"),
            "POST",
            "application/x-www-form-urlencoded",
            cookie,
            form(Map.of("label", "Workstation", "csrf", csrf)));
    assertEquals(201, created.statusCode(), created.body());
    return match(TOKEN, created.body());
  }

  private static String usage(String generatedAt, String cost, boolean complete) {
    String daily =
        complete
            ? "{\"ok\":true,\"json\":{\"daily\":[{\"period\":\"2026-07-14\",\"agent\":\"claude\",\"inputTokens\":10,\"outputTokens\":2,\"cacheCreationTokens\":3,\"cacheReadTokens\":4,\"totalTokens\":19,\"totalCost\":"
                + cost
                + ",\"unknownField\":\"preserved\"}]}}"
            : "{\"ok\":false,\"error\":\"daily failed\"}";
    return "{\"trackerVersion\":\"1\",\"ccusageVersion\":\"20.0.17\",\"clientTimeZone\":\"UTC\",\"full\":"
        + complete
        + ",\"generatedAt\":\""
        + generatedAt
        + "\",\"reports\":{\"daily\":"
        + daily
        + ",\"session\":{\"ok\":true,\"json\":{\"session\":[{\"period\":\"codex-session\",\"agent\":\"codex\",\"modelsUsed\":[\"gpt-test\"],\"totalCost\":0.2,\"totalTokens\":12,\"metadata\":{\"lastActivity\":\"2026-07-14T22:00:00Z\"}}]}},\"blocks\":{\"ok\":true,\"json\":{\"blocks\":[]}},\"sourceReports\":{\"codex\":{\"daily\":{\"ok\":true,\"json\":{\"daily\":[{\"futureDailyField\":\"preserved\"}],\"totals\":{}}},\"session\":{\"ok\":true,\"json\":{\"sessions\":[{\"sessionId\":\"codex-session\",\"futureSourceField\":\"preserved\"}],\"totals\":{}}}}}}}";
  }

  private static HttpResponse<String> send(
      HttpClient client, URI uri, String method, String contentType, String cookie, String body)
      throws Exception {
    return send(client, uri, method, contentType, cookie, body, null);
  }

  private static HttpResponse<String> send(
      HttpClient client,
      URI uri,
      String method,
      String contentType,
      String cookie,
      String body,
      String token)
      throws Exception {
    var request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(3));
    if (contentType != null) request.header("Content-Type", contentType);
    if (cookie != null) request.header("Cookie", cookie);
    if (token != null) request.header("Authorization", "Bearer " + token);
    request.method(
        method,
        body == null
            ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofString(body));
    return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  private static String form(Map<String, String> fields) {
    return fields.entrySet().stream()
        .map(
            entry ->
                URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8)
                    + "="
                    + URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8))
        .collect(java.util.stream.Collectors.joining("&"));
  }

  private static String match(Pattern pattern, String value) {
    var matcher = pattern.matcher(value);
    assertTrue(matcher.find(), value);
    return matcher.groupCount() == 0 ? matcher.group() : matcher.group(1);
  }
}
