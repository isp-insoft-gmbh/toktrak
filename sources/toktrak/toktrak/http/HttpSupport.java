package toktrak.http;

import com.sun.net.httpserver.HttpExchange;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public final class HttpSupport {
  public static final int MAX_REQUEST_BODY_BYTES = 5 * 1024 * 1024;
  private static final int BUFFER_BYTES = 8 * 1024;
  private static final int RESPONSE_BODY_BYTES_MAX = 5 * 1024 * 1024;

  private HttpSupport() {}

  public static byte[] readLimited(InputStream input, int limit) throws IOException {
    assert input != null;
    if (limit < 0 || limit > MAX_REQUEST_BODY_BYTES) {
      throw new IllegalArgumentException("limit must be 0.." + MAX_REQUEST_BODY_BYTES + " bytes");
    }
    var output = new ByteArrayOutputStream(Math.min(limit, BUFFER_BYTES));
    byte[] buffer = new byte[Math.min(BUFFER_BYTES, Math.max(1, Math.addExact(limit, 1)))];
    int readOperations = 0;
    int readOperationsMax = Math.addExact(limit, 1);
    while (readOperations < readOperationsMax) {
      int read = input.read(buffer);
      readOperations = Math.addExact(readOperations, 1);
      if (read < 0) {
        byte[] result = output.toByteArray();
        assert result.length <= limit;
        return result;
      }
      if (read == 0) throw new IOException("request body read made no progress");
      if (read > limit - output.size()) {
        throw new IllegalArgumentException("request body exceeds " + limit + " bytes");
      }
      output.write(buffer, 0, read);
    }
    throw new IOException("request body exceeded read operation limit");
  }

  public static void json(HttpExchange exchange, int status, String body) throws IOException {
    assert body != null;
    send(
        exchange, status, "application/json; charset=utf-8", body.getBytes(StandardCharsets.UTF_8));
  }

  public static void html(HttpExchange exchange, int status, String body) throws IOException {
    assert body != null;
    send(exchange, status, "text/html; charset=utf-8", body.getBytes(StandardCharsets.UTF_8));
  }

  private static void send(HttpExchange exchange, int status, String contentType, byte[] body)
      throws IOException {
    assert exchange != null;
    assert status >= 100 && status <= 599;
    assert contentType != null && !contentType.isBlank();
    assert body != null;
    if (body.length > RESPONSE_BODY_BYTES_MAX) {
      throw new IllegalArgumentException(
          "response body exceeds " + RESPONSE_BODY_BYTES_MAX + " bytes");
    }
    var headers = exchange.getResponseHeaders();
    assert headers != null;
    headers.set("Content-Type", contentType);
    headers.set("X-Content-Type-Options", "nosniff");
    headers.set("X-Frame-Options", "DENY");
    headers.set("Referrer-Policy", "no-referrer");
    headers.set(
        "Content-Security-Policy", "default-src 'self'; frame-ancestors 'none'; base-uri 'none'");
    exchange.sendResponseHeaders(status, body.length);
    try (var output = exchange.getResponseBody()) {
      output.write(body);
    }
    assert exchange.getResponseCode() == status;
  }
}
