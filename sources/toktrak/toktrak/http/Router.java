package toktrak.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import io.jstach.jstachio.Template;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import toktrak.auth.AuthService;
import toktrak.auth.AuthService.Session;
import toktrak.health.HealthState;
import toktrak.identity.IdentityService.PreparedToken;
import toktrak.json.Json;
import toktrak.projection.Projection.TokenPage;
import toktrak.projection.Projection.TrackerToken;

public final class Router implements HttpHandler {
  private static final int PATH_BYTES_MAX = 2 * 1024;
  private static final int METHOD_CHARACTERS_MAX = 32;
  private static final int FORM_BYTES_MAX = 16 * 1024;
  private static final int TOKEN_PAGE_SIZE = 100;
  private static final String URI_TOO_LONG_PATH = "URI exceeds 2048-byte limit";
  private static final String INVALID_METHOD = "(invalid method)";

  private final HealthState health;
  private final boolean devAuth;
  private final Executor requestExecutor;
  private final Assets assets;
  private final AuthService auth;

  public Router(
      HealthState health,
      boolean devAuth,
      Executor requestExecutor,
      Assets assets,
      AuthService auth) {
    assert health != null;
    assert requestExecutor != null;
    this.health = health;
    this.devAuth = devAuth;
    this.requestExecutor = requestExecutor;
    this.assets = Objects.requireNonNull(assets, "assets");
    this.auth = Objects.requireNonNull(auth, "auth");
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
    String method = exchange.getRequestMethod();
    assert !exceedsPathLimit(path);
    String rawPath = exchange.getRequestURI().getRawPath();
    if (method.equals("GET") && exchange.getRequestURI().getRawQuery() == null) {
      var asset = assets.publicAsset(rawPath);
      if (asset.isPresent()) {
        HttpSupport.asset(exchange, asset.get().mediaType(), asset.get().body());
        return;
      }
    }
    if (devAuth && path.equals("/debug/error") && method.equals("GET")) {
      throw new IllegalStateException("debug failure");
    }
    if (path.equals("/health")) {
      health(exchange);
      return;
    }
    if (path.equals("/login") && method.equals("GET")) {
      login(exchange);
      return;
    }
    if (path.equals("/oauth/callback") && method.equals("GET")) {
      callback(exchange);
      return;
    }
    if (path.equals("/tokens") && method.equals("GET")) {
      tokens(exchange);
      return;
    }
    if (path.equals("/tokens") && method.equals("POST")) {
      createToken(exchange);
      return;
    }
    if (path.equals("/tokens/revoke") && method.equals("POST")) {
      revokeToken(exchange);
      return;
    }
    if (path.equals("/account/deactivate") && method.equals("POST")) {
      deactivate(exchange);
      return;
    }
    if (path.equals("/logout") && method.equals("POST")) {
      logout(exchange);
      return;
    }
    if (path.equals("/api/auth") && method.equals("GET")) {
      apiAuth(exchange);
      return;
    }
    if (path.equals("/api/tracker/verify") && method.equals("POST")) {
      verifyTracker(exchange);
      return;
    }
    if (path.equals("/") && method.equals("GET")) {
      home(exchange);
      return;
    }
    notFound(exchange, path);
  }

  private void health(HttpExchange exchange) throws IOException {
    if (health.healthy()) HttpSupport.json(exchange, 200, Json.write(Map.of("status", "ok")));
    else
      HttpSupport.json(
          exchange, 503, Json.write(Map.of("status", "degraded", "reason", health.reason())));
  }

  private void login(HttpExchange exchange) throws IOException {
    try {
      AuthService.Login login = auth.beginLogin();
      if (login.transaction() != null) {
        exchange
            .getResponseHeaders()
            .add("Set-Cookie", auth.transactionCookie(login.transaction()));
      } else {
        exchange.getResponseHeaders().add("Set-Cookie", auth.sessionCookie(login.session()));
      }
      HttpSupport.redirect(exchange, 302, login.redirectUri());
    } catch (RuntimeException exception) {
      authFailure(exchange, exception);
    }
  }

  private void callback(HttpExchange exchange) throws IOException {
    try {
      String transaction =
          AuthService.cookie(
                  exchange.getRequestHeaders().getFirst("Cookie"), AuthService.TRANSACTION_COOKIE)
              .orElseThrow(() -> new IllegalArgumentException("OIDC transaction is invalid"));
      AuthService.CompletedLogin login =
          auth.completeLogin(exchange.getRequestURI().getRawQuery(), transaction);
      exchange.getResponseHeaders().add("Set-Cookie", auth.clearTransactionCookie());
      exchange.getResponseHeaders().add("Set-Cookie", auth.sessionCookie(login.session()));
      HttpSupport.redirect(exchange, 302, login.redirectUri());
    } catch (RuntimeException exception) {
      authFailure(exchange, exception);
    }
  }

