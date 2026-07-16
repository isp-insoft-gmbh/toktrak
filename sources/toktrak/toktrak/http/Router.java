package toktrak.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import toktrak.health.HealthState;
import toktrak.json.Json;

public final class Router implements HttpHandler {
  private static final int PATH_BYTES_MAX = 2 * 1024;
  private static final int METHOD_CHARACTERS_MAX = 32;

  private final HealthState health;
  private final boolean devAuth;
  private final Executor requestExecutor;

  public Router(HealthState health, boolean devAuth, Executor requestExecutor) {
    assert health != null;
    assert requestExecutor != null;
    this.health = health;
    this.devAuth = devAuth;
    this.requestExecutor = requestExecutor;
  }

  @Override
  public void handle(HttpExchange exchange) throws IOException {
    assert exchange != null;
    String method = exchange.getRequestMethod();
    if (method == null || method.isBlank() || method.length() > METHOD_CHARACTERS_MAX) {
      try {
        HttpSupport.json(
            exchange,
            400,
            new ApiError(
                    "invalid_method", "request method is invalid", UUID.randomUUID().toString())
                .json());
      } finally {
        exchange.close();
      }
      return;
    }
    String rawPath = exchange.getRequestURI().getRawPath();
    String path = exchange.getRequestURI().getPath();
    if (exceedsPathLimit(rawPath) || exceedsPathLimit(path)) {
      try {
        HttpSupport.json(
            exchange,
            414,
            new ApiError("uri_too_long", "request URI is too long", UUID.randomUUID().toString())
                .json());
      } finally {
        exchange.close();
      }
      return;
    }
    try {
      requestExecutor.execute(() -> handleAccepted(exchange));
    } catch (RejectedExecutionException exception) {
      try {
        HttpSupport.json(
            exchange,
            503,
            new ApiError("server_busy", "server is busy", UUID.randomUUID().toString()).json());
      } finally {
        exchange.close();
      }
    }
  }

  private void handleAccepted(HttpExchange exchange) {
    assert exchange != null;
    var context =
        RequestContext.create(
            exchange.getRequestMethod(),
            exchange.getRequestURI().getPath(),
            devAuth ? "dev" : "prod");
    try {
      RequestContext.with(
          context,
          () -> {
            route(exchange);
            return null;
          });
    } catch (Exception exception) {
      if (exchange.getResponseCode() < 0) {
        try {
          HttpSupport.json(
              exchange,
              500,
              new ApiError("internal_error", "internal server error", context.requestId()).json());
        } catch (IOException responseException) {
          // The peer may have disconnected; the exchange is closed below.
        }
      }
    } finally {
      exchange.close();
    }
  }

  private void route(HttpExchange exchange) throws IOException {
    assert exchange != null;
    String path = exchange.getRequestURI().getPath();
    assert !exceedsPathLimit(path);
    if (path.equals("/health")) {
      if (health.healthy())
        HttpSupport.json(exchange, 200, Json.write(java.util.Map.of("status", "ok")));
      else
        HttpSupport.json(
            exchange,
            503,
            Json.write(java.util.Map.of("status", "degraded", "reason", health.reason())));
      return;
    }
    if (path.equals("/") && exchange.getRequestMethod().equals("GET")) {
      String strip =
          devAuth
              ? "<div style=\"background:#b00020;color:white;padding:.5rem\">DEV AUTH</div>"
              : "";
      HttpSupport.html(
          exchange,
          200,
          "<!doctype html><meta charset=\"utf-8\"><title>TokTrak</title>"
              + strip
              + "<h1>TokTrak</h1>");
      return;
    }
    if (path.startsWith("/api/")) {
      RequestContext context = RequestContext.currentOrNull();
      assert context != null;
      HttpSupport.json(
          exchange, 404, new ApiError("not_found", "route not found", context.requestId()).json());
      return;
    }
    HttpSupport.html(
        exchange,
        404,
        "<!doctype html><meta charset=\"utf-8\"><title>404</title><h1>404</h1><p>BRUTAL ERROR</p>");
  }

  private static boolean exceedsPathLimit(String value) {
    if (value == null) return false;
    if (value.length() > PATH_BYTES_MAX) return true;
    return value.getBytes(StandardCharsets.UTF_8).length > PATH_BYTES_MAX;
  }
}
