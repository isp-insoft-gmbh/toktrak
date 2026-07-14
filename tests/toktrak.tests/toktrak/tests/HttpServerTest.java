package toktrak.tests;

import toktrak.*;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class HttpServerTest {
  @Test
  void healthReturnsOkJsonAndSecurityHeaders() throws Exception {
    try (var app = App.start(new String[] {}, Map.of("TOKTRAK_DEV_AUTH", "true", "TOKTRAK_PORT", "0"))) {
      var res = get(app, "/health");
      assertEquals(200, res.statusCode());
      assertEquals("nosniff", res.headers().firstValue("x-content-type-options").orElseThrow());
      assertEquals("DENY", res.headers().firstValue("x-frame-options").orElseThrow());
      assertTrue(res.body().contains("\"status\":\"ok\""));
    }
  }

  @Test
  void unknownApiRouteReturnsEnvelope() throws Exception {
    try (var app = App.start(new String[] {}, Map.of("TOKTRAK_DEV_AUTH", "true", "TOKTRAK_PORT", "0"))) {
      var res = get(app, "/api/nope");
      assertEquals(404, res.statusCode());
      assertTrue(res.body().contains("\"error\""));
      assertTrue(res.body().contains("\"code\":\"not_found\""));
      assertTrue(res.body().contains("\"requestId\""));
    }
  }

  @Test
  void unknownBrowserRouteReturnsBrutalHtml() throws Exception {
    try (var app = App.start(new String[] {}, Map.of("TOKTRAK_DEV_AUTH", "true", "TOKTRAK_PORT", "0"))) {
      var res = get(app, "/nope");
      assertEquals(404, res.statusCode());
      assertTrue(res.body().contains("<h1>404</h1>"));
      assertTrue(res.body().contains("BRUTAL ERROR"));
    }
  }

  private static HttpResponse<String> get(App app, String path) throws Exception {
    var req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + path)).GET().build();
    return HttpClient.newHttpClient().send(req, BodyHandlers.ofString());
  }
}
