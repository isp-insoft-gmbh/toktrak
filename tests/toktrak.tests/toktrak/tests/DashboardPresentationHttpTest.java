package toktrak.tests;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import toktrak.App;

/// Pins the dashboard presentation helpers that only HTTP rendering reaches: avatar initials,
/// half-up money rounding, grouped token counts, and the label length limits.
final class DashboardPresentationHttpTest {
  private static final String CLOCK = "2026-07-15T00:00:00Z";
  private static final Pattern CSRF = Pattern.compile("name=\\\"csrf\\\" value=\\\"([^\\\"]+)\\\"");
  private static final Pattern TOKEN = Pattern.compile("tt_[A-Za-z0-9_-]{43}");
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
    try (var app =
        App.start(
            new String[] {"--corpus", corpus.toString(), "--clock", CLOCK},
            environment("initials"))) {
      URI base = URI.create("http://127.0.0.1:" + app.port());
      var client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
      String streams = section(visualizations(client, base), "streams-heading", "</ol>");

      assertTrue(streams.contains(avatar("S1", "Synthetic User 1")), streams);
      assertTrue(streams.contains(avatar("AL", "  Ada   Lovelace ")), streams);
      assertTrue(streams.contains(avatar("G", "Grace")), streams);
      assertTrue(streams.contains(avatar("𝔄N", "𝔄da von Neumann")), streams);
      assertTrue(streams.contains("class=\"avatar color-5\""), streams);
    }
  }

  @Test
  void given_uploadAtLabelAndRoundingBoundaries_when_openingDashboards_then_formatsEveryValue()
      throws Exception {
    try (var app = App.start(new String[] {"--clock", CLOCK}, environment("boundaries"))) {
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
              upload(),
              token);
      assertEquals(200, uploaded.statusCode(), uploaded.body());

      String overview = send(client, base.resolve("/"), "GET", null, cookie, null, null).body();
      assertTrue(overview.contains("id=\"kpi-cost\" data-counter=\"0.13\""), overview);
      assertTrue(overview.contains("$0.13"), overview);
      assertFalse(overview.contains("$0.12"), overview);
      assertTrue(overview.contains("id=\"kpi-tokens\" data-counter=\"1234567\""), overview);
      assertTrue(overview.contains("1,234,567"), overview);

      String visualizations = visualizations(client, base);
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

      String blocks = section(visualizations, "blocks-heading", "</table>");
      assertTrue(
          blocks.contains(
              "<td>2026-07-14T07:00:00.000Z</td><td>gpt-test</td><td>$0.50</td><td>4,294,967,296</td>"
                  + "<td>12</td>"),
          blocks);

      String spikes = section(visualizations, "Largest individual daily cost rows", "</table>");
      assertTrue(spikes.contains("<td>$0.13</td><td>1,234,567</td>"), spikes);
    }
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

  private static String visualizations(HttpClient client, URI base) throws Exception {
    HttpResponse<String> response =
        send(client, base.resolve("/visualizations"), "GET", null, login(client, base), null, null);
    assertEquals(200, response.statusCode(), response.body());
    return response.body();
  }

  private Map<String, String> environment(String dataName) {
    return Map.of(
        "TOKTRAK_DEV_AUTH", "true",
        "TOKTRAK_PORT", "0",
        "TOKTRAK_DATA_DIR", directory.resolve(dataName).toString());
  }

  private static String login(HttpClient client, URI base) throws Exception {
    HttpResponse<String> login =
        send(client, base.resolve("/login"), "GET", null, null, null, null);
    assertEquals(302, login.statusCode());
    return login.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
  }

  private static String createTrackerToken(HttpClient client, URI base, String cookie)
      throws Exception {
    HttpResponse<String> tokens =
        send(client, base.resolve("/tokens"), "GET", null, cookie, null, null);
    String csrf = match(CSRF, tokens.body());
    String form =
        "label="
            + URLEncoder.encode("Workstation", StandardCharsets.UTF_8)
            + "&csrf="
            + URLEncoder.encode(csrf, StandardCharsets.UTF_8);
    HttpResponse<String> created =
        send(
            client,
            base.resolve("/tokens"),
            "POST",
            "application/x-www-form-urlencoded",
            cookie,
            form,
            null);
    assertEquals(201, created.statusCode(), created.body());
    return match(TOKEN, created.body());
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
    return client.send(request.build(), BodyHandlers.ofString());
  }

  private static String match(Pattern pattern, String value) {
    var matcher = pattern.matcher(value);
    assertTrue(matcher.find(), value);
    return matcher.groupCount() == 0 ? matcher.group() : matcher.group(1);
  }
}
