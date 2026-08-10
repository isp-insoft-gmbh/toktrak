# Development Banner Styling Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use /skill:executing-plans to
> implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for
> tracking.

**Goal:** Render the Phase 1 development-authentication banner with its intended
red styling without weakening Content Security Policy.

**Architecture:** The root HTML links one in-memory stylesheet at
`/assets/main.css`. `Router` serves the bounded constant response through a
small `HttpSupport.css` response method; no filesystem asset server or CSP
exception is added.

**Tech Stack:** Java 26, JDK `HttpServer`, JUnit 6, mise, PIT

**Roadmap:** `docs/super/roadmaps/2026-07-13-toktrak-roadmap.md`

**Phase:** Phase 1: Durable Server Core

---

## File structure

- Modify `tests/toktrak.tests/toktrak/tests/HealthModeTest.java`: prove the root
  markup and stylesheet HTTP contract.
- Modify `sources/toktrak/toktrak/http/HttpSupport.java`: add the bounded CSS
  response helper.
- Modify `sources/toktrak/toktrak/http/Router.java`: link and serve the static
  stylesheet constant.

### Task 1: Prove the broken stylesheet contract

**Files:**

- Modify: `tests/toktrak.tests/toktrak/tests/HealthModeTest.java`

- [ ] **Step 1: Strengthen the root-page test**

In `given_devAuthMode_when_requestingRoot_then_returnsDevAuthStrip`, add these
assertions after the existing `DEV AUTH` assertion:

```java
assertTrue(response.body().contains("<link rel=\"stylesheet\" href=\"/assets/main.css\">"));
assertTrue(
    response.body().contains("<div class=\"environment-banner\">DEV AUTH</div>"));
assertFalse(response.body().contains("<div style="));
```

- [ ] **Step 2: Add the stylesheet response tests**

Add these named tests:

```java
@Test
void given_developmentStylesheet_when_requestingAsset_then_returnsEnvironmentBannerStyles()
    throws Exception {
  try (var app =
      App.start(new String[] {}, Map.of("TOKTRAK_DEV_AUTH", "true", "TOKTRAK_PORT", "0"))) {
    var response = get(app, "/assets/main.css");
    assertEquals(200, response.statusCode());
    assertEquals("text/css; charset=utf-8", response.contentType());
    assertEquals(
        ".environment-banner{background:#b00020;color:white;padding:.5rem}", response.body());
    assertEquals(
        "default-src 'self'; frame-ancestors 'none'; base-uri 'none'",
        response.contentSecurityPolicy());
    assertEquals("nosniff", response.contentTypeOptions());
    assertEquals("DENY", response.frameOptions());
    assertEquals("no-referrer", response.referrerPolicy());
  }
}

@Test
void given_postToDevelopmentStylesheet_when_requestingAsset_then_returnsBrowserNotFound()
    throws Exception {
  try (var app =
      App.start(new String[] {}, Map.of("TOKTRAK_DEV_AUTH", "true", "TOKTRAK_PORT", "0"))) {
    var response = request(app, "/assets/main.css", "POST");
    assertEquals(404, response.statusCode());
    assertTrue(response.body().contains("BRUTAL ERROR"));
  }
}
```

Make `get` delegate to a method-aware request helper:

```java
private static Response get(App app, String path) throws Exception {
  return request(app, path, "GET");
}

private static Response request(App app, String path, String method) throws Exception {
  var connection =
      (HttpURLConnection)
          URI.create("http://127.0.0.1:" + app.port() + path).toURL().openConnection();
  connection.setConnectTimeout(2_000);
  connection.setReadTimeout(2_000);
  connection.setRequestMethod(method);
  connection.setRequestProperty("Connection", "close");
  try {
    int statusCode = connection.getResponseCode();
    try (var input =
        statusCode >= 400 ? connection.getErrorStream() : connection.getInputStream()) {
      assertNotNull(input);
      return new Response(
          statusCode,
          connection.getHeaderField("Content-Type"),
          connection.getHeaderField("Content-Security-Policy"),
          connection.getHeaderField("X-Content-Type-Options"),
          connection.getHeaderField("X-Frame-Options"),
          connection.getHeaderField("Referrer-Policy"),
          new String(input.readAllBytes(), StandardCharsets.UTF_8));
    }
  } finally {
    connection.disconnect();
  }
}
```

