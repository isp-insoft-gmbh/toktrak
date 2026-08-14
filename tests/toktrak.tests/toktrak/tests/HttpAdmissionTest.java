package toktrak.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import toktrak.ClockSource;
import toktrak.auth.AuthService;
import toktrak.health.HealthState;
import toktrak.http.Assets;
import toktrak.http.Router;
import toktrak.identity.IdentityService;
import toktrak.projection.Projection;
import toktrak.store.EventLog;
import toktrak.store.Writer;
import toktrak.usage.UsageService;

final class HttpAdmissionTest {
  private static final int WORKER_COUNT = 64;
  private static final int QUEUE_CAPACITY = 256;
  @TempDir Path directory;

  @Test
  void
      given_saturatedProductionExecutor_when_requestingBrowserAndApiRoutes_then_returnsTypedServiceUnavailable()
          throws Exception {
    var releaseWorkers = new CountDownLatch(1);
    var workersStarted = new CountDownLatch(WORKER_COUNT);
    var workers =
        new ThreadPoolExecutor(
            WORKER_COUNT,
            WORKER_COUNT,
            0,
            TimeUnit.NANOSECONDS,
            new ArrayBlockingQueue<>(QUEUE_CAPACITY),
            Thread.ofVirtual().name("http-admission-test-", 0).factory(),
            new ThreadPoolExecutor.AbortPolicy());
    HttpServer server =
        HttpServer.create(new InetSocketAddress(InetAddress.ofLiteral("127.0.0.1"), 0), 128);
    var health = new HealthState();
    var projection = Projection.empty();
    try (var log = EventLog.open(directory.resolve("events.ndjson"));
        var writer = Writer.start(log, projection, health, ClockSource.system(), false)) {
      var identities = new IdentityService(writer, projection, new byte[32]);
      var auth =
          AuthService.development(
              URI.create("http://127.0.0.1"),
              ClockSource.system(),
              projection,
              identities,
              new byte[32]);
      server.createContext(
          "/",
          new Router(
              health,
              false,
              workers,
              Assets.load(),
              auth,
              new UsageService(writer, ClockSource.system()),
              projection));
      server.setExecutor(Runnable::run);
      for (int index = 0; index < WORKER_COUNT; index++) {
        workers.execute(
            () -> {
              workersStarted.countDown();
              try {
                releaseWorkers.await();
              } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
              }
            });
      }
      assertTrue(workersStarted.await(2, TimeUnit.SECONDS));
      for (int index = 0; index < QUEUE_CAPACITY; index++) workers.execute(() -> {});
      assertEquals(WORKER_COUNT, workers.getActiveCount());
      assertEquals(QUEUE_CAPACITY, workers.getQueue().size());
      server.start();

      var connection =
          (HttpURLConnection)
              URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/nope")
                  .toURL()
                  .openConnection();
      connection.setConnectTimeout(2_000);
      connection.setReadTimeout(2_000);
      connection.setRequestMethod("GET");
      connection.setRequestProperty("Connection", "close");
      try {
        assertEquals(503, connection.getResponseCode());
        assertEquals("text/html; charset=utf-8", connection.getHeaderField("Content-Type"));
        try (var input = connection.getErrorStream()) {
          String body = new String(input.readAllBytes(), StandardCharsets.UTF_8);
          assertTrue(body.contains("Server is busy. Try again."));
          assertTrue(body.contains("Request ID"));
        }
      } finally {
        connection.disconnect();
      }

      var apiConnection =
          (HttpURLConnection)
              URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/api/nope")
                  .toURL()
                  .openConnection();
      apiConnection.setConnectTimeout(2_000);
      apiConnection.setReadTimeout(2_000);
      apiConnection.setRequestMethod("GET");
      apiConnection.setRequestProperty("Connection", "close");
      try {
        assertEquals(503, apiConnection.getResponseCode());
        assertEquals(
            "application/json; charset=utf-8", apiConnection.getHeaderField("Content-Type"));
        try (var input = apiConnection.getErrorStream()) {
          assertTrue(
              new String(input.readAllBytes(), StandardCharsets.UTF_8)
                  .contains("\"code\":\"server_busy\""));
        }
      } finally {
        apiConnection.disconnect();
      }
    } finally {
      releaseWorkers.countDown();
      server.stop(0);
      workers.shutdownNow();
      assertTrue(workers.awaitTermination(2, TimeUnit.SECONDS));
    }
  }
}
