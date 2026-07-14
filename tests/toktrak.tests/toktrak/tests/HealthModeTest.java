package toktrak.tests;

import toktrak.*;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class HealthModeTest {
  @Test
  void failWritesStartsAfterBindingAndReportsDegraded() throws Exception {
    try (var app = App.start(new String[] {"--fail-writes"}, Map.of("TOKTRAK_DEV_AUTH", "true", "TOKTRAK_PORT", "0"))) {
      var res = HttpClient.newHttpClient().send(
          HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/health")).GET().build(),
          BodyHandlers.ofString());
      assertEquals(503, res.statusCode());
      assertTrue(res.body().contains("\"status\":\"degraded\""));
      assertTrue(res.body().contains("\"reason\":\"writes_failed\""));
    }
  }

  @Test
  void rootShowsDevAuthStrip() throws Exception {
    try (var app = App.start(new String[] {}, Map.of("TOKTRAK_DEV_AUTH", "true", "TOKTRAK_PORT", "0"))) {
      var res = HttpClient.newHttpClient().send(
          HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/")).GET().build(),
          BodyHandlers.ofString());
      assertEquals(200, res.statusCode());
      assertTrue(res.body().contains("DEV AUTH"));
    }
  }
}
