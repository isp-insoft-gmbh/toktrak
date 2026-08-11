package toktrak.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import toktrak.health.HealthState;
import toktrak.json.Json;

public final class Router implements HttpHandler {
  private static final int PATH_BYTES_MAX = 2 * 1024;
  private static final int METHOD_CHARACTERS_MAX = 32;
  private static final String URI_TOO_LONG_PATH = "URI exceeds 2048-byte limit";
  private static final String INVALID_METHOD = "(invalid method)";

  private final HealthState health;
  private final boolean devAuth;
  private final Executor requestExecutor;
  private final Assets assets;

  public Router(HealthState health, boolean devAuth, Executor requestExecutor, Assets assets) {
    assert health != null;
    assert requestExecutor != null;
    this.health = health;
    this.devAuth = devAuth;
    this.requestExecutor = requestExecutor;
    this.assets = Objects.requireNonNull(assets, "assets");
  }

  @Override
  public void handle(HttpExchange exchange) throws IOException {
    assert exchange != null;
    String rawPath = exchange.getRequestURI().getRawPath();
    String path = exchange.getRequestURI().getPath();
    if (exceedsPathLimit(rawPath) || exceedsPathLimit(path)) {
      String requestId = UUID.randomUUID().toString();
      try {
        respondError(
            exchange,
            414,
            "uri_too_long",
            "request URI is too long",
            "Request URI is too long.",
            requestId,
            null);
      } finally {
        exchange.close();
      }
      return;
    }
    String method = exchange.getRequestMethod();
    if (method == null || method.isBlank() || method.length() > METHOD_CHARACTERS_MAX) {
      String requestId = UUID.randomUUID().toString();
      try {
        respondError(
            exchange,
            400,
            "invalid_method",
            "request method is invalid",
            "Request method is invalid.",
            requestId,
            null);
      } finally {
        exchange.close();
      }
      return;
    }
    try {
      requestExecutor.execute(() -> handleAccepted(exchange));
    } catch (RejectedExecutionException exception) {
      try {
        respondError(
            exchange,
            503,
            "server_busy",
            "server is busy",
            "Server is busy. Try again.",
            UUID.randomUUID().toString(),
            null);
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
          respondError(
              exchange,
              500,
              "internal_error",
              "internal server error",
              "Internal server error. Try again.",
              context.requestId(),
              exception);
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
    String rawPath = exchange.getRequestURI().getRawPath();
    if (exchange.getRequestMethod().equals("GET")
        && exchange.getRequestURI().getRawQuery() == null) {
      var asset = assets.publicAsset(rawPath);
      if (asset.isPresent()) {
        HttpSupport.asset(exchange, asset.get().mediaType(), asset.get().body());
        return;
      }
    }
    if (devAuth && path.equals("/debug/error") && exchange.getRequestMethod().equals("GET")) {
      throw new IllegalStateException("debug failure");
    }
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
      String strip = devAuth ? "<div class=\"environment-banner\">DEV AUTH</div>" : "";
      HttpSupport.html(
          exchange,
          200,
          "<!doctype html><meta charset=\"utf-8\"><title>TokTrak</title>"
              + "<link rel=\"stylesheet\" href=\""
              + assets.publicUrl("main.css")
              + "\">"
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
    RequestContext context = RequestContext.currentOrNull();
    assert context != null;
    respondError(
        exchange,
        404,
        "not_found",
        "route not found",
        "Route not found.",
        context.requestId(),
        null);
  }

  private void respondError(
      HttpExchange exchange,
      int status,
      String code,
      String jsonMessage,
      String browserMessage,
      String requestId,
      Throwable failure)
      throws IOException {
    assert exchange != null;
    assert status >= 400 && status <= 599;
    assert code != null && !code.isBlank();
    assert jsonMessage != null && !jsonMessage.isBlank();
    assert browserMessage != null && !browserMessage.isBlank();
    assert requestId != null && !requestId.isBlank();
    String path = status == 414 ? URI_TOO_LONG_PATH : exchange.getRequestURI().getPath();
    if (path == null || path.isBlank()) path = "/";
    String method = exchange.getRequestMethod();
    if (method == null || method.isBlank() || method.length() > METHOD_CHARACTERS_MAX) {
      method = INVALID_METHOD;
    }
    if (isJsonPath(exchange.getRequestURI().getPath())) {
      HttpSupport.json(exchange, status, new ApiError(code, jsonMessage, requestId).json());
    } else {
      HttpSupport.html(
          exchange,
          status,
          ErrorPage.render(
              status,
              code,
              browserMessage,
              requestId,
              method,
              path,
              assets.publicUrl("main.css"),
              failure,
              devAuth));
    }
  }

  private static boolean isJsonPath(String path) {
    return path != null && (path.equals("/health") || path.startsWith("/api/"));
  }

  private static boolean exceedsPathLimit(String value) {
    if (value == null) return false;
    if (value.length() > PATH_BYTES_MAX) return true;
    return value.getBytes(StandardCharsets.UTF_8).length > PATH_BYTES_MAX;
  }
}
