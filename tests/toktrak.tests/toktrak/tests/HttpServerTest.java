package toktrak.tests;

import static org.junit.jupiter.api.Assertions.*;

import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;
import toktrak.*;

final class HttpServerTest {
  @Test
  void given_healthyApp_when_requestingHealth_then_returnsJsonAndSecurityHeaders()
      throws Exception {
    try (var app =
        App.start(new String[] {}, Map.of("TOKTRAK_DEV_AUTH", "true", "TOKTRAK_PORT", "0"))) {
      var response = get(app, "/health");
      assertEquals(200, response.statusCode());
      assertEquals("nosniff", response.header("x-content-type-options"));
      assertEquals("DENY", response.header("x-frame-options"));
      assertTrue(response.body().contains("\"status\":\"ok\""));
    }
  }

  @Test
  void given_pathAboveLengthLimit_when_requestingRoute_then_returnsUriTooLong() throws Exception {
    try (var app =
        App.start(new String[] {}, Map.of("TOKTRAK_DEV_AUTH", "true", "TOKTRAK_PORT", "0"))) {
      var response = get(app, "/" + "a".repeat(2_049));
      assertEquals(414, response.statusCode());
    }
  }

  @Test
  void given_unknownApiRoute_when_requestingRoute_then_returnsNotFoundEnvelope() throws Exception {
    try (var app =
        App.start(new String[] {}, Map.of("TOKTRAK_DEV_AUTH", "true", "TOKTRAK_PORT", "0"))) {
      var response = get(app, "/api/nope");
      assertEquals(404, response.statusCode());
      assertTrue(response.body().contains("\"error\""));
      assertTrue(response.body().contains("\"code\":\"not_found\""));
      assertTrue(response.body().contains("\"requestId\""));
    }
  }

  @Test
  void given_unknownBrowserRoute_when_requestingRoute_then_returnsBrutalNotFoundHtml()
      throws Exception {
    try (var app =
        App.start(new String[] {}, Map.of("TOKTRAK_DEV_AUTH", "true", "TOKTRAK_PORT", "0"))) {
      var response = get(app, "/nope");
      assertEquals(404, response.statusCode());
      assertTrue(response.body().contains("<h1>404</h1>"));
      assertTrue(response.body().contains("BRUTAL ERROR"));
    }
  }

  private static Response get(App app, String path) throws Exception {
    var connection =
        (HttpURLConnection)
            URI.create("http://127.0.0.1:" + app.port() + path).toURL().openConnection();
    connection.setConnectTimeout(2_000);
    connection.setReadTimeout(2_000);
    connection.setRequestMethod("GET");
    connection.setRequestProperty("Connection", "close");
    try {
      int statusCode = connection.getResponseCode();
      try (var input =
          statusCode >= 400 ? connection.getErrorStream() : connection.getInputStream()) {
        assertNotNull(input);
        return new Response(
            statusCode,
            connection.getHeaderFields(),
            new String(input.readAllBytes(), StandardCharsets.UTF_8));
      }
    } finally {
      connection.disconnect();
    }
  }

  private record Response(
      int statusCode, Map<String, java.util.List<String>> headers, String body) {
    private Response {
      assert statusCode >= 100 && statusCode <= 599;
      assert headers != null;
      assert body != null;
    }

    private String header(String name) {
      return headers.entrySet().stream()
          .filter(entry -> entry.getKey() != null && entry.getKey().equalsIgnoreCase(name))
          .flatMap(entry -> entry.getValue().stream())
          .findFirst()
          .orElseThrow();
    }
  }
}