Change the response record to:

```java
private record Response(
    int statusCode,
    String contentType,
    String contentSecurityPolicy,
    String contentTypeOptions,
    String frameOptions,
    String referrerPolicy,
    String body) {
  private Response {
    assert statusCode >= 100 && statusCode <= 599;
    assert contentType != null && !contentType.isBlank();
    assert contentSecurityPolicy != null && !contentSecurityPolicy.isBlank();
    assert contentTypeOptions != null && !contentTypeOptions.isBlank();
    assert frameOptions != null && !frameOptions.isBlank();
    assert referrerPolicy != null && !referrerPolicy.isBlank();
    assert body != null;
  }
}
```

- [ ] **Step 3: Run the focused test and verify RED**

Run:

```text
mise run test -- tests/toktrak.tests/toktrak/tests/HealthModeTest.java
```

Expected: FAIL because the root lacks the stylesheet link/class and the asset
route returns 404.

### Task 2: Serve the stylesheet without weakening CSP

**Files:**

- Modify: `sources/toktrak/toktrak/http/HttpSupport.java`
- Modify: `sources/toktrak/toktrak/http/Router.java`

- [ ] **Step 1: Add the CSS response helper**

Add beside `html` in `HttpSupport`:

```java
public static void css(HttpExchange exchange, int status, String body) throws IOException {
  assert body != null;
  send(exchange, status, "text/css; charset=utf-8", body.getBytes(StandardCharsets.UTF_8));
}
```

- [ ] **Step 2: Add the bounded stylesheet constant and route**

Add to `Router`:

```java
private static final String MAIN_CSS =
    ".environment-banner{background:#b00020;color:white;padding:.5rem}";
```

In `route`, before `/health`, add:

```java
if (path.equals("/assets/main.css") && exchange.getRequestMethod().equals("GET")) {
  HttpSupport.css(exchange, 200, MAIN_CSS);
  return;
}
```

Replace the root response's inline strip with:

```java
String strip = devAuth ? "<div class=\"environment-banner\">DEV AUTH</div>" : "";
```

Add the stylesheet link after the title:

```java
"<!doctype html><meta charset=\"utf-8\"><title>TokTrak</title>"
    + "<link rel=\"stylesheet\" href=\"/assets/main.css\">"
```

Keep the existing Content Security Policy unchanged.

- [ ] **Step 3: Run the focused test and verify GREEN**

Run:

```text
mise run test -- tests/toktrak.tests/toktrak/tests/HealthModeTest.java
```

Expected: all `HealthModeTest` tests pass.

- [ ] **Step 4: Run mutation testing for changed production code**

Run:

```text
mise run pit --history -- sources/toktrak/toktrak/http/Router.java sources/toktrak/toktrak/http/HttpSupport.java
```

Inspect survivors affecting the stylesheet contract. Improve only focused tests
when a survivor exposes missing behavior. If PIT history errors or results are
inconsistent, delete `output/pit.history` and rerun without `--history`.

- [ ] **Step 5: Run full verification**

Run:

```text
mise run verify
```

Expected: formatting, compilation, lint, build tests, unit tests, and tagged
tests pass without warnings.

- [ ] **Step 6: Commit**

```text
git add sources/toktrak/toktrak/http/HttpSupport.java sources/toktrak/toktrak/http/Router.java tests/toktrak.tests/toktrak/tests/HealthModeTest.java
git commit -m "fix: style development environment banner"
```

### Task 3: Repeat the human browser check

- [ ] **Step 1: Restart the running development server**

Let `mise run dev` restart automatically after the source changes, or stop and
start it again.

- [ ] **Step 2: Verify the root page manually**

Open `http://127.0.0.1:8080/` and confirm the `DEV AUTH` banner has a red
background and contrasting white text.

- [ ] **Step 3: Verify browser security behavior**

Confirm browser developer tools report no Content Security Policy violation for
the banner stylesheet.

This plan intentionally stops at Phase 1: Durable Server Core. Future roadmap
phases need separate detailed plans.
