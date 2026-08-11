package toktrak.tests;

import static org.junit.jupiter.api.Assertions.*;

import java.net.HttpURLConnection;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import toktrak.*;

final class HttpServerTest {
  @TempDir Path directory;

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
  void given_methodAboveLengthLimit_when_requestingBrowserRoute_then_returnsDiagnosticBadRequest()
      throws Exception {
    try (var app =
        App.start(new String[] {}, Map.of("TOKTRAK_DEV_AUTH", "true", "TOKTRAK_PORT", "0"))) {
      var request =
          HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/nope"))
              .method("X".repeat(33), HttpRequest.BodyPublishers.noBody())
              .build();
      var response = HttpClient.newHttpClient().send(request, BodyHandlers.ofString());
      assertEquals(400, response.statusCode());
      assertTrue(response.body().contains("Request method is invalid."));
      assertTrue(response.body().contains("<code>(invalid method)</code>"));
    }
  }

  @Test
  void given_pathAboveLengthLimit_when_requestingRoute_then_returnsUriTooLong() throws Exception {
    try (var app =
        App.start(new String[] {}, Map.of("TOKTRAK_DEV_AUTH", "true", "TOKTRAK_PORT", "0"))) {
      var response = get(app, "/" + "a".repeat(2_049));
      assertEquals(414, response.statusCode());
      assertEquals("text/html; charset=utf-8", response.header("content-type"));
      assertTrue(response.body().contains("Request URI is too long."));
      assertTrue(response.body().contains("URI exceeds 2048-byte limit"));
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
  void given_unknownDevelopmentBrowserRoute_when_requestingRoute_then_returnsDiagnosticHtml()
      throws Exception {
    try (var app =
        App.start(new String[] {}, Map.of("TOKTRAK_DEV_AUTH", "true", "TOKTRAK_PORT", "0"))) {
      var response = get(app, "/nope");
      assertEquals(404, response.statusCode());
      assertEquals("text/html; charset=utf-8", response.header("content-type"));
      assertTrue(response.body().contains("<h1>404</h1>"));
      assertTrue(response.body().contains("Route not found."));
      assertTrue(response.body().contains("<code>/nope</code>"));
      assertTrue(response.body().contains("DEV AUTH · DEBUG"));
      assertTrue(response.body().contains("not_found"));
      assertTrue(response.body().contains("Request ID"));
    }
  }

  @Test
  void given_developmentFailureRoute_when_requestingRoute_then_returnsDiagnosticHtml()
      throws Exception {
    try (var app =
        App.start(new String[] {}, Map.of("TOKTRAK_DEV_AUTH", "true", "TOKTRAK_PORT", "0"))) {
      var response = get(app, "/debug/error");
      assertEquals(500, response.statusCode());
      assertTrue(response.body().contains("Internal server error. Try again."));
      assertTrue(response.body().contains("java.lang.IllegalStateException: debug failure"));
      assertFalse(response.body().contains("at toktrak"));
    }
  }

  @Test
  void given_productionFailurePath_when_requestingRoute_then_returnsUsefulNotFoundHtml()
      throws Exception {
    try (var app =
        App.start(
            new String[] {},
            Map.of(
                "TOKTRAK_DATA_DIR",
                directory.toString(),
                "TOKTRAK_BASE_URL",
                "https://toktrak.test",
                "TOKTRAK_PORT",
                "0"))) {
      var response = get(app, "/debug/error");
      assertEquals(404, response.statusCode());
      assertTrue(response.body().contains("Route not found."));
      assertTrue(response.body().contains("<code>/debug/error</code>"));
      assertTrue(response.body().contains("Request ID"));
      assertFalse(response.body().contains("DEBUG"));
      assertFalse(response.body().contains("IllegalStateException"));
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
