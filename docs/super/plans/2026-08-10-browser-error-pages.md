# Browser Error Pages Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use /skill:executing-plans to
> implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for
> tracking.

**Goal:** Serve useful production browser errors and diagnostic development
browser errors through one safe renderer while preserving JSON API errors.

**Architecture:** Add one deterministic `ErrorPage` renderer for every HTTP
status from 400 through 599. `Router` classifies decoded `/api/` paths as JSON
and all other failed requests as HTML, including admission failures; the
existing in-memory stylesheet supplies the accepted diagnostic-list layout.

**Tech Stack:** Java 26, JDK `HttpServer`, JUnit 6, mise, PIT

**Roadmap:** `docs/super/roadmaps/2026-07-13-toktrak-roadmap.md`

**Phase:** Phase 1: Durable Server Core

---

## File structure

- Create `sources/toktrak/toktrak/http/ErrorPage.java`: bounded deterministic
  HTML rendering and escaping.
- Create `tests/toktrak.tests/toktrak/tests/ErrorPageTest.java`: renderer range,
  production redaction, development diagnostics, escaping, and bounds.
- Modify `sources/toktrak/toktrak/http/Router.java`: classify error responses,
  route browser failures through `ErrorPage`, and expose the dev-only failure
  trigger.
- Modify `tests/toktrak.tests/toktrak/tests/HttpServerTest.java`: browser/API
  integration and dev/prod 500 behavior.
- Modify `tests/toktrak.tests/toktrak/tests/HttpAdmissionTest.java`: saturated
  browser/API response formats.
- Modify `tests/toktrak.tests/toktrak/tests/HealthModeTest.java`: expanded
  stylesheet contract.

### Task 1: Build the generic safe renderer

**Files:**

- Create: `tests/toktrak.tests/toktrak/tests/ErrorPageTest.java`
- Create: `sources/toktrak/toktrak/http/ErrorPage.java`

- [ ] **Step 1: Write renderer tests**

Create `ErrorPageTest.java`:

```java
package toktrak.tests;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import toktrak.http.ErrorPage;

final class ErrorPageTest {
  @Test
  void given_arbitraryErrorStatus_when_renderingPage_then_returnsUsefulProductionHtml() {
    String html =
        ErrorPage.render(
            418,
            "teapot",
            "cannot brew coffee",
            "00000000-0000-4000-8000-000000000001",
            "POST",
            "/coffee",
            new IllegalStateException("debug failure"),
            false);
    assertTrue(html.contains("<h1>418</h1>"));
    assertTrue(html.contains("cannot brew coffee"));
    assertTrue(html.contains("<code>/coffee</code>"));
    assertTrue(html.contains("00000000-0000-4000-8000-000000000001"));
    assertTrue(html.contains("href=\"/\""));
    assertFalse(html.contains("teapot"));
    assertFalse(html.contains("POST"));
    assertFalse(html.contains("DEBUG"));
    assertFalse(html.contains("IllegalStateException"));
    assertFalse(html.contains("debug failure"));
  }

  @Test
  void given_developmentFailure_when_renderingPage_then_returnsEscapedDiagnostics() {
    String html =
        ErrorPage.render(
            500,
            "internal_<error>",
            "failed <publicly>",
            "request&1",
            "GET",
            "/bad?<value>\"'",
            new IllegalStateException("debug <failure> & safe"),
            true);
    assertTrue(html.contains("DEV AUTH · DEBUG"));
    assertTrue(html.contains("internal_&lt;error&gt;"));
    assertTrue(html.contains("failed &lt;publicly&gt;"));
    assertTrue(html.contains("request&amp;1"));
    assertTrue(html.contains("/bad?&lt;value&gt;&quot;&#39;"));
    assertTrue(html.contains("<dt>Method</dt><dd><code>GET</code></dd>"));
    assertTrue(html.contains("java.lang.IllegalStateException: debug &lt;failure&gt; &amp; safe"));
    assertFalse(html.contains("<failure>"));
    assertFalse(html.contains("at toktrak"));
  }

  @Test
  void given_errorStatusOutsideRange_when_renderingPage_then_rejectsStatus() {
    assertThrows(
        IllegalArgumentException.class,
        () -> ErrorPage.render(399, "bad", "bad", "request", "GET", "/", null, false));
    assertThrows(
        IllegalArgumentException.class,
        () -> ErrorPage.render(600, "bad", "bad", "request", "GET", "/", null, false));
  }

  @Test
  void given_exceptionMessageAboveLimit_when_renderingPage_then_boundsDiagnostic() {
    String html =
        ErrorPage.render(
            500,
            "internal_error",
            "internal server error",
            "request",
            "GET",
            "/",
            new IllegalStateException("x".repeat(8_193)),
            true);
    assertTrue(html.contains("x".repeat(8_192)));
    assertFalse(html.contains("x".repeat(8_193)));
  }

  @Test
  void given_maximumEscapingInputs_when_renderingPage_then_returnsBoundedHtml() {
    String html =
        ErrorPage.render(
            599,
            "&".repeat(128),
            "&".repeat(1_024),
            "&".repeat(64),
            "&".repeat(32),
            "&".repeat(2_048),
            new IllegalStateException("&".repeat(8_192)),
            true);
    assertTrue(html.length() <= 128 * 1024);
    assertFalse(html.contains("&&"));
  }
}
```

