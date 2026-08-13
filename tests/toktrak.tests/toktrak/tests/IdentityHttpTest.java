package toktrak.tests;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import toktrak.App;

final class IdentityHttpTest {
  private static final Pattern CSRF = Pattern.compile("name=\\\"csrf\\\" value=\\\"([^\\\"]+)\\\"");
  private static final Pattern TOKEN = Pattern.compile("tt_[A-Za-z0-9_-]{43}");
  private static final Pattern TOKEN_ID =
      Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
  @TempDir Path directory;

  @Test
  void given_developmentLogin_when_managingTrackerToken_then_enforcesCsrfAndRevocation()
      throws Exception {
    try (var app =
        App.start(
            new String[] {},
            Map.of(
                "TOKTRAK_DEV_AUTH", "true",
                "TOKTRAK_DATA_DIR", directory.toString(),
                "TOKTRAK_PORT", "0"))) {
      URI base = URI.create("http://127.0.0.1:" + app.port());
      var client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
      assertEquals(
          302, send(client, base.resolve("/tokens"), "GET", null, null, null).statusCode());
      assertEquals(
          401, send(client, base.resolve("/oauth/callback"), "GET", null, null, null).statusCode());
      HttpResponse<String> login = send(client, base.resolve("/login"), "GET", null, null, null);
      assertEquals(302, login.statusCode());
      assertEquals(
          base.resolve("/tokens").toString(), login.headers().firstValue("Location").orElseThrow());
      String cookie = login.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];

      HttpResponse<String> page = send(client, base.resolve("/tokens"), "GET", cookie, null, null);
      assertEquals(200, page.statusCode());
      String csrf = match(CSRF, page.body());
      assertEquals(
          400,
          send(client, base.resolve("/tokens"), "POST", cookie, "label=Laptop&csrf=wrong", null)
              .statusCode());

      HttpResponse<String> created =
          send(
              client,
              base.resolve("/tokens"),
              "POST",
              cookie,
              form(Map.of("label", "Laptop", "csrf", csrf)),
              null);
      assertEquals(201, created.statusCode());
      String token = match(TOKEN, created.body());
      assertFalse(created.headers().firstValue("Cache-Control").orElseThrow().contains("public"));

      assertEquals(
          200,
          send(client, base.resolve("/api/tracker/verify"), "POST", null, "", "Bearer " + token)
              .statusCode());
      HttpResponse<String> usedPage =
          send(client, base.resolve("/tokens"), "GET", cookie, null, null);
      assertTrue(usedPage.body().contains("last used"));
      String tokenId = match(TOKEN_ID, usedPage.body());

      assertEquals(
          400,
          send(
                  client,
                  base.resolve("/tokens/revoke"),
                  "POST",
                  cookie,
                  form(Map.of("tokenId", tokenId, "csrf", "wrong")),
                  null)
              .statusCode());
      assertEquals(
          303,
          send(
                  client,
                  base.resolve("/tokens/revoke"),
                  "POST",
                  cookie,
                  form(Map.of("tokenId", tokenId, "csrf", csrf)),
                  null)
              .statusCode());
      assertEquals(
          401,
          send(client, base.resolve("/api/tracker/verify"), "POST", null, "", "Bearer " + token)
              .statusCode());
      assertEquals(
          400,
          send(client, base.resolve("/logout"), "POST", cookie, form(Map.of("csrf", "wrong")), null)
              .statusCode());
      HttpResponse<String> logout =
          send(client, base.resolve("/logout"), "POST", cookie, form(Map.of("csrf", csrf)), null);
      assertEquals(303, logout.statusCode());
      assertTrue(logout.headers().firstValue("Set-Cookie").orElseThrow().contains("Max-Age=0"));
      assertEquals(
          400,
          send(
                  client,
                  base.resolve("/account/deactivate"),
                  "POST",
                  cookie,
                  form(Map.of("csrf", "wrong")),
                  null)
              .statusCode());
      HttpResponse<String> deactivated =
          send(
              client,
              base.resolve("/account/deactivate"),
              "POST",
              cookie,
              form(Map.of("csrf", csrf)),
              null);
      assertEquals(303, deactivated.statusCode());
      assertTrue(
          deactivated.headers().allValues("Set-Cookie").stream()
              .anyMatch(value -> value.contains("Max-Age=0")));
      assertEquals(
          401, send(client, base.resolve("/api/auth"), "GET", cookie, null, null).statusCode());
    }
  }

  private static HttpResponse<String> send(
      HttpClient client, URI uri, String method, String cookie, String form, String authorization)
      throws Exception {
    var request = HttpRequest.newBuilder(uri).timeout(java.time.Duration.ofSeconds(2));
    if (cookie != null) request.header("Cookie", cookie);
    if (authorization != null) request.header("Authorization", authorization);
    if (form == null) {
      request.method(method, HttpRequest.BodyPublishers.noBody());
    } else {
      request
          .header("Content-Type", "application/x-www-form-urlencoded")
          .method(method, HttpRequest.BodyPublishers.ofString(form));
    }
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
