package toktrak.tests;

import static org.junit.jupiter.api.Assertions.*;

import java.net.HttpURLConnection;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import toktrak.*;

final class HttpServerTest {
  @TempDir Path directory;

  @Test
  void given_developmentApp_when_startingServer_then_bindsLoopback() {
    try (var app = App.start(new String[] {}, devEnvironment())) {
      assertTrue(app.bindAddress().isLoopbackAddress());
    }
  }

  @Tag("network")
  @Test
  void
      given_nonLoopbackAuthority_when_requestingDevelopmentLogin_then_rejectsBeforeAutomaticAuthentication()
          throws Exception {
    try (var app = App.start(new String[] {}, devEnvironment())) {
      String response;
      try (var socket = new Socket(app.bindAddress(), app.port())) {
        socket.setSoTimeout(2_000);
        String request =
            "GET /login HTTP/1.1\r\n"
                + "Host: rebinding.invalid:"
                + app.port()
                + "\r\nConnection: close\r\n\r\n";
        socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
        socket.shutdownOutput();
        response = new String(socket.getInputStream().readAllBytes(), StandardCharsets.US_ASCII);
      }

      assertTrue(response.startsWith("HTTP/1.1 421 "), response);
      assertFalse(response.toLowerCase(Locale.ROOT).contains("\r\nset-cookie:"), response);
      assertTrue(app.projection().users().isEmpty());
      assertEquals(200, get(app, "/health").statusCode());
    }
  }

  @Test
  void given_productionApp_when_startingServer_then_bindsContainerInterface() {
    try (var app = App.start(new String[] {}, productionEnvironment(directory))) {
      assertTrue(app.bindAddress().isAnyLocalAddress());
    }
  }

  @Test
  void given_healthyApp_when_requestingHealth_then_returnsJsonAndSecurityHeaders()
      throws Exception {
    try (var app = App.start(new String[] {}, devEnvironment())) {
      var response = get(app, "/health");
      assertEquals(200, response.statusCode());
      assertEquals("nosniff", response.header("x-content-type-options"));
      assertEquals("DENY", response.header("x-frame-options"));
      assertEquals("{\"status\":\"ok\"}", response.body());
    }
  }

