package toktrak.tests;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import toktrak.http.HttpSupport;

final class HttpSupportTest {
  private static final int EVENT_STREAM_BYTES_MAX = 64 * 1024;

  @Test
  void given_eventStream_when_sending_then_setsNoStoreAndSecurityHeaders() throws Exception {
    String body = "event: datastar-patch-signals\ndata: signals {}\n\n";
    HttpResponse<byte[]> response = serve(exchange -> HttpSupport.eventStream(exchange, body));
    assertEquals(200, response.statusCode());
    assertEquals(body, new String(response.body(), StandardCharsets.UTF_8));
    assertEquals("text/event-stream; charset=utf-8", header(response, "Content-Type"));
    assertEquals("no-store", header(response, "Cache-Control"));
    assertEquals("nosniff", header(response, "X-Content-Type-Options"));
    assertEquals("DENY", header(response, "X-Frame-Options"));
    assertEquals("no-referrer", header(response, "Referrer-Policy"));
    assertEquals(
        "default-src 'self'; frame-ancestors 'none'; base-uri 'none'",
        header(response, "Content-Security-Policy"));
  }

  @Test
  void given_eventStreamBodyBoundary_when_sending_then_acceptsLimitAndRejectsOverflow()
      throws Exception {
    String atLimit = text(EVENT_STREAM_BYTES_MAX);
    HttpResponse<byte[]> accepted = serve(exchange -> HttpSupport.eventStream(exchange, atLimit));
    assertEquals(200, accepted.statusCode());
    assertEquals(EVENT_STREAM_BYTES_MAX, accepted.body().length);

    String overLimit = text(EVENT_STREAM_BYTES_MAX + 1);
    HttpResponse<byte[]> rejected = serve(exchange -> HttpSupport.eventStream(exchange, overLimit));
    assertEquals(500, rejected.statusCode());
    assertEquals("SSE body is too large", new String(rejected.body(), StandardCharsets.UTF_8));
    assertTrue(
        rejected.headers().firstValue("Content-Type").isEmpty(),
        rejected.headers().map()::toString);
  }

  @Test
  void given_encodedHtmlBoundary_when_sending_then_acceptsLimitAndRejectsOverflow()
      throws Exception {
    byte[] atLimit = bytes(HttpSupport.ENCODED_HTML_BYTES_MAX);
    HttpResponse<byte[]> accepted =
        serve(exchange -> HttpSupport.encodedHtml(exchange, 200, atLimit));
    assertEquals(200, accepted.statusCode());
    assertEquals("text/html; charset=utf-8", header(accepted, "Content-Type"));
    assertEquals(HttpSupport.ENCODED_HTML_BYTES_MAX, accepted.body().length);

    byte[] overLimit = bytes(HttpSupport.ENCODED_HTML_BYTES_MAX + 1);
    HttpResponse<byte[]> rejected =
        serve(exchange -> HttpSupport.encodedHtml(exchange, 200, overLimit));
    assertEquals(500, rejected.statusCode());
    assertEquals(
        "encoded HTML exceeds " + HttpSupport.ENCODED_HTML_BYTES_MAX + " bytes",
        new String(rejected.body(), StandardCharsets.UTF_8));
  }

  private static String header(HttpResponse<?> response, String name) {
    return response.headers().firstValue(name).orElseThrow();
  }

  private static String text(int length) {
    return new String(bytes(length), StandardCharsets.UTF_8);
  }

  private static byte[] bytes(int length) {
    byte[] result = new byte[length];
    Arrays.fill(result, (byte) 'x');
    return result;
  }

  // Serves one request through the handler; a rejected response carries the exception message
  // as a 500 so the test observes the validation failure instead of a hung connection.
  private static HttpResponse<byte[]> serve(Handler handler) throws Exception {
    var failure = new AtomicReference<RuntimeException>();
    HttpServer server =
        HttpServer.create(new InetSocketAddress(InetAddress.ofLiteral("127.0.0.1"), 0), 1);
    server.createContext(
        "/",
        exchange -> {
          try {
            handler.handle(exchange);
          } catch (IllegalArgumentException exception) {
            failure.set(exception);
            byte[] message = exception.getMessage().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(500, message.length);
            try (var output = exchange.getResponseBody()) {
              output.write(message);
            }
          }
        });
    server.start();
    try {
      URI uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
      HttpResponse<byte[]> response =
          HttpClient.newHttpClient()
              .send(
                  HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10)).GET().build(),
                  HttpResponse.BodyHandlers.ofByteArray());
      assertEquals(response.statusCode() == 500, failure.get() != null);
      return response;
    } finally {
      server.stop(0);
    }
  }

  @FunctionalInterface
  private interface Handler {
    void handle(HttpExchange exchange) throws IOException;
  }
}
