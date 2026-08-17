package toktrak.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import io.jstach.jstachio.Template;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import toktrak.auth.AuthService;
import toktrak.auth.AuthService.Session;
import toktrak.health.HealthState;
import toktrak.http.BaseView.Currency;
import toktrak.http.BaseView.CurrencySwitch;
import toktrak.http.BaseView.CurrentPage;
import toktrak.http.BaseView.RuntimeMode;
import toktrak.http.HomeView.SessionState;
import toktrak.http.TokenListView.LastUsage;
import toktrak.http.TokenListView.PageLink;
import toktrak.http.TokenListView.TokenState;
import toktrak.identity.IdentityService.PreparedToken;
import toktrak.json.Json;
import toktrak.projection.Projection;
import toktrak.projection.Projection.TokenPage;
import toktrak.projection.Projection.TrackerToken;
import toktrak.projection.Projection.UserKey;
import toktrak.usage.UsageService;
import toktrak.usage.UsageUpload;
import toktrak.usage.UsageUpload.Report;

public final class Router implements HttpHandler {
  private static final int PATH_BYTES_MAX = 2 * 1024;
  private static final int QUERY_BYTES_MAX = 8 * 1024;
  private static final int METHOD_CHARACTERS_MAX = 32;
  private static final int FORM_BYTES_MAX = 16 * 1024;
  private static final int TOKEN_PAGE_SIZE = 100;
  private static final int USAGE_PAGE_SIZE_DEFAULT = 100;
  private static final int USAGE_PAGE_SIZE_MAX = 1_000;
  private static final Duration STREAM_WAIT = Duration.ofSeconds(5);
  private static final String URI_TOO_LONG_PATH =
      "URI exceeds 2048-byte path or 8192-byte query limit";
  private static final String INVALID_METHOD = "(invalid method)";
  private static final String CURRENCY_COOKIE = "toktrak_currency";

  private final HealthState health;
  private final boolean devAuth;
  private final Executor requestExecutor;
  private final Assets assets;
  private final AuthService auth;
  private final UsageService usage;
  private final Projection projection;
  private final TrackerScript trackerScript;

  public Router(
      HealthState health,
      boolean devAuth,
      Executor requestExecutor,
      Assets assets,
      AuthService auth,
      UsageService usage,
      Projection projection,
      URI baseUri) {
    assert health != null;
    assert requestExecutor != null;
    this.health = health;
    this.devAuth = devAuth;
    this.requestExecutor = requestExecutor;
    this.assets = Objects.requireNonNull(assets, "assets");
    this.auth = Objects.requireNonNull(auth, "auth");
    this.usage = Objects.requireNonNull(usage, "usage");
    this.projection = Objects.requireNonNull(projection, "projection");
    this.trackerScript =
        new TrackerScript(assets.privateBytes("tracker.mjs"), Objects.requireNonNull(baseUri));
  }

