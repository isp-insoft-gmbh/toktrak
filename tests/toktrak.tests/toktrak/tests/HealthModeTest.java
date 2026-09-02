package toktrak.tests;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import toktrak.*;

final class HealthModeTest {
  private static final int RAW_RESPONSE_BYTES_MAX = 128 * 1024;
  private static final Pattern STYLESHEET =
      Pattern.compile("<link rel=\\\"stylesheet\\\" href=\\\"([^\\\"]+)\\\"");
  @TempDir Path directory;

  @Test
  void given_failWritesMode_when_startingApp_then_reportsDegradedHealth() throws Exception {
    try (var app = App.start(new String[] {"--fail-writes"}, devEnvironment())) {
      var response = get(app, "/health");
      assertEquals(503, response.statusCode());
      assertTrue(response.body().contains("\"status\":\"degraded\""));
      assertTrue(response.body().contains("\"reason\":\"writes_failed\""));
    }
  }

  @Test
  void given_devAuthMode_when_requestingRoot_then_returnsDevAuthStrip() throws Exception {
    try (var app = App.start(new String[] {}, devEnvironment())) {
      var response = get(app, "/");
      assertEquals(200, response.statusCode());
      assertTrue(response.body().contains("DEV AUTH"));
      assertTrue(stylesheetUrl(response).matches("/assets/main\\.[0-9a-f]{32}\\.css"));
      assertTrue(
          response
              .body()
              .contains("<div class=\"environment-banner\" role=\"status\">DEV AUTH</div>"));
      assertFalse(response.body().contains("<div style="));
    }
  }

  @Test
  void given_fingerprintedStylesheet_when_requestingAsset_then_returnsImmutableVerifiedBytes()
      throws Exception {
    try (var app = App.start(new String[] {}, devEnvironment())) {
      String assetUrl = stylesheetUrl(get(app, "/"));
      var response = get(app, assetUrl);
      String expected = Files.readString(Path.of("sources/toktrak/assets/public/main.css"));

      assertEquals(200, response.statusCode());
      assertEquals(expected, response.body());
      assertEquals("text/css; charset=utf-8", response.header("Content-Type"));
      assertEquals("public, max-age=31536000, immutable", response.header("Cache-Control"));
      assertEquals("same-origin", response.header("Cross-Origin-Resource-Policy"));
      assertEquals("nosniff", response.header("X-Content-Type-Options"));
      assertEquals(
          Integer.toString(expected.getBytes(StandardCharsets.UTF_8).length),
          response.header("Content-Length"));
      assertEquals(
          "default-src 'self'; frame-ancestors 'none'; base-uri 'none'",
          response.header("Content-Security-Policy"));
      assertEquals("DENY", response.header("X-Frame-Options"));
      assertEquals("no-referrer", response.header("Referrer-Policy"));
    }
  }

  @Test
  void given_noncanonicalAssetRequests_when_requestingRoutes_then_returnsBrowserNotFound()
      throws Exception {
    try (var app = App.start(new String[] {}, devEnvironment())) {
      String assetUrl = stylesheetUrl(get(app, "/"));
      int fingerprintStart = "/assets/main.".length();
      char replacement = assetUrl.charAt(fingerprintStart) == '0' ? '1' : '0';
      String wrongHash =
          assetUrl.substring(0, fingerprintStart)
              + replacement
              + assetUrl.substring(fingerprintStart + 1);

      for (Response response :
          List.of(
              get(app, "/assets/main.css"),
              get(app, wrongHash),
              request(app, assetUrl, "POST"),
              get(app, "/assets/private/tracker.mjs"))) {
        assertEquals(404, response.statusCode());
        assertTrue(response.body().contains("Route not found."));
        assertTrue(response.body().contains("DEV AUTH · DEBUG"));
      }
    }
  }

  @Test
  void given_noncanonicalAssetTargets_when_requestingRawHttp_then_returnsNotFound()
      throws Exception {
    try (var app = App.start(new String[] {}, devEnvironment())) {
      String assetUrl = stylesheetUrl(get(app, "/"));
      String fileName = assetUrl.substring("/assets/".length());
      for (String target :
          List.of(
              "/assets/%6d" + fileName.substring(1),
              "/assets/./" + fileName,
              "/assets//" + fileName,
              assetUrl + "?cache-bust=1",
              assetUrl + "?")) {
        assertEquals(404, rawStatus(app, target), target);
      }
      assertEquals(200, rawStatus(app, assetUrl));
    }
  }

  private Map<String, String> devEnvironment() {
    return Map.of(
        "TOKTRAK_DEV_AUTH", "true",
        "TOKTRAK_PORT", "0",
        "TOKTRAK_DATA_DIR", directory.toString());
  }

  private static String stylesheetUrl(Response response) {
    var matcher = STYLESHEET.matcher(response.body());
    assertTrue(matcher.find(), response.body());
    String result = matcher.group(1);
    assertFalse(matcher.find(), response.body());
    return result;
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
            connection.getHeaderFields(),
            new String(input.readAllBytes(), StandardCharsets.UTF_8));
      }
    } finally {
      connection.disconnect();
    }
  }

  private static int rawStatus(App app, String target) throws Exception {
    try (var socket = new Socket(InetAddress.ofLiteral("127.0.0.1"), app.port())) {
      socket.setSoTimeout(2_000);
      String request =
          "GET "
              + target
              + " HTTP/1.1\r\n"
              + "Host: 127.0.0.1:"
              + app.port()
              + "\r\n"
              + "Connection: close\r\n"
              + "\r\n";
      socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
      socket.getOutputStream().flush();
      var response = new ByteArrayOutputStream();
      byte[] buffer = new byte[4 * 1024];
      int readOperations = 0;
      boolean eof = false;
      while (response.size() <= RAW_RESPONSE_BYTES_MAX
          && readOperations <= RAW_RESPONSE_BYTES_MAX) {
        int read = socket.getInputStream().read(buffer);
        readOperations = Math.addExact(readOperations, 1);
        if (read < 0) {
          eof = true;
          break;
        }
        if (read == 0) fail("raw HTTP response read made no progress");
        response.write(buffer, 0, read);
      }
      assertTrue(eof, "raw HTTP response did not reach EOF");
      assertTrue(response.size() <= RAW_RESPONSE_BYTES_MAX, "raw HTTP response exceeded limit");
      String raw = response.toString(StandardCharsets.ISO_8859_1);
      int lineEnd = raw.indexOf("\r\n");
      assertTrue(lineEnd > 0, raw);
      String[] statusLine = raw.substring(0, lineEnd).split(" ", 3);
      assertEquals(3, statusLine.length, raw);
      return Integer.parseInt(statusLine[1]);
    }
  }

  private record Response(int statusCode, Map<String, List<String>> headers, String body) {
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