  private void tokens(HttpExchange exchange) throws IOException {
    Session session = browserSession(exchange);
    if (session == null) return;
    try {
      int page = tokenPage(exchange.getRequestURI().getRawQuery());
      TokenPage tokens = auth.identities().trackerTokenPage(session.key(), page, TOKEN_PAGE_SIZE);
      var rows = tokens.tokens().stream().map(Router::tokenRow).toList();
      var view =
          new TokenListView(
              base("My Tracker · TokTrak"),
              session.csrf(),
              rows,
              page,
              page > 1,
              page > 1 ? tokenPageUrl(page - 1) : "",
              page < tokens.pageCount(),
              page < tokens.pageCount() ? tokenPageUrl(page + 1) : "");
      HttpSupport.encodedHtml(
          exchange,
          200,
          render(
              TokenListViewRenderer.of(),
              view,
              "tokens.mustache",
              "TokenListView",
              "TokenListViewRenderer"));
    } catch (IllegalArgumentException exception) {
      badRequest(exchange, exception.getMessage());
    }
  }

  private void createToken(HttpExchange exchange) throws IOException {
    Session session = browserSession(exchange);
    if (session == null) return;
    try {
      Map<String, String> form = form(exchange);
      auth.requireCsrf(session, form.get("csrf"));
      PreparedToken prepared =
          auth.identities().prepareTrackerToken(session.key(), form.get("label"));
      var view =
          new CreatedTokenView(base("Tracker token created · TokTrak"), prepared.plaintext());
      byte[] body =
          render(
              CreatedTokenViewRenderer.of(),
              view,
              "created-token.mustache",
              "CreatedTokenView",
              "CreatedTokenViewRenderer");
      auth.identities().commitTrackerToken(prepared);
      HttpSupport.encodedHtml(exchange, 201, body);
    } catch (IllegalArgumentException | IllegalStateException exception) {
      badRequest(exchange, exception.getMessage());
    }
  }

  private void revokeToken(HttpExchange exchange) throws IOException {
    Session session = browserSession(exchange);
    if (session == null) return;
    try {
      Map<String, String> form = form(exchange);
      auth.requireCsrf(session, form.get("csrf"));
      auth.identities().revokeTrackerToken(session.key(), UUID.fromString(form.get("tokenId")));
      HttpSupport.redirect(exchange, 303, URI.create("/tokens"));
    } catch (IllegalArgumentException | IllegalStateException exception) {
      badRequest(exchange, exception.getMessage());
    }
  }

  private void deactivate(HttpExchange exchange) throws IOException {
    Session session = browserSession(exchange);
    if (session == null) return;
    try {
      Map<String, String> form = form(exchange);
      auth.requireCsrf(session, form.get("csrf"));
      auth.identities().deactivate(session.key());
      exchange.getResponseHeaders().add("Set-Cookie", auth.clearSessionCookie());
      HttpSupport.redirect(exchange, 303, URI.create("/login"));
    } catch (IllegalArgumentException | IllegalStateException exception) {
      badRequest(exchange, exception.getMessage());
    }
  }

  private void logout(HttpExchange exchange) throws IOException {
    Session session = browserSession(exchange);
    if (session == null) return;
    try {
      Map<String, String> form = form(exchange);
      auth.requireCsrf(session, form.get("csrf"));
      exchange.getResponseHeaders().add("Set-Cookie", auth.clearSessionCookie());
      HttpSupport.redirect(exchange, 303, URI.create("/"));
    } catch (IllegalArgumentException exception) {
      badRequest(exchange, exception.getMessage());
    }
  }

  private void apiAuth(HttpExchange exchange) throws IOException {
    Session session = apiSession(exchange);
    if (session == null) return;
    HttpSupport.json(
        exchange,
        200,
        Json.write(
            Map.of(
                "status", "ok",
                "subject", session.key().subject(),
                "color", session.user().color())));
  }

  private void verifyTracker(HttpExchange exchange) throws IOException {
    String authorization = exchange.getRequestHeaders().getFirst("Authorization");
    if (authorization == null || !authorization.startsWith("Bearer ")) {
      apiError(exchange, 401, "invalid_token", "tracker token is invalid");
      return;
    }
    try {
      auth.identities().authenticateTrackerToken(authorization.substring("Bearer ".length()));
      HttpSupport.json(exchange, 200, Json.write(Map.of("status", "ok")));
    } catch (IllegalArgumentException | IllegalStateException exception) {
      apiError(exchange, 401, "invalid_token", "tracker token is invalid");
    }
  }

