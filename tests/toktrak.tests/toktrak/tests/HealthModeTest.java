package toktrak.tests;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import toktrak.*;

final class HealthModeTest {
  @Test
  void failWritesStartsAfterBindingAndReportsDegraded() throws Exception {
    try (var app =
        App.start(
            new String[] {"--fail-writes"},
            Map.of("TOKTRAK_DEV_AUTH", "true", "TOKTRAK_PORT", "0"))) {
      var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
      var request =
          HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/health"))
              .timeout(Duration.ofSeconds(2))
              .GET()
              .build();
      var response = client.send(request, BodyHandlers.ofString());
      assertEquals(503, response.statusCode());
      assertTrue(response.body().contains("\"status\":\"degraded\""));
      assertTrue(response.body().contains("\"reason\":\"writes_failed\""));
    }
  }

  @Test
  void rootShowsDevAuthStrip() throws Exception {
    try (var app =
        App.start(new String[] {}, Map.of("TOKTRAK_DEV_AUTH", "true", "TOKTRAK_PORT", "0"))) {
      var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
      var request =
          HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/"))
              .timeout(Duration.ofSeconds(2))
              .GET()
              .build();
      var response = client.send(request, BodyHandlers.ofString());
      assertEquals(200, response.statusCode());
      assertTrue(response.body().contains("DEV AUTH"));
    }
  }
}