- [ ] **Step 2: Run tests and verify RED**

Run:

```text
mise run test -- tests/toktrak.tests/toktrak/tests/ErrorPageTest.java
```

Expected: compilation fails because `toktrak.http.ErrorPage` does not exist.

- [ ] **Step 3: Implement `ErrorPage`**

Create `ErrorPage.java`:

```java
package toktrak.http;

import java.util.Objects;

public final class ErrorPage {
  private static final int CODE_CHARACTERS_MAX = 128;
  private static final int MESSAGE_CHARACTERS_MAX = 1024;
  private static final int REQUEST_ID_CHARACTERS_MAX = 64;
  private static final int METHOD_CHARACTERS_MAX = 32;
  private static final int PATH_CHARACTERS_MAX = 2 * 1024;
  private static final int EXCEPTION_CLASS_CHARACTERS_MAX = 1024;
  private static final int EXCEPTION_MESSAGE_CHARACTERS_MAX = 8 * 1024;
  private static final int HTML_CHARACTERS_MAX = 128 * 1024;

  private ErrorPage() {}

  public static String render(
      int status,
      String code,
      String message,
      String requestId,
      String method,
      String path,
      Throwable failure,
      boolean debug) {
    if (status < 400 || status > 599) {
      throw new IllegalArgumentException("status must be 400..599");
    }
    requireText(code, CODE_CHARACTERS_MAX, "code");
    requireText(message, MESSAGE_CHARACTERS_MAX, "message");
    requireText(requestId, REQUEST_ID_CHARACTERS_MAX, "requestId");
    requireText(method, METHOD_CHARACTERS_MAX, "method");
    requireText(path, PATH_CHARACTERS_MAX, "path");

    var html = new StringBuilder(2 * 1024);
    html.append("<!doctype html><meta charset=\"utf-8\"><title>")
        .append(status)
        .append(" · TokTrak</title><link rel=\"stylesheet\" href=\"/assets/main.css\">")
        .append("<main class=\"error-page\">");
    if (debug) {
      html.append("<div class=\"environment-banner\">DEV AUTH · DEBUG</div>");
    }
    html.append("<h1>").append(status).append("</h1><p>").append(escape(message)).append("</p><dl>");
    detail(html, "Path", path);
    detail(html, "Request ID", requestId);
    if (debug) {
      detail(html, "Code", code);
      detail(html, "Method", method);
      if (failure != null) {
        String failureClass =
            bounded(failure.getClass().getName(), EXCEPTION_CLASS_CHARACTERS_MAX);
        String failureMessage =
            failure.getMessage() == null
                ? "(no message)"
                : bounded(failure.getMessage(), EXCEPTION_MESSAGE_CHARACTERS_MAX);
        detail(html, "Exception", failureClass + ": " + failureMessage);
      }
    }
    html.append("</dl><p><a href=\"/\">Return to TokTrak</a></p></main>");
    String result = html.toString();
    if (result.length() > HTML_CHARACTERS_MAX) {
      throw new IllegalStateException("error page exceeds " + HTML_CHARACTERS_MAX + " characters");
    }
    assert result.contains("<h1>" + status + "</h1>");
    return result;
  }

  private static void detail(StringBuilder html, String label, String value) {
    assert html != null;
    assert label != null && !label.isBlank();
    assert value != null && !value.isBlank();
    html.append("<dt>")
        .append(label)
        .append("</dt><dd><code>")
        .append(escape(value))
        .append("</code></dd>");
  }

  private static String bounded(String value, int charactersMax) {
    assert value != null;
    assert charactersMax >= 1 && charactersMax <= HTML_CHARACTERS_MAX;
    return value.length() <= charactersMax ? value : value.substring(0, charactersMax);
  }

  private static String escape(String value) {
    assert value != null;
    var escaped = new StringBuilder(value.length());
    for (int index = 0; index < value.length(); index++) {
      switch (value.charAt(index)) {
        case '&' -> escaped.append("&amp;");
        case '<' -> escaped.append("&lt;");
        case '>' -> escaped.append("&gt;");
        case '\"' -> escaped.append("&quot;");
        case '\'' -> escaped.append("&#39;");
        default -> escaped.append(value.charAt(index));
      }
    }
    return escaped.toString();
  }

  private static void requireText(String value, int charactersMax, String name) {
    Objects.requireNonNull(value, name);
    assert charactersMax >= 1 && charactersMax <= HTML_CHARACTERS_MAX;
    if (value.isBlank()) throw new IllegalArgumentException(name + " is required");
    if (value.length() > charactersMax) {
      throw new IllegalArgumentException(name + " exceeds " + charactersMax + " characters");
    }
  }
}
```

