package toktrak.tests;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import toktrak.*;

final class HttpServerTest {
  @Test
  void healthReturnsOkJsonAndSecurityHeaders() throws Exception {
    try (var app =
        App.start(new String[] {}, Map.of("TOKTRAK_DEV_AUTH", "true", "TOKTRAK_PORT", "0"))) {
      var response = get(app, "/health");
      assertEquals(200, response.statusCode());
      assertEquals(
          "nosniff", response.headers().firstValue("x-content-type-options").orElseThrow());
      assertEquals("DENY", response.headers().firstValue("x-frame-options").orElseThrow());
      assertTrue(response.body().contains("\"status\":\"ok\""));
    }
  }

  @Test
  void oversizedPathReturnsUriTooLong() throws Exception {
    try (var app =
        App.start(new String[] {}, Map.of("TOKTRAK_DEV_AUTH", "true", "TOKTRAK_PORT", "0"))) {
      var response = get(app, "/" + "a".repeat(2_049));
      assertEquals(414, response.statusCode());
    }
  }

  @Test
  void unknownApiRouteReturnsEnvelope() throws Exception {
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
  void unknownBrowserRouteReturnsBrutalHtml() throws Exception {
    try (var app =
        App.start(new String[] {}, Map.of("TOKTRAK_DEV_AUTH", "true", "TOKTRAK_PORT", "0"))) {
      var response = get(app, "/nope");
      assertEquals(404, response.statusCode());
      assertTrue(response.body().contains("<h1>404</h1>"));
      assertTrue(response.body().contains("BRUTAL ERROR"));
    }
  }

  private static HttpResponse<String> get(App app, String path) throws Exception {
    var request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + path))
            .timeout(Duration.ofSeconds(2))
            .GET()
            .build();
    var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    return client.send(request, BodyHandlers.ofString());
  }
}
