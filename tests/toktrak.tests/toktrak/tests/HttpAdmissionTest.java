package toktrak.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import toktrak.health.HealthState;
import toktrak.http.Router;

final class HttpAdmissionTest {
  private static final int WORKER_COUNT = 64;
  private static final int QUEUE_CAPACITY = 256;

  @Test
  void given_saturatedProductionExecutor_when_requestingHealth_then_returnsServiceUnavailable()
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
    server.createContext("/", new Router(new HealthState(), true, workers));
    server.setExecutor(Runnable::run);
    try {
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
              URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/health")
                  .toURL()
                  .openConnection();
      connection.setConnectTimeout(2_000);
      connection.setReadTimeout(2_000);
      connection.setRequestMethod("GET");
      connection.setRequestProperty("Connection", "close");
      try {
        assertEquals(503, connection.getResponseCode());
        try (var input = connection.getErrorStream()) {
          assertTrue(input.readAllBytes().length > 0);
        }
      } finally {
        connection.disconnect();
      }
    } finally {
      releaseWorkers.countDown();
      server.stop(0);
      workers.shutdownNow();
      assertTrue(workers.awaitTermination(2, TimeUnit.SECONDS));
    }
  }
}