- [ ] **Step 4: Run focused tests and verify GREEN**

Run:

```text
mise run test -- tests/toktrak.tests/toktrak/tests/ErrorPageTest.java
```

Expected: five tests pass.

### Task 2: Route browser failures through the renderer

**Files:**

- Modify: `tests/toktrak.tests/toktrak/tests/HttpServerTest.java`
- Modify: `tests/toktrak.tests/toktrak/tests/HttpAdmissionTest.java`
- Modify: `sources/toktrak/toktrak/http/Router.java`

- [ ] **Step 1: Replace the browser 404 test and add dev 500 coverage**

In `HttpServerTest`, replace
`given_unknownBrowserRoute_when_requestingRoute_then_returnsBrutalNotFoundHtml`
with:

```java
@Test
void given_unknownDevelopmentBrowserRoute_when_requestingRoute_then_returnsDiagnosticHtml()
    throws Exception {
  try (var app =
      App.start(new String[] {}, Map.of("TOKTRAK_DEV_AUTH", "true", "TOKTRAK_PORT", "0"))) {
    var response = get(app, "/nope");
    assertEquals(404, response.statusCode());
    assertEquals("text/html; charset=utf-8", response.header("content-type"));
    assertTrue(response.body().contains("<h1>404</h1>"));
    assertTrue(response.body().contains("Route not found."));
    assertTrue(response.body().contains("<code>/nope</code>"));
    assertTrue(response.body().contains("DEV AUTH · DEBUG"));
    assertTrue(response.body().contains("not_found"));
    assertTrue(response.body().contains("Request ID"));
  }
}

@Test
void given_developmentFailureRoute_when_requestingRoute_then_returnsDiagnosticHtml()
    throws Exception {
  try (var app =
      App.start(new String[] {}, Map.of("TOKTRAK_DEV_AUTH", "true", "TOKTRAK_PORT", "0"))) {
    var response = get(app, "/debug/error");
    assertEquals(500, response.statusCode());
    assertTrue(response.body().contains("Internal server error. Try again."));
    assertTrue(response.body().contains("java.lang.IllegalStateException: debug failure"));
    assertFalse(response.body().contains("at toktrak"));
  }
}
```

Add `@TempDir Path directory;` to the class, import `java.nio.file.Path` and
`org.junit.jupiter.api.io.TempDir`, then add production coverage:

```java
@Test
void given_productionFailurePath_when_requestingRoute_then_returnsUsefulNotFoundHtml()
    throws Exception {
  try (var app =
      App.start(
          new String[] {},
          Map.of(
              "TOKTRAK_DATA_DIR", directory.toString(),
              "TOKTRAK_BASE_URL", "https://toktrak.test",
              "TOKTRAK_PORT", "0"))) {
    var response = get(app, "/debug/error");
    assertEquals(404, response.statusCode());
    assertTrue(response.body().contains("Route not found."));
    assertTrue(response.body().contains("<code>/debug/error</code>"));
    assertTrue(response.body().contains("Request ID"));
    assertFalse(response.body().contains("DEBUG"));
    assertFalse(response.body().contains("IllegalStateException"));
  }
}
```

Update the path-limit test to assert browser HTML:

```java
assertEquals("text/html; charset=utf-8", response.header("content-type"));
assertTrue(response.body().contains("Request URI is too long."));
assertTrue(response.body().contains("URI exceeds 2048-byte limit"));
```

- [ ] **Step 2: Strengthen saturated admission coverage**