  @Test
  void given_methodAboveLengthLimit_when_requestingBrowserRoute_then_returnsDiagnosticBadRequest()
      throws Exception {
    try (var app = App.start(new String[] {}, devEnvironment())) {
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
    try (var app = App.start(new String[] {}, devEnvironment())) {
      var response = get(app, "/" + "a".repeat(2_049));
      assertEquals(414, response.statusCode());
      assertEquals("text/html; charset=utf-8", response.header("content-type"));
      assertTrue(response.body().contains("Request URI is too long."));
      assertTrue(response.body().contains("URI exceeds 2048-byte path or 8192-byte query limit"));
    }
  }

  @Test
  void given_queryAtAndAboveLengthLimit_when_requestingPublicRoute_then_enforcesLimit()
      throws Exception {
    try (var app = App.start(new String[] {}, devEnvironment())) {
      String queryAtLimit = "probe=" + "a".repeat(8 * 1024 - "probe=".length());
      assertEquals(200, get(app, "/health?" + queryAtLimit).statusCode());

      var rejected = get(app, "/health?" + queryAtLimit + "a");
      assertEquals(414, rejected.statusCode());
      assertEquals("application/json; charset=utf-8", rejected.header("content-type"));
      assertTrue(rejected.body().contains("\"code\":\"uri_too_long\""));
    }
  }

  @Test
  void given_appStart_when_configuringHttpServer_then_setsSupportedAdmissionProperties() {
    Map<String, String> expected =
        Map.of(
            "jdk.httpserver.maxConnections", "384",
            "sun.net.httpserver.maxIdleConnections", "64",
            "sun.net.httpserver.maxReqHeaders", "64",
            "sun.net.httpserver.maxReqHeaderSize", "32768",
            "sun.net.httpserver.maxReqTime", "30",
            "sun.net.httpserver.maxRspTime", "60");
    var previous = new java.util.HashMap<String, String>();
    for (String name : expected.keySet()) {
      previous.put(name, System.getProperty(name));
      System.clearProperty(name);
    }
    try (var _ = App.start(new String[] {}, devEnvironment())) {
      for (var entry : expected.entrySet()) {
        assertEquals(entry.getValue(), System.getProperty(entry.getKey()));
      }
    } finally {
      for (var entry : previous.entrySet()) {
        if (entry.getValue() == null) System.clearProperty(entry.getKey());
        else System.setProperty(entry.getKey(), entry.getValue());
      }
    }
  }

  @Tag("network")
  @Test
  void given_oversizedRequestHeader_when_reachingHttpServer_then_rejectsBeforeApplicationRouting()
      throws Exception {
    try (var app = App.start(new String[] {}, devEnvironment())) {
      try (var socket = new Socket(app.bindAddress(), app.port())) {
        socket.setSoTimeout(2_000);
        String request =
            "GET /health HTTP/1.1\r\n"
                + "Host: 127.0.0.1\r\n"
                + "X-Padding: "
                + "a".repeat(32 * 1024)
                + "\r\nConnection: close\r\n\r\n";
        socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
        socket.shutdownOutput();
        assertTrue(closedWithoutResponse(socket));
      }
      assertEquals(200, get(app, "/health").statusCode());
    }
  }

  @Test
  void given_unknownApiRoute_when_requestingRoute_then_returnsNotFoundEnvelope() throws Exception {
    try (var app = App.start(new String[] {}, devEnvironment())) {
      var response = get(app, "/api/nope");
      assertEquals(404, response.statusCode());
      assertTrue(response.body().startsWith("{\"error\":{"), response.body());
      assertTrue(response.body().contains("\"code\":\"not_found\""), response.body());
      assertTrue(response.body().contains("\"message\":\"route not found\""), response.body());
      assertTrue(response.body().matches(".*\"requestId\":\"[0-9a-f-]{36}\".*"), response.body());
    }
  }

  @Test
  void given_oversizedApiRoute_when_requestingRoute_then_returnsBoundedErrorEnvelope()
      throws Exception {
    try (var app = App.start(new String[] {}, devEnvironment())) {
      var response = get(app, "/api/" + "x".repeat(2_050));
      assertEquals(414, response.statusCode());
      assertTrue(response.body().contains("\"code\":\"uri_too_long\""));
    }
  }

  @Test
  void given_unknownDevelopmentBrowserRoute_when_requestingRoute_then_returnsDiagnosticHtml()
      throws Exception {
    try (var app = App.start(new String[] {}, devEnvironment())) {
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
    try (var app = App.start(new String[] {}, devEnvironment())) {
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
    try (var app = App.start(new String[] {}, productionEnvironment(directory))) {
      var response = get(app, "/debug/error");
      assertEquals(404, response.statusCode());
      assertTrue(response.body().contains("Route not found."));
      assertTrue(response.body().contains("<code>/debug/error</code>"));
      assertTrue(response.body().contains("Request ID"));
      assertFalse(response.body().contains("DEBUG"));
      assertFalse(response.body().contains("IllegalStateException"));
    }
  }

  private Map<String, String> devEnvironment() {
    return Map.of(
        "TOKTRAK_DEV_AUTH", "true",
        "TOKTRAK_PORT", "0",
        "TOKTRAK_DATA_DIR", directory.toString());
  }

  private static Map<String, String> productionEnvironment(Path directory) {
    String secret = java.util.Base64.getEncoder().encodeToString(new byte[32]);
    return Map.of(
        "TOKTRAK_DATA_DIR", directory.toString(),
        "TOKTRAK_BASE_URL", "https://toktrak.test",
        "TOKTRAK_PORT", "0",
        "TOKTRAK_OIDC_DISCOVERY_URL", "https://accounts.example/.well-known/openid-configuration",
        "TOKTRAK_OIDC_CLIENT_ID", "client",
        "TOKTRAK_OIDC_CLIENT_SECRET", "secret",
        "TOKTRAK_ALLOWED_DOMAIN", "example.com",
        "TOKTRAK_SESSION_SECRET", secret,
        "TOKTRAK_TOKEN_PEPPER", secret);
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

  private static boolean closedWithoutResponse(Socket socket) throws java.io.IOException {
    assertNotNull(socket);
    try {
      return socket.getInputStream().read() < 0;
    } catch (java.net.SocketException exception) {
      return true;
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
