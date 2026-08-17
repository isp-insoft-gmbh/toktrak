package toktrak.http;

import com.sun.net.httpserver.HttpExchange;
import io.jstach.jstachio.Output;
import io.jstach.jstachio.Template;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

public final class HttpSupport {
  public static final int MAX_REQUEST_BODY_BYTES = 5 * 1024 * 1024;
  private static final int BUFFER_BYTES = 8 * 1024;
  private static final int RESPONSE_BODY_BYTES_MAX = 5 * 1024 * 1024;
  public static final int ENCODED_HTML_BYTES_MAX = 4 * 1024 * 1024;

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

  public static <T> byte[] renderEncoded(Template.EncodedTemplate<T> renderer, T model)
      throws IOException {
    assert renderer != null;
    assert model != null;
    byte[] result;
    try (var output = new BoundedOutputStream(ENCODED_HTML_BYTES_MAX)) {
      renderer.write(model, Output.of(output, StandardCharsets.UTF_8));
      result = output.toByteArray();
    }
    assert result.length <= ENCODED_HTML_BYTES_MAX;
    return result;
  }

  public static void encodedHtml(HttpExchange exchange, int status, byte[] body)
      throws IOException {
    assert body != null;
    if (body.length > ENCODED_HTML_BYTES_MAX) {
      throw new IllegalArgumentException(
          "encoded HTML exceeds " + ENCODED_HTML_BYTES_MAX + " bytes");
    }
    send(exchange, status, "text/html; charset=utf-8", body.clone());
  }

  public static void redirect(HttpExchange exchange, int status, URI location) throws IOException {
    assert exchange != null;
    assert status == 302 || status == 303;
    assert location != null;
    exchange.getResponseHeaders().set("Location", location.toString());
    send(exchange, status, "text/plain; charset=utf-8", new byte[0]);
  }

  public static void eventStream(HttpExchange exchange, String body) throws IOException {
    assert exchange != null;
    assert body != null;
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    if (bytes.length > 64 * 1024) throw new IllegalArgumentException("SSE body is too large");
    var headers = exchange.getResponseHeaders();
    headers.set("Content-Type", "text/event-stream; charset=utf-8");
    headers.set("Cache-Control", "no-store");
    headers.set("X-Content-Type-Options", "nosniff");
    headers.set("X-Frame-Options", "DENY");
    headers.set("Referrer-Policy", "no-referrer");
    headers.set(
        "Content-Security-Policy", "default-src 'self'; frame-ancestors 'none'; base-uri 'none'");
    exchange.sendResponseHeaders(200, 0);
    try (var output = exchange.getResponseBody()) {
      output.write(bytes);
    }
    assert exchange.getResponseCode() == 200;
  }

  public static void asset(HttpExchange exchange, String contentType, byte[] body)
      throws IOException {
    assert exchange != null;
    assert contentType != null && !contentType.isBlank();
    assert body != null;
    exchange.getResponseHeaders().set("Cache-Control", "public, max-age=31536000, immutable");
    exchange.getResponseHeaders().set("Cross-Origin-Resource-Policy", "same-origin");
    send(exchange, 200, contentType, body);
  }

  public static void trackerScript(HttpExchange exchange, byte[] body, String sha256)
      throws IOException {
    assert exchange != null;
    assert body != null;
    assert sha256 != null && sha256.matches("[0-9a-f]{64}");
    exchange.getResponseHeaders().set("X-TokTrak-SHA256", sha256);
    send(exchange, 200, "text/javascript; charset=utf-8", body.clone());
  }

  private static final class BoundedOutputStream extends OutputStream {
    private final int limit;
    private final ByteArrayOutputStream output;

    private BoundedOutputStream(int limit) {
      this.limit = limit;
      output = new ByteArrayOutputStream(Math.min(limit, BUFFER_BYTES));
    }

    @Override
    public void write(int value) throws IOException {
      requireCapacity(1);
      output.write(value);
    }

    @Override
    public void write(byte[] bytes, int offset, int length) throws IOException {
      Objects.requireNonNull(bytes, "bytes");
      if (offset < 0 || length < 0 || offset > bytes.length - length) {
        throw new IndexOutOfBoundsException();
      }
      requireCapacity(length);
      output.write(bytes, offset, length);
    }

    private void requireCapacity(int additional) throws IOException {
      if (additional > limit - output.size()) {
        throw new EncodedHtmlTooLargeException();
      }
    }

    private byte[] toByteArray() {
      return output.toByteArray();
    }
  }

  static final class EncodedHtmlTooLargeException extends IOException {
    private static final long serialVersionUID = 1L;

    private EncodedHtmlTooLargeException() {
      super("encoded HTML exceeds " + ENCODED_HTML_BYTES_MAX + " bytes", null);
    }
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
    if (!headers.containsKey("Cache-Control")) headers.set("Cache-Control", "no-store");
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
