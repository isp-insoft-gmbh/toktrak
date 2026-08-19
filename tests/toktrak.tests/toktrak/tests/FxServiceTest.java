package toktrak.tests;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import toktrak.ClockSource;
import toktrak.fx.FxClient;
import toktrak.fx.FxService;
import toktrak.health.HealthState;
import toktrak.projection.Projection;
import toktrak.store.EventLog;
import toktrak.store.Writer;

final class FxServiceTest {
  @TempDir Path directory;

  @Test
  void given_successThenFxFailure_when_refreshing_then_persistsAndRetainsLastGoodRate()
      throws Exception {
    var fail = new AtomicBoolean();
    HttpServer server =
        HttpServer.create(new InetSocketAddress(InetAddress.ofLiteral("127.0.0.1"), 0), 8);
    server.createContext(
        "/latest",
        exchange -> {
          byte[] body =
              (fail.get() ? "unavailable" : "{\"date\":\"2026-07-14\",\"rates\":{\"EUR\":0.92}}")
                  .getBytes(StandardCharsets.UTF_8);
          int status = fail.get() ? 503 : 200;
          exchange.sendResponseHeaders(status, body.length);
          try (var output = exchange.getResponseBody()) {
            output.write(body);
          }
        });
    server.start();
    var client =
        new FxClient(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/latest"));
    var projection = Projection.empty();
    var health = new HealthState();
    try (var log = EventLog.open(directory.resolve("events.ndjson"));
        var writer =
            Writer.start(
                log, projection, health, ClockSource.fixed(Instant.parse("2026-07-15T00:00:00Z")));
        var service = FxService.start(writer, client, Duration.ZERO, Duration.ofMillis(100))) {
      assertNotNull(service);
      assertDoesNotThrow(
          () ->
              new FxClient.Rate(java.time.LocalDate.parse("2026-07-14"), java.math.BigDecimal.TEN));
      assertThrows(
          IllegalArgumentException.class,
          () ->
              new FxClient.Rate(
                  java.time.LocalDate.parse("2026-07-14"), new java.math.BigDecimal("10.01")));
      assertThrows(
          IllegalArgumentException.class,
          () -> FxService.start(writer, client, Duration.ZERO, Duration.ZERO));
      assertThrows(
          IllegalArgumentException.class,
          () -> FxService.start(writer, client, Duration.ofHours(25), Duration.ofMillis(100)));
      awaitRate(projection);
      fail.set(true);
      Thread.sleep(250);

      assertEquals("0.92", projection.fxRate().orElseThrow().eurPerUsd().toPlainString());
      assertEquals("2026-07-14", projection.fxRate().orElseThrow().date().toString());
      assertTrue(health.healthy());
    } finally {
      server.stop(0);
    }
  }

  private static void awaitRate(Projection projection) throws InterruptedException {
    long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
    while (projection.fxRate().isEmpty() && System.nanoTime() < deadline) Thread.sleep(10);
    assertTrue(projection.fxRate().isPresent());
  }
}