  @Override
  public void handle(HttpExchange exchange) throws IOException {
    assert exchange != null;
    String rawPath = exchange.getRequestURI().getRawPath();
    String path = exchange.getRequestURI().getPath();
    String rawQuery = exchange.getRequestURI().getRawQuery();
    if (exceedsUtf8Limit(rawPath, PATH_BYTES_MAX)
        || exceedsUtf8Limit(path, PATH_BYTES_MAX)
        || exceedsUtf8Limit(rawQuery, QUERY_BYTES_MAX)) {
      String requestId = UUID.randomUUID().toString();
      try (exchange) {
        respondError(
            exchange,
            414,
            "uri_too_long",
            "request URI is too long",
            "Request URI is too long.",
            requestId,
            null);
      }
      return;
    }
    String method = exchange.getRequestMethod();
    if (method == null || method.isBlank() || method.length() > METHOD_CHARACTERS_MAX) {
      String requestId = UUID.randomUUID().toString();
      try (exchange) {
        respondError(
            exchange,
            400,
            "invalid_method",
            "request method is invalid",
            "Request method is invalid.",
            requestId,
            null);
      }
      return;
    }
    try {
      requestExecutor.execute(() -> handleAccepted(exchange));
    } catch (RejectedExecutionException exception) {
      try (exchange) {
        respondError(
            exchange,
            503,
            "server_busy",
            "server is busy",
            "Server is busy. Try again.",
            UUID.randomUUID().toString(),
            null);
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
    try (exchange) {
      try {
        RequestContext.with(
            context,
            () -> {
              route(exchange);
              return null;
            });
      } catch (IOException
          | ArithmeticException
          | ArrayStoreException
          | ClassCastException
          | EnumConstantNotPresentException
          | IllegalArgumentException
          | IllegalMonitorStateException
          | IllegalStateException
          | IndexOutOfBoundsException
          | NegativeArraySizeException
          | RenderFailure
          | SecurityException
          | TypeNotPresentException
          | UnsupportedOperationException exception) {
        internalFailure(exchange, context, exception);
      }
    }
  }

  private void internalFailure(HttpExchange exchange, RequestContext context, Throwable exception) {
    assert exchange != null;
    assert context != null;
    assert exception != null;
    if (exchange.getResponseCode() >= 0) return;
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
      // The peer may have disconnected; the exchange is closed by the caller.
    }
  }

  private void route(HttpExchange exchange) throws IOException {
    assert exchange != null;
    String path = exchange.getRequestURI().getPath();
    String method = exchange.getRequestMethod();
    assert !exceedsUtf8Limit(path, PATH_BYTES_MAX);
    assert !exceedsUtf8Limit(exchange.getRequestURI().getRawQuery(), QUERY_BYTES_MAX);
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
    if (path.equals("/api/tracker") && method.equals("GET")) {
      trackerScript(exchange);
      return;
    }
    if (path.equals("/api/usage") && method.equals("POST")) {
      uploadUsage(exchange);
      return;
    }
    if (path.equals("/api/analytics") && method.equals("GET")) {
      analytics(exchange);
      return;
    }
    if (path.startsWith("/api/usage/") && method.equals("GET")) {
      usageRows(exchange, path);
      return;
    }
    if (path.equals("/api/stream") && method.equals("GET")) {
      stream(exchange);
      return;
    }
    if (path.equals("/visualizations") && method.equals("GET")) {
      visualizations(exchange);
      return;
    }
    if (path.equals("/scope") && method.equals("GET")) {
      scope(exchange);
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
    } catch (IllegalArgumentException | IllegalStateException exception) {
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
    } catch (IllegalArgumentException | IllegalStateException exception) {
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
              trackerBase("My Tracker · TokTrak"),
              session.csrf(),
              rows,
              page,
              page > 1 ? PageLink.available(tokenPageUrl(page - 1)) : PageLink.unavailable(),
              page < tokens.pageCount()
                  ? PageLink.available(tokenPageUrl(page + 1))
                  : PageLink.unavailable(),
              assets.publicUrl("platform.js"));
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
      TrackerScript.Personalized script = trackerScript.render(prepared.plaintext());
      var view =
          new CreatedTokenView(
              trackerBase("Tracker token created · TokTrak"),
              prepared.plaintext(),
              script.text(),
              script.sha256(),
              assets.publicUrl("clipboard.js"),
              assets.publicUrl("platform.js"));
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
    UserKey owner = trackerOwner(exchange);
    if (owner == null) return;
    HttpSupport.json(exchange, 200, Json.write(Map.of("status", "ok")));
  }

  private void trackerScript(HttpExchange exchange) throws IOException {
    String token = bearerToken(exchange);
    if (token == null || trackerOwner(exchange, token) == null) return;
    TrackerScript.Personalized script = trackerScript.render(token);
    HttpSupport.trackerScript(exchange, script.bytes(), script.sha256());
  }

  private void uploadUsage(HttpExchange exchange) throws IOException {
    UserKey owner = trackerOwner(exchange);
    if (owner == null) return;
    String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
    if (contentType == null || !contentType.equalsIgnoreCase("application/json")) {
      apiError(exchange, 415, "unsupported_media_type", "application/json is required");
      return;
    }
    byte[] body;
    try {
      body = HttpSupport.readLimited(exchange.getRequestBody(), HttpSupport.MAX_REQUEST_BODY_BYTES);
    } catch (IllegalArgumentException exception) {
      apiError(exchange, 413, "payload_too_large", "usage upload is too large");
      return;
    }
    try {
      UsageUpload uploaded = usage.upload(owner, body);
      HttpSupport.json(
          exchange,
          200,
          Json.write(
              Map.of(
                  "status", "ok",
                  "generatedAt", uploaded.generatedAt(),
                  "partial", uploaded.partial(),
                  "successfulReports", uploaded.successfulReports(),
                  "failedReports", uploaded.failedReports())));
    } catch (IllegalArgumentException exception) {
      apiError(exchange, 400, "invalid_usage", "usage upload is invalid");
    } catch (IllegalStateException exception) {
      apiError(exchange, 503, "write_unavailable", "usage upload could not be stored");
    }
  }

  private UserKey trackerOwner(HttpExchange exchange) throws IOException {
    String token = bearerToken(exchange);
    return token == null ? null : trackerOwner(exchange, token);
  }

  private String bearerToken(HttpExchange exchange) throws IOException {
    assert exchange != null;
    String authorization = exchange.getRequestHeaders().getFirst("Authorization");
    if (authorization == null || !authorization.startsWith("Bearer ")) {
      apiError(exchange, 401, "invalid_token", "tracker token is invalid");
      return null;
    }
    return authorization.substring("Bearer ".length());
  }

  private UserKey trackerOwner(HttpExchange exchange, String token) throws IOException {
    assert exchange != null;
    assert token != null;
    try {
      return auth.identities().authenticateTrackerToken(token);
    } catch (IllegalArgumentException exception) {
      apiError(exchange, 401, "invalid_token", "tracker token is invalid");
      return null;
    } catch (IllegalStateException exception) {
      apiError(exchange, 503, "write_unavailable", "tracker verification is unavailable");
      return null;
    }
  }

  private void analytics(HttpExchange exchange) throws IOException {
    if (apiSession(exchange) == null) return;
    var body = new LinkedHashMap<String, Object>();
    body.put("revision", projection.revision());
    body.put("summary", projection.usageSummary());
    body.put("ingestion", projection.ingestion());
    body.put("fx", projection.fxRate().orElse(null));
    HttpSupport.json(exchange, 200, Json.write(body));
  }

  private void usageRows(HttpExchange exchange, String path) throws IOException {
    if (apiSession(exchange) == null) return;
    Report report =
        switch (path) {
          case "/api/usage/daily" -> Report.DAILY;
          case "/api/usage/session" -> Report.SESSION;
          case "/api/usage/blocks" -> Report.BLOCKS;
          default -> null;
        };
    if (report == null) {
      apiError(exchange, 404, "not_found", "route not found");
      return;
    }
    try {
      Page page = page(exchange.getRequestURI().getRawQuery());
      List<toktrak.usage.UsageProjection.Row> rows = projection.usageRows(report);
      int pageCount = Math.max(1, Math.ceilDiv(rows.size(), page.pageSize));
      if (page.page > pageCount) throw new IllegalArgumentException("page is out of range");
      int from = Math.multiplyExact(page.page - 1, page.pageSize);
      int to = Math.min(rows.size(), Math.addExact(from, page.pageSize));
      HttpSupport.json(
          exchange,
          200,
          Json.write(
              Map.of(
                  "report", report.reportName(),
                  "total", rows.size(),
                  "page", page.page,
                  "pageCount", pageCount,
                  "rows", rows.subList(from, to))));
    } catch (IllegalArgumentException | ArithmeticException exception) {
      apiError(exchange, 400, "invalid_query", "usage query is invalid");
    }
  }

  private void stream(HttpExchange exchange) throws IOException {
    if (apiSession(exchange) == null) return;
    try {
      long after = revision(exchange.getRequestURI().getRawQuery());
      long observed = projection.awaitRevision(after, STREAM_WAIT);
      String body =
          observed > after
              ? "event: datastar-patch-signals\ndata: signals "
                  + Json.write(Map.of("_usageRevision", observed))
                  + "\n\n"
              : ": keepalive\n\n";
      HttpSupport.eventStream(exchange, body);
    } catch (IllegalArgumentException exception) {
      apiError(exchange, 400, "invalid_query", "stream query is invalid");
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      apiError(exchange, 503, "server_stopping", "server is stopping");
    }
  }

  private void home(HttpExchange exchange) throws IOException {
    Session session;
    try {
      session = auth.requireSession(exchange.getRequestHeaders().getFirst("Cookie"));
    } catch (IllegalArgumentException exception) {
      var view = new HomeView(base("TokTrak"), SessionState.SIGNED_OUT);
      HttpSupport.encodedHtml(
          exchange,
          200,
          render(HomeViewRenderer.of(), view, "home.mustache", "HomeView", "HomeViewRenderer"));
      return;
    }
    DashboardCurrency currency = dashboardCurrency(exchange, "/");
    if (currency == null) return;
    DashboardCurrency effective = effectiveCurrency(currency);
    var view =
        new OverviewView(
            dashboardBase("Overview · TokTrak", "/", CurrentPage.OVERVIEW, effective),
            projection.revision(),
            session.user().displayName(),
            DashboardFactory.create(projection, health, effective));
    HttpSupport.encodedHtml(
        exchange,
        200,
        render(
            OverviewViewRenderer.of(),
            view,
            "overview.mustache",
            "OverviewView",
            "OverviewViewRenderer"));
  }

  private void visualizations(HttpExchange exchange) throws IOException {
    Session session = browserSession(exchange);
    if (session == null) return;
    DashboardCurrency currency = dashboardCurrency(exchange, "/visualizations");
    if (currency == null) return;
    DashboardCurrency effective = effectiveCurrency(currency);
    var view =
        new VisualizationsView(
            dashboardBase(
                "Visualizations · TokTrak",
                "/visualizations",
                CurrentPage.VISUALIZATIONS,
                effective),
            projection.revision(),
            session.user().displayName(),
            DashboardFactory.create(projection, health, effective));
    HttpSupport.encodedHtml(
        exchange,
        200,
        render(
            VisualizationsViewRenderer.of(),
            view,
            "visualizations.mustache",
            "VisualizationsView",
            "VisualizationsViewRenderer"));
  }

  private void scope(HttpExchange exchange) throws IOException {
    Session session = browserSession(exchange);
    if (session == null) return;
    DashboardCurrency currency = dashboardCurrency(exchange, "/scope");
    if (currency == null) return;
    DashboardCurrency effective = effectiveCurrency(currency);
    var view =
        new ScopeView(
            dashboardBase("Data scope · TokTrak", "/scope", CurrentPage.SCOPE, effective),
            session.user().displayName());
    HttpSupport.encodedHtml(
        exchange,
        200,
        render(ScopeViewRenderer.of(), view, "scope.mustache", "ScopeView", "ScopeViewRenderer"));
  }

  private DashboardCurrency dashboardCurrency(HttpExchange exchange, String path)
      throws IOException {
    assert exchange != null;
    assert path.equals("/") || path.equals("/visualizations") || path.equals("/scope");
    String query = exchange.getRequestURI().getRawQuery();
    if (query != null) {
      try {
        Map<String, String> fields = toktrak.auth.OidcClient.parseForm(query);
        if (!fields.keySet().equals(java.util.Set.of("currency"))) {
          throw new IllegalArgumentException("currency query is invalid");
        }
        DashboardCurrency currency = DashboardCurrency.valueOf(fields.get("currency"));
        exchange
            .getResponseHeaders()
            .add(
                "Set-Cookie",
                CURRENCY_COOKIE
                    + "="
                    + currency
                    + "; Path=/; Max-Age=31536000; HttpOnly; SameSite=Lax"
                    + (devAuth ? "" : "; Secure"));
        HttpSupport.redirect(exchange, 303, URI.create(path));
        return null;
      } catch (IllegalArgumentException exception) {
        badRequest(exchange, "currency query is invalid");
        return null;
      }
    }
    return AuthService.cookie(exchange.getRequestHeaders().getFirst("Cookie"), CURRENCY_COOKIE)
        .map(
            value -> {
              try {
                return DashboardCurrency.valueOf(value);
              } catch (IllegalArgumentException exception) {
                return DashboardCurrency.USD;
              }
            })
        .orElse(DashboardCurrency.USD);
  }

  private DashboardCurrency effectiveCurrency(DashboardCurrency currency) {
    assert currency != null;
    return currency == DashboardCurrency.EUR && projection.fxRate().isEmpty()
        ? DashboardCurrency.USD
        : currency;
  }

  private BaseView dashboardBase(
      String title, String path, CurrentPage currentPage, DashboardCurrency currency) {
    assert path.equals("/") || path.equals("/visualizations") || path.equals("/scope");
    DashboardCurrency alternative =
        currency == DashboardCurrency.USD ? DashboardCurrency.EUR : DashboardCurrency.USD;
    return new BaseView(
        title,
        assets.publicUrl("main.css"),
        assets.publicUrl("datastar.js"),
        assets.publicUrl("favicon.svg"),
        assets.publicUrl("logo-wordmark.svg"),
        assets.publicUrl("logo-wordmark-dark.svg"),
        assets.publicUrl("logo-lockup.svg"),
        assets.publicUrl("logo-lockup-dark.svg"),
        devAuth ? RuntimeMode.DEVELOPMENT : RuntimeMode.PRODUCTION,
        currentPage,
        CurrencySwitch.enabled(
            Currency.from(currency),
            path + "?currency=" + alternative,
            Currency.from(alternative)));
  }

  private BaseView trackerBase(String title) {
    return new BaseView(
        title,
        assets.publicUrl("main.css"),
        assets.publicUrl("datastar.js"),
        assets.publicUrl("favicon.svg"),
        assets.publicUrl("logo-wordmark.svg"),
        assets.publicUrl("logo-wordmark-dark.svg"),
        assets.publicUrl("logo-lockup.svg"),
        assets.publicUrl("logo-lockup-dark.svg"),
        devAuth ? RuntimeMode.DEVELOPMENT : RuntimeMode.PRODUCTION,
        CurrentPage.TRACKER,
        CurrencySwitch.disabled());
  }

  private BaseView base(String title) {
    return new BaseView(
        title,
        assets.publicUrl("main.css"),
        assets.publicUrl("datastar.js"),
        assets.publicUrl("favicon.svg"),
        assets.publicUrl("logo-wordmark.svg"),
        assets.publicUrl("logo-wordmark-dark.svg"),
        assets.publicUrl("logo-lockup.svg"),
        assets.publicUrl("logo-lockup-dark.svg"),
        devAuth ? RuntimeMode.DEVELOPMENT : RuntimeMode.PRODUCTION,
        CurrentPage.NONE,
        CurrencySwitch.disabled());
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
    TokenState state = token.revokedAt() == null ? TokenState.ACTIVE : TokenState.REVOKED;
    LastUsage lastUsage =
        token.lastUsedAt() == null
            ? LastUsage.never()
            : LastUsage.at(token.lastUsedAt().toString());
    return new TokenListView.TokenRow(token.label(), token.id().toString(), state, lastUsage);
  }

  private static Page page(String query) {
    Map<String, String> fields =
        query == null ? Map.of() : toktrak.auth.OidcClient.parseForm(query);
    if (!java.util.Set.of("page", "pageSize").containsAll(fields.keySet())) {
      throw new IllegalArgumentException("query is invalid");
    }
    int page = positiveInt(fields.getOrDefault("page", "1"), Integer.MAX_VALUE);
    int pageSize =
        positiveInt(
            fields.getOrDefault("pageSize", Integer.toString(USAGE_PAGE_SIZE_DEFAULT)),
            USAGE_PAGE_SIZE_MAX);
    return new Page(page, pageSize);
  }

  private static long revision(String query) {
    if (query == null) return 0;
    Map<String, String> fields = toktrak.auth.OidcClient.parseForm(query);
    if (!java.util.Set.of("revision", "datastar").containsAll(fields.keySet())
        || !fields.containsKey("revision")
        || !fields.getOrDefault("datastar", "{}").equals("{}")) {
      throw new IllegalArgumentException("query is invalid");
    }
    String value = fields.get("revision");
    if (!value.matches("0|[1-9][0-9]{0,18}")) {
      throw new IllegalArgumentException("revision is invalid");
    }
    try {
      return Long.parseLong(value);
    } catch (NumberFormatException exception) {
      throw new IllegalArgumentException("revision is invalid", exception);
    }
  }

  private static int positiveInt(String value, int maximum) {
    assert value != null;
    assert maximum > 0;
    if (!value.matches("[1-9][0-9]{0,9}")) throw new IllegalArgumentException("integer is invalid");
    try {
      int result = Integer.parseInt(value);
      if (result > maximum) throw new NumberFormatException();
      return result;
    } catch (NumberFormatException exception) {
      throw new IllegalArgumentException("integer is invalid", exception);
    }
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

  private record Page(int page, int pageSize) {
    private Page {
      assert page > 0;
      assert pageSize > 0 && pageSize <= USAGE_PAGE_SIZE_MAX;
    }
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
      throw new RenderFailure(templateName, modelName, rendererName, "output_limit", exception);
    } catch (IOException exception) {
      throw new RenderFailure(templateName, modelName, rendererName, "renderer_failure", exception);
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
              assets.publicUrl("favicon.svg"),
              assets.publicUrl("logo-mark.svg"),
              failure,
              devAuth));
    }
  }

  private static boolean isJsonPath(String path) {
    return path != null && (path.equals("/health") || path.startsWith("/api/"));
  }

  private static boolean exceedsUtf8Limit(String value, int bytesMax) {
    assert bytesMax > 0;
    if (value == null) return false;
    if (value.length() > bytesMax) return true;
    return value.getBytes(StandardCharsets.UTF_8).length > bytesMax;
  }
}