  private void home(HttpExchange exchange) throws IOException {
    boolean signedIn;
    try {
      auth.requireSession(exchange.getRequestHeaders().getFirst("Cookie"));
      signedIn = true;
    } catch (IllegalArgumentException exception) {
      signedIn = false;
    }
    var view = new HomeView(base("TokTrak"), signedIn);
    HttpSupport.encodedHtml(
        exchange,
        200,
        render(HomeViewRenderer.of(), view, "home.mustache", "HomeView", "HomeViewRenderer"));
  }

  private BaseView base(String title) {
    return new BaseView(title, assets.publicUrl("main.css"), devAuth);
  }

  private Session browserSession(HttpExchange exchange) throws IOException {
    try {
      return auth.requireSession(exchange.getRequestHeaders().getFirst("Cookie"));
    } catch (IllegalArgumentException exception) {
      HttpSupport.redirect(exchange, 302, URI.create("/login"));
      return null;
    }
  }

  private Session apiSession(HttpExchange exchange) throws IOException {
    try {
      return auth.requireSession(exchange.getRequestHeaders().getFirst("Cookie"));
    } catch (IllegalArgumentException exception) {
      apiError(exchange, 401, "login_required", "login required");
      return null;
    }
  }

  private Map<String, String> form(HttpExchange exchange) throws IOException {
    String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
    if (contentType == null || !contentType.equalsIgnoreCase("application/x-www-form-urlencoded")) {
      throw new IllegalArgumentException("form content type is required");
    }
    byte[] bytes = HttpSupport.readLimited(exchange.getRequestBody(), FORM_BYTES_MAX);
    return toktrak.auth.OidcClient.parseForm(new String(bytes, StandardCharsets.UTF_8));
  }

  private static TokenListView.TokenRow tokenRow(TrackerToken token) {
    assert token != null;
    boolean active = token.revokedAt() == null;
    String lastUsed = token.lastUsedAt() == null ? "" : token.lastUsedAt().toString();
    return new TokenListView.TokenRow(
        token.label(),
        token.id().toString(),
        active ? "active" : "revoked",
        !lastUsed.isEmpty(),
        lastUsed,
        active);
  }

  private static int tokenPage(String query) {
    if (query == null) return 1;
    if (!query.matches("page=[1-9][0-9]{0,9}")) {
      throw new IllegalArgumentException("page query is invalid");
    }
    try {
      return Integer.parseInt(query.substring("page=".length()));
    } catch (NumberFormatException exception) {
      throw new IllegalArgumentException("page query is invalid", exception);
    }
  }

  private static String tokenPageUrl(int page) {
    assert page >= 1;
    return "/tokens?page=" + page;
  }

  private static <T> byte[] render(
      Template.EncodedTemplate<T> renderer,
      T model,
      String templateName,
      String modelName,
      String rendererName) {
    try {
      return HttpSupport.renderEncoded(renderer, model);
    } catch (HttpSupport.EncodedHtmlTooLargeException exception) {
      throw new RenderFailure(templateName, modelName, rendererName, "output_limit");
    } catch (IOException | RuntimeException exception) {
      throw new RenderFailure(templateName, modelName, rendererName, "renderer_failure");
    }
  }

  private void authFailure(HttpExchange exchange, RuntimeException exception) throws IOException {
    RequestContext context = RequestContext.currentOrNull();
    assert context != null;
    respondError(
        exchange,
        401,
        "authentication_failed",
        "authentication failed",
        "Authentication failed.",
        context.requestId(),
        devAuth ? exception : null);
  }

  private void badRequest(HttpExchange exchange, String message) throws IOException {
    RequestContext context = RequestContext.currentOrNull();
    assert context != null;
    respondError(
        exchange,
        400,
        "invalid_request",
        "request is invalid",
        devAuth && message != null && !message.isBlank() ? message : "Request is invalid.",
        context.requestId(),
        null);
  }

  private void apiError(HttpExchange exchange, int status, String code, String message)
      throws IOException {
    RequestContext context = RequestContext.currentOrNull();
    assert context != null;
    HttpSupport.json(exchange, status, new ApiError(code, message, context.requestId()).json());
  }

  private void notFound(HttpExchange exchange, String path) throws IOException {
    if (path.startsWith("/api/")) {
      apiError(exchange, 404, "not_found", "route not found");
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
