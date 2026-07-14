package toktrak.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
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
  void saturatedProductionSizedExecutorReturnsServiceUnavailable() throws Exception {
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
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 128);
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

      var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
      var request =
          HttpRequest.newBuilder(
                  URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/health"))
              .timeout(Duration.ofSeconds(2))
              .GET()
              .build();
      var response = client.send(request, BodyHandlers.ofString());
      assertEquals(503, response.statusCode());
    } finally {
      releaseWorkers.countDown();
      server.stop(0);
      workers.shutdownNow();
      assertTrue(workers.awaitTermination(2, TimeUnit.SECONDS));
    }
  }
}