In `HttpAdmissionTest`, rename the test to
`given_saturatedProductionExecutor_when_requestingBrowserAndApiRoutes_then_returnsTypedServiceUnavailable`,
construct `Router` with `devAuth=false`, change the first request path from
`/health` to `/nope`, and import `java.nio.charset.StandardCharsets`. Read the
browser error body and assert:

```java
assertEquals("text/html; charset=utf-8", connection.getHeaderField("Content-Type"));
String body = new String(input.readAllBytes(), StandardCharsets.UTF_8);
assertTrue(body.contains("Server is busy. Try again."));
assertTrue(body.contains("Request ID"));
```

After disconnecting that request and while the same executor remains saturated,
make the API request:

```java
var apiConnection =
    (HttpURLConnection)
        URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/api/nope")
            .toURL()
            .openConnection();
apiConnection.setConnectTimeout(2_000);
apiConnection.setReadTimeout(2_000);
apiConnection.setRequestMethod("GET");
apiConnection.setRequestProperty("Connection", "close");
try {
  assertEquals(503, apiConnection.getResponseCode());
  assertEquals("application/json; charset=utf-8", apiConnection.getHeaderField("Content-Type"));
  try (var input = apiConnection.getErrorStream()) {
    assertTrue(
        new String(input.readAllBytes(), StandardCharsets.UTF_8)
            .contains("\"code\":\"server_busy\""));
  }
} finally {
  apiConnection.disconnect();
}
```

- [ ] **Step 3: Run HTTP tests and verify RED**

Run:

```text
mise run test -- tests/toktrak.tests/toktrak/tests/HttpServerTest.java tests/toktrak.tests/toktrak/tests/HttpAdmissionTest.java
```

Expected: failures because browser errors still return the old HTML or JSON and
`/debug/error` still returns 404.

- [ ] **Step 4: Add shared error response selection to `Router`**

Add these constants:

```java
private static final String URI_TOO_LONG_PATH = "URI exceeds 2048-byte limit";
private static final String INVALID_METHOD = "(invalid method)";
```

Move raw/decoded path extraction and the 414 limit block before method
validation so an overlong path always receives the fixed bounded display path.
Replace the three admission JSON blocks with calls that preserve existing JSON
messages while supplying useful browser copy:

```java
respondError(
    exchange,
    400,
    "invalid_method",
    "request method is invalid",
    "Request method is invalid.",
    requestId,
    null);
respondError(
    exchange,
    414,
    "uri_too_long",
    "request URI is too long",
    "Request URI is too long.",
    requestId,
    null);
respondError(
    exchange,
    503,
    "server_busy",
    "server is busy",
    "Server is busy. Try again.",
    requestId,
    null);
```

Generate one UUID request ID immediately before each call. Keep each existing
`finally { exchange.close(); }` block.

Replace the accepted-handler catch response with:

```java
respondError(
    exchange,
    500,
    "internal_error",
    "internal server error",
    "Internal server error. Try again.",
    context.requestId(),
    exception);
```

Catch the resulting `IOException` exactly as the current fallback does.

Add:

```java
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
  String path =
      status == 414 ? URI_TOO_LONG_PATH : exchange.getRequestURI().getPath();
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
            status, code, browserMessage, requestId, method, path, failure, devAuth));
  }
}

private static boolean isJsonPath(String path) {
  return path != null && (path.equals("/health") || path.startsWith("/api/"));
}
```

- [ ] **Step 5: Route unknown browser errors and add the dev failure trigger**

Before `/health`, add:

```java
if (devAuth && path.equals("/debug/error") && exchange.getRequestMethod().equals("GET")) {
  throw new IllegalStateException("debug failure");
}
```

Replace the fixed browser 404 response with:

```java
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
```

Keep the API 404 branch before this browser fallback so its existing JSON body
remains unchanged.

- [ ] **Step 6: Run HTTP tests and verify GREEN**

Run:

```text
mise run test -- tests/toktrak.tests/toktrak/tests/HttpServerTest.java tests/toktrak.tests/toktrak/tests/HttpAdmissionTest.java
```

Expected: all selected tests pass.

### Task 3: Apply the accepted presentation

**Files:**

- Modify: `tests/toktrak.tests/toktrak/tests/HealthModeTest.java`
- Modify: `sources/toktrak/toktrak/http/Router.java`

- [ ] **Step 1: Strengthen the stylesheet test**

Replace the exact one-rule CSS assertion with:

```java
assertTrue(
    response.body().startsWith(
        ".environment-banner{background:#b00020;color:white;padding:.5rem;font-weight:800}"));
assertTrue(response.body().contains(".error-page{"));
assertTrue(response.body().contains(".error-page dl{"));
assertTrue(response.body().contains(".error-page dt,.error-page dd{"));
```

Update the POST stylesheet test to expect the new useful development 404:

```java
assertTrue(response.body().contains("Route not found."));
assertTrue(response.body().contains("DEV AUTH · DEBUG"));
```

- [ ] **Step 2: Run the stylesheet test and verify RED**

Run:

```text
mise run test -- tests/toktrak.tests/toktrak/tests/HealthModeTest.java
```

Expected: stylesheet assertions fail because diagnostic styles are absent.

- [ ] **Step 3: Extend `MAIN_CSS`**

Replace the stylesheet constant with:

```java
private static final String MAIN_CSS =
    ".environment-banner{background:#b00020;color:white;padding:.5rem;font-weight:800}"
        + ".error-page{font:16px/1.4 ui-monospace,monospace;max-width:760px;margin:48px auto;"
        + "border:4px solid #111;padding:28px;background:#fff;color:#111}"
        + ".error-page h1{font-size:64px;line-height:1;margin:24px 0 8px}"
        + ".error-page p{font-family:system-ui,sans-serif}"
        + ".error-page dl{display:grid;grid-template-columns:max-content 1fr;"
        + "border-top:3px solid #111;margin-top:28px}"
        + ".error-page dt,.error-page dd{padding:10px;border-bottom:2px solid #111;margin:0}"
        + ".error-page dt{font-weight:800}"
        + ".error-page code{overflow-wrap:anywhere}";
```

- [ ] **Step 4: Run the stylesheet test and verify GREEN**

Run:

```text
mise run test -- tests/toktrak.tests/toktrak/tests/HealthModeTest.java
```

Expected: all selected tests pass.

### Task 4: Mutation, full verification, and commit

**Files:**

- All changed production and test files above.

- [ ] **Step 1: Format changed Java**

Run:

```text
mise run fmt -- sources/toktrak/toktrak/http/ErrorPage.java sources/toktrak/toktrak/http/Router.java tests/toktrak.tests/toktrak/tests/ErrorPageTest.java tests/toktrak.tests/toktrak/tests/HttpServerTest.java tests/toktrak.tests/toktrak/tests/HttpAdmissionTest.java tests/toktrak.tests/toktrak/tests/HealthModeTest.java
```

- [ ] **Step 2: Run focused tests after formatting**

Run:

```text
mise run test -- tests/toktrak.tests/toktrak/tests/ErrorPageTest.java tests/toktrak.tests/toktrak/tests/HttpServerTest.java tests/toktrak.tests/toktrak/tests/HttpAdmissionTest.java tests/toktrak.tests/toktrak/tests/HealthModeTest.java
```

Expected: all selected tests pass.

- [ ] **Step 3: Run mutation testing**

Run:

```text
mise run pit --history -- sources/toktrak/toktrak/http/ErrorPage.java sources/toktrak/toktrak/http/Router.java
```

Inspect survivors and improve focused tests when they expose missing required
behavior. If PIT history errors or results are inconsistent, delete
`output/pit.history` and rerun without `--history`.

- [ ] **Step 4: Run full verification**

Run:

```text
mise run verify
```

Expected: formatting, compilation, lint, build tests, unit tests, and tagged
tests pass without warnings.

- [ ] **Step 5: Commit**

```text
git add sources/toktrak/toktrak/http/ErrorPage.java sources/toktrak/toktrak/http/Router.java tests/toktrak.tests/toktrak/tests/ErrorPageTest.java tests/toktrak.tests/toktrak/tests/HttpServerTest.java tests/toktrak.tests/toktrak/tests/HttpAdmissionTest.java tests/toktrak.tests/toktrak/tests/HealthModeTest.java
git commit -m "feat: add useful browser error pages"
```

### Task 5: Human verification

- [ ] **Step 1: Start development mode**

Run `mise run dev`.

- [ ] **Step 2: Verify the development 404**

Open `http://127.0.0.1:8080/nope`. Confirm the accepted diagnostic-list layout,
404 explanation, path, request ID, stable code, method, and return link.

- [ ] **Step 3: Verify the development 500**

Open `http://127.0.0.1:8080/debug/error`. Confirm status 500, public message,
`IllegalStateException: debug failure`, request data, no stack trace, and no CSP
console violation.

This plan intentionally stops at Phase 1: Durable Server Core. Future roadmap
phases need separate detailed plans.
