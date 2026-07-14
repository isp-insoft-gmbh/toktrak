package toktrak.http;

import com.sun.net.httpserver.HttpExchange;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public final class HttpSupport {
  private HttpSupport() {}

  public static byte[] readLimited(InputStream input, int limit) throws IOException {
    if (limit < 0) throw new IllegalArgumentException("limit must not be negative");
    var output = new ByteArrayOutputStream(Math.min(limit, 8192));
    byte[] buffer = new byte[Math.min(8192, Math.max(1, limit + 1))];
    while (true) {
      int read = input.read(buffer);
      if (read < 0) return output.toByteArray();
      if (output.size() + read > limit) throw new IllegalArgumentException("request body exceeds " + limit + " bytes");
      output.write(buffer, 0, read);
    }
  }

  public static void json(HttpExchange exchange, int status, String body) throws IOException {
    send(exchange, status, "application/json; charset=utf-8", body.getBytes(StandardCharsets.UTF_8));
  }

  public static void html(HttpExchange exchange, int status, String body) throws IOException {
    send(exchange, status, "text/html; charset=utf-8", body.getBytes(StandardCharsets.UTF_8));
  }

  private static void send(HttpExchange exchange, int status, String contentType, byte[] body) throws IOException {
    var headers = exchange.getResponseHeaders();
    headers.set("Content-Type", contentType);
    headers.set("X-Content-Type-Options", "nosniff");
    headers.set("X-Frame-Options", "DENY");
    headers.set("Referrer-Policy", "no-referrer");
    headers.set("Content-Security-Policy", "default-src 'self'; frame-ancestors 'none'; base-uri 'none'");
    exchange.sendResponseHeaders(status, body.length);
    try (var output = exchange.getResponseBody()) {
      output.write(body);
    }
  }
}
