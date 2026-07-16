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
        return new Response(statusCode, new String(input.readAllBytes(), StandardCharsets.UTF_8));
      }
    } finally {
      connection.disconnect();
    }
  }

  private record Response(int statusCode, String body) {
    private Response {
      assert statusCode >= 100 && statusCode <= 599;
      assert body != null;
    }
  }
}
