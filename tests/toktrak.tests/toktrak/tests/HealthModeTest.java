package toktrak.tests;

import static org.junit.jupiter.api.Assertions.*;

import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;
import toktrak.*;

final class HealthModeTest {
  @Test
  void given_failWritesMode_when_startingApp_then_reportsDegradedHealth() throws Exception {
    try (var app =
        App.start(
            new String[] {"--fail-writes"},
            Map.of("TOKTRAK_DEV_AUTH", "true", "TOKTRAK_PORT", "0"))) {
      var response = get(app, "/health");
      assertEquals(503, response.statusCode());
      assertTrue(response.body().contains("\"status\":\"degraded\""));
      assertTrue(response.body().contains("\"reason\":\"writes_failed\""));
    }
  }

  @Test
  void given_devAuthMode_when_requestingRoot_then_returnsDevAuthStrip() throws Exception {
    try (var app =
        App.start(new String[] {}, Map.of("TOKTRAK_DEV_AUTH", "true", "TOKTRAK_PORT", "0"))) {
      var response = get(app, "/");
      assertEquals(200, response.statusCode());
      assertTrue(response.body().contains("DEV AUTH"));
      assertTrue(response.body().contains("<link rel=\"stylesheet\" href=\"/assets/main.css\">"));
      assertTrue(response.body().contains("<div class=\"environment-banner\">DEV AUTH</div>"));
      assertFalse(response.body().contains("<div style="));
    }
  }

  @Test
  void given_developmentStylesheet_when_requestingAsset_then_returnsEnvironmentBannerStyles()
      throws Exception {
    try (var app =
        App.start(new String[] {}, Map.of("TOKTRAK_DEV_AUTH", "true", "TOKTRAK_PORT", "0"))) {
      var response = get(app, "/assets/main.css");
      assertEquals(200, response.statusCode());
      assertEquals("text/css; charset=utf-8", response.contentType());
      assertEquals(
          ".environment-banner{background:#b00020;color:white;padding:.5rem}", response.body());
      assertEquals(
          "default-src 'self'; frame-ancestors 'none'; base-uri 'none'",
          response.contentSecurityPolicy());
      assertEquals("nosniff", response.contentTypeOptions());
      assertEquals("DENY", response.frameOptions());
      assertEquals("no-referrer", response.referrerPolicy());
    }
  }

  @Test
  void given_postToDevelopmentStylesheet_when_requestingAsset_then_returnsBrowserNotFound()
      throws Exception {
    try (var app =
        App.start(new String[] {}, Map.of("TOKTRAK_DEV_AUTH", "true", "TOKTRAK_PORT", "0"))) {
      var response = request(app, "/assets/main.css", "POST");
      assertEquals(404, response.statusCode());
      assertTrue(response.body().contains("BRUTAL ERROR"));
    }
  }

  private static Response get(App app, String path) throws Exception {
    return request(app, path, "GET");
  }

  private static Response request(App app, String path, String method) throws Exception {
    var connection =
        (HttpURLConnection)
            URI.create("http://127.0.0.1:" + app.port() + path).toURL().openConnection();
    connection.setConnectTimeout(2_000);
    connection.setReadTimeout(2_000);
    connection.setRequestMethod(method);
    connection.setRequestProperty("Connection", "close");
    try {
      int statusCode = connection.getResponseCode();
      try (var input =
          statusCode >= 400 ? connection.getErrorStream() : connection.getInputStream()) {
        assertNotNull(input);
        return new Response(
            statusCode,
            connection.getHeaderField("Content-Type"),
            connection.getHeaderField("Content-Security-Policy"),
            connection.getHeaderField("X-Content-Type-Options"),
            connection.getHeaderField("X-Frame-Options"),
            connection.getHeaderField("Referrer-Policy"),
            new String(input.readAllBytes(), StandardCharsets.UTF_8));
      }
    } finally {
      connection.disconnect();
    }
  }

  private record Response(
      int statusCode,
      String contentType,
      String contentSecurityPolicy,
      String contentTypeOptions,
      String frameOptions,
      String referrerPolicy,
      String body) {
    private Response {
      assert statusCode >= 100 && statusCode <= 599;
      assert contentType != null && !contentType.isBlank();
      assert contentSecurityPolicy != null && !contentSecurityPolicy.isBlank();
      assert contentTypeOptions != null && !contentTypeOptions.isBlank();
      assert frameOptions != null && !frameOptions.isBlank();
      assert referrerPolicy != null && !referrerPolicy.isBlank();
      assert body != null;
    }
  }
}
