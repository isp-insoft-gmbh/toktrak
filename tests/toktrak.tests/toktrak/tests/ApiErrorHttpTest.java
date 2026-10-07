package toktrak.tests;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import toktrak.App;
import toktrak.http.HttpSupport;

final class ApiErrorHttpTest {
  private static final Pattern CSRF = Pattern.compile("name=\\\"csrf\\\" value=\\\"([^\\\"]+)\\\"");
  private static final Pattern TOKEN = Pattern.compile("tt_[A-Za-z0-9_-]{43}");
  private static final String USAGE =
      "{\"trackerVersion\":\"1\",\"ccusageVersion\":\"20.0.17\",\"clientTimeZone\":\"UTC\",\"full\":true,\"generatedAt\":\"2026-07-14T23:00:00Z\",\"reports\":{\"daily\":{\"ok\":true,\"json\":{\"daily\":[]}}}}";
  @TempDir Path directory;

  @Test
  void given_apiRequestsOutsideTrustBoundary_when_routing_then_answersStructuredApiErrors()
      throws Exception {
    String token;
    try (var app = start(new String[] {})) {
      URI base = URI.create("http://127.0.0.1:" + app.port());
      var client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
      String cookie = login(client, base);
      token = createTrackerToken(client, base, cookie);

      HttpResponse<String> anonymous = send(client, base.resolve("/api/auth"), "GET", null, null);
      assertEquals(401, anonymous.statusCode(), anonymous.body());
      assertApiError(anonymous, "login_required", "login required");
      HttpResponse<String> authenticated =
          send(client, base.resolve("/api/auth"), "GET", null, cookie);
      assertEquals(200, authenticated.statusCode(), authenticated.body());
      assertTrue(authenticated.body().contains("\"status\":\"ok\""), authenticated.body());
      assertTrue(authenticated.body().contains("\"subject\":\"viewer\""), authenticated.body());
      assertTrue(
          authenticated.body().matches(".*\"color\":\"#[0-9a-f]{6}\".*"), authenticated.body());

      HttpResponse<String> missingBearer =
          send(client, base.resolve("/api/usage"), "POST", "application/json", null, USAGE, null);
      assertEquals(401, missingBearer.statusCode(), missingBearer.body());
      assertApiError(missingBearer, "invalid_token", "tracker token is invalid");
      HttpResponse<String> basicScheme =
          sendAuthorization(client, base.resolve("/api/usage"), "Basic " + token);
      assertEquals(401, basicScheme.statusCode(), basicScheme.body());
      assertApiError(basicScheme, "invalid_token", "tracker token is invalid");
      HttpResponse<String> unknownToken =
          send(
              client,
              base.resolve("/api/usage"),
              "POST",
              "application/json",
              null,
              USAGE,
              "tt_" + "A".repeat(43));
      assertEquals(401, unknownToken.statusCode(), unknownToken.body());
      assertApiError(unknownToken, "invalid_token", "tracker token is invalid");

      HttpResponse<String> wrongMediaType =
          send(client, base.resolve("/api/usage"), "POST", "text/plain", null, USAGE, token);
      assertEquals(415, wrongMediaType.statusCode(), wrongMediaType.body());
      assertApiError(wrongMediaType, "unsupported_media_type", "application/json is required");
      HttpResponse<String> tooLarge =
          send(
              client,
              base.resolve("/api/usage"),
              "POST",
              "application/json",
              null,
              "x".repeat(HttpSupport.MAX_REQUEST_BODY_BYTES + 1),
              token);
      assertEquals(413, tooLarge.statusCode(), tooLarge.body());
      assertApiError(tooLarge, "payload_too_large", "usage upload is too large");
      HttpResponse<String> accepted =
          send(client, base.resolve("/api/usage"), "POST", "application/json", null, USAGE, token);
      assertEquals(200, accepted.statusCode(), accepted.body());

      HttpResponse<String> largestPage =
          send(client, base.resolve("/api/usage/daily?pageSize=1000"), "GET", null, cookie);
      assertEquals(200, largestPage.statusCode(), largestPage.body());
      assertTrue(largestPage.body().contains("\"pageCount\":1"), largestPage.body());
      HttpResponse<String> unknownReport =
          send(client, base.resolve("/api/usage/weekly"), "GET", null, cookie);
      assertEquals(404, unknownReport.statusCode(), unknownReport.body());
      assertApiError(unknownReport, "not_found", "route not found");
    }

    try (var app = start(new String[] {"--fail-writes"})) {
      URI base = URI.create("http://127.0.0.1:" + app.port());
      var client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
      HttpResponse<String> upload =
          send(client, base.resolve("/api/usage"), "POST", "application/json", null, USAGE, token);
      assertEquals(503, upload.statusCode(), upload.body());
      assertApiError(upload, "write_unavailable", "tracker verification is unavailable");
      HttpResponse<String> verify =
          send(
              client,
              base.resolve("/api/tracker/verify"),
              "POST",
              "application/json",
              null,
              "{}",
              token);
      assertEquals(503, verify.statusCode(), verify.body());
      assertApiError(verify, "write_unavailable", "tracker verification is unavailable");
    }
  }

  private App start(String[] args) {
    return App.start(
        args,
        Map.of(
            "TOKTRAK_DEV_AUTH", "true",
            "TOKTRAK_DATA_DIR", directory.toString(),
            "TOKTRAK_PORT", "0"));
  }

  private static void assertApiError(HttpResponse<String> response, String code, String message) {
    assertEquals(
        "application/json; charset=utf-8",
        response.headers().firstValue("Content-Type").orElseThrow());
    assertTrue(response.body().contains("\"code\":\"" + code + "\""), response.body());
    assertTrue(response.body().contains("\"message\":\"" + message + "\""), response.body());
    assertTrue(response.body().contains("\"requestId\":\""), response.body());
  }

  private static String login(HttpClient client, URI base) throws Exception {
    HttpResponse<String> login = send(client, base.resolve("/login"), "GET", null, null);
    assertEquals(302, login.statusCode());
    return login.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
  }

  private static String createTrackerToken(HttpClient client, URI base, String cookie)
      throws Exception {
    HttpResponse<String> tokens = send(client, base.resolve("/tokens"), "GET", null, cookie);
    String csrf = match(CSRF, tokens.body());
    HttpResponse<String> created =
        send(
            client,
            base.resolve("/tokens"),
            "POST",
            "application/x-www-form-urlencoded",
            cookie,
            form(Map.of("label", "Workstation", "csrf", csrf)),
            null);
    assertEquals(201, created.statusCode(), created.body());
    return match(TOKEN, created.body());
  }

  private static HttpResponse<String> sendAuthorization(
      HttpClient client, URI uri, String authorization) throws Exception {
    var request =
        HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(8))
            .header("Content-Type", "application/json")
            .header("Authorization", authorization)
            .POST(HttpRequest.BodyPublishers.ofString(USAGE))
            .build();
    return client.send(request, HttpResponse.BodyHandlers.ofString());
  }

  private static HttpResponse<String> send(
      HttpClient client, URI uri, String method, String contentType, String cookie)
      throws Exception {
    return send(client, uri, method, contentType, cookie, null, null);
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
    var request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(8));
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
