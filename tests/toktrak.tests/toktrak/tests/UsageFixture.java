package toktrak.tests;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import toktrak.App;

/// A pinned-clock development server with one signed-in viewer who uploads daily usage.
final class UsageFixture implements AutoCloseable {
  static final String CLOCK = "2026-07-15T00:00:00Z";
  private static final Pattern CSRF = Pattern.compile("name=\\\"csrf\\\" value=\\\"([^\\\"]+)\\\"");
  private static final Pattern TOKEN = Pattern.compile("tt_[A-Za-z0-9_-]{43}");
  private static final Duration TIMEOUT = Duration.ofSeconds(3);

  private final App app;
  private final URI base;
  private final HttpClient client;
  private final String cookie;

  UsageFixture(Path dataDirectory) throws Exception {
    this(dataDirectory, List.of());
  }

  /// `arguments` follow the pinned clock, for example `--corpus` and its path.
  UsageFixture(Path dataDirectory, List<String> arguments) throws Exception {
    var all = new ArrayList<String>(List.of("--clock", CLOCK));
    all.addAll(arguments);
    app =
        App.start(
            all.toArray(String[]::new),
            Map.of(
                "TOKTRAK_DEV_AUTH", "true",
                "TOKTRAK_PORT", "0",
                "TOKTRAK_DATA_DIR", dataDirectory.toString()));
    base = URI.create("http://127.0.0.1:" + app.port());
    client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    HttpResponse<String> login =
        client.send(
            HttpRequest.newBuilder(base.resolve("/login")).timeout(TIMEOUT).build(),
            BodyHandlers.ofString());
    assertEquals(302, login.statusCode());
    cookie = login.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
  }

  /// A daily report row; empty `agents` or `metadataAgent` omit that field.
  static String daily(String date, String cost, String agents, String metadataAgent) {
    return "{\"period\":\""
        + date
        + "\",\"agent\":\"all\",\"inputTokens\":1,\"outputTokens\":1,\"cacheCreationTokens\":0,"
        + "\"cacheReadTokens\":0,\"totalTokens\":2,\"totalCost\":"
        + cost
        + (agents.isEmpty() ? "" : ",\"agents\":[" + agents + "]")
        + (metadataAgent.isEmpty() ? "" : ",\"metadata\":{\"agents\":[" + metadataAgent + "]}")
        + "}";
  }

  void upload(String... dailyRows) throws Exception {
    String body =
        "{\"trackerVersion\":\"1\",\"ccusageVersion\":\"20.0.17\",\"clientTimeZone\":\"UTC\","
            + "\"full\":true,\"generatedAt\":\"2026-07-14T23:00:00Z\",\"reports\":{"
            + "\"daily\":{\"ok\":true,\"json\":{\"daily\":["
            + String.join(",", dailyRows)
            + "]}}}}";
    var request =
        HttpRequest.newBuilder(base.resolve("/api/usage"))
            .timeout(TIMEOUT)
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + trackerToken())
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
    HttpResponse<String> response = client.send(request, BodyHandlers.ofString());
    assertEquals(200, response.statusCode(), response.body());
  }

  String overview() throws Exception {
    var request =
        HttpRequest.newBuilder(base.resolve("/"))
            .timeout(TIMEOUT)
            .header("Cookie", cookie)
            .GET()
            .build();
    HttpResponse<String> response = client.send(request, BodyHandlers.ofString());
    assertEquals(200, response.statusCode(), response.body());
    return response.body();
  }

  String visualizations(String query) throws Exception {
    HttpResponse<String> response = send(query);
    assertEquals(200, response.statusCode(), response.body());
    return response.body();
  }

  HttpResponse<String> send(String query) throws Exception {
    var request =
        HttpRequest.newBuilder(base.resolve("/visualizations" + (query == null ? "" : "?" + query)))
            .timeout(TIMEOUT)
            .header("Cookie", cookie)
            .GET()
            .build();
    return client.send(request, BodyHandlers.ofString());
  }

  private String trackerToken() throws Exception {
    HttpResponse<String> tokens =
        client.send(
            HttpRequest.newBuilder(base.resolve("/tokens"))
                .timeout(TIMEOUT)
                .header("Cookie", cookie)
                .build(),
            BodyHandlers.ofString());
    var csrf = CSRF.matcher(tokens.body());
    assertTrue(csrf.find(), tokens.body());
    String form =
        "label=Workstation&csrf=" + URLEncoder.encode(csrf.group(1), StandardCharsets.UTF_8);
    HttpResponse<String> created =
        client.send(
            HttpRequest.newBuilder(base.resolve("/tokens"))
                .timeout(TIMEOUT)
                .header("Cookie", cookie)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build(),
            BodyHandlers.ofString());
    assertEquals(201, created.statusCode(), created.body());
    var token = TOKEN.matcher(created.body());
    assertTrue(token.find(), created.body());
    return token.group();
  }

  @Override
  public void close() {
    app.close();
  }
}
