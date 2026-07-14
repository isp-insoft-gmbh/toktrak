package toktrak.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import toktrak.health.HealthState;
import toktrak.json.Json;

public final class Router implements HttpHandler {
  private final HealthState health;
  private final boolean devAuth;

  public Router(HealthState health, boolean devAuth) {
    this.health = health;
    this.devAuth = devAuth;
  }

  @Override
  public void handle(HttpExchange exchange) throws IOException {
    var context = RequestContext.create(exchange.getRequestMethod(), exchange.getRequestURI().getPath(), devAuth ? "dev" : "prod");
    try {
      RequestContext.with(context, () -> {
        route(exchange);
        return null;
      });
    } catch (Exception ex) {
      if (exchange.getResponseCode() < 0) {
        HttpSupport.json(exchange, 500, new ApiError("internal_error", "internal server error", context.requestId()).json());
      }
    } finally {
      exchange.close();
    }
  }

  private void route(HttpExchange exchange) throws IOException {
    String path = exchange.getRequestURI().getPath();
    if (path.equals("/health")) {
      if (health.healthy()) HttpSupport.json(exchange, 200, Json.write(java.util.Map.of("status", "ok")));
      else HttpSupport.json(exchange, 503, Json.write(java.util.Map.of("status", "degraded", "reason", health.reason())));
      return;
    }
    if (path.equals("/") && exchange.getRequestMethod().equals("GET")) {
      String strip = devAuth ? "<div style=\"background:#b00020;color:white;padding:.5rem\">DEV AUTH</div>" : "";
      HttpSupport.html(exchange, 200, "<!doctype html><meta charset=\"utf-8\"><title>TokTrak</title>" + strip + "<h1>TokTrak</h1>");
      return;
    }
    if (path.startsWith("/api/")) {
      String requestId = RequestContext.currentOrNull().requestId();
      HttpSupport.json(exchange, 404, new ApiError("not_found", "route not found", requestId).json());
      return;
    }
    HttpSupport.html(exchange, 404, "<!doctype html><meta charset=\"utf-8\"><title>404</title><h1>404</h1><p>BRUTAL ERROR</p>");
  }
}
