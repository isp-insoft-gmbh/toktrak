package toktrak.tests;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import toktrak.http.ErrorPage;

final class ErrorPageTest {
  private static final String STYLESHEET_URL = "/assets/main.0123456789abcdef0123456789abcdef.css";
  private static final String FAVICON_URL = "/assets/favicon.0123456789abcdef0123456789abcdef.svg";
  private static final String LOGO_MARK_URL =
      "/assets/logo-mark.0123456789abcdef0123456789abcdef.svg";

  @Test
  void given_arbitraryErrorStatus_when_renderingPage_then_returnsUsefulProductionHtml() {
    String html =
        render(
            418,
            "teapot",
            "cannot brew coffee",
            "00000000-0000-4000-8000-000000000001",
            "POST",
            "/coffee",
            STYLESHEET_URL,
            new IllegalStateException("debug failure"),
            false);
    assertTrue(html.contains("<h1>418</h1>"));
    assertTrue(html.contains("cannot brew coffee"));
    assertTrue(html.contains("<code>/coffee</code>"));
    assertTrue(html.contains("00000000-0000-4000-8000-000000000001"));
    assertTrue(html.contains("href=\"" + STYLESHEET_URL + "\""));
    assertTrue(html.contains("href=\"" + FAVICON_URL + "\""));
    assertTrue(html.contains("src=\"" + LOGO_MARK_URL + "\""));
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
        render(
            500,
            "internal_<error>",
            "failed <publicly>",
            "request&1",
            "GET",
            "/bad?<value>\"'",
            STYLESHEET_URL,
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
  void given_failureWithoutMessage_when_renderingDevelopmentPage_then_reportsMissingMessage() {
    String html =
        render(
            500,
            "internal_error",
            "internal server error",
            "request",
            "GET",
            "/",
            STYLESHEET_URL,
            new IllegalStateException(),
            true);
    assertTrue(html.contains("java.lang.IllegalStateException: (no message)"));
  }

  @Test
  void given_errorStatusBoundaries_when_renderingPage_then_acceptsBoundaries() {
    assertDoesNotThrow(
        () -> render(400, "bad", "bad", "request", "GET", "/", STYLESHEET_URL, null, false));
    assertDoesNotThrow(
        () -> render(599, "bad", "bad", "request", "GET", "/", STYLESHEET_URL, null, false));
  }

  @Test
  void given_errorStatusOutsideRange_when_renderingPage_then_rejectsStatus() {
    assertThrows(
        IllegalArgumentException.class,
        () -> render(399, "bad", "bad", "request", "GET", "/", STYLESHEET_URL, null, false));
    assertThrows(
        IllegalArgumentException.class,
        () -> render(600, "bad", "bad", "request", "GET", "/", STYLESHEET_URL, null, false));
  }

  @Test
  void given_blankRendererInputs_when_renderingPage_then_rejectsInputs() {
    assertThrows(
        IllegalArgumentException.class,
        () -> render(500, "", "message", "request", "GET", "/", STYLESHEET_URL, null, false));
    assertThrows(
        IllegalArgumentException.class,
        () -> render(500, "code", "", "request", "GET", "/", STYLESHEET_URL, null, false));
    assertThrows(
        IllegalArgumentException.class,
        () -> render(500, "code", "message", "", "GET", "/", STYLESHEET_URL, null, false));
    assertThrows(
        IllegalArgumentException.class,
        () -> render(500, "code", "message", "request", "", "/", STYLESHEET_URL, null, false));
    assertThrows(
        IllegalArgumentException.class,
        () -> render(500, "code", "message", "request", "GET", "", STYLESHEET_URL, null, false));
  }

  @Test
  void given_invalidAssetUrls_when_renderingPage_then_rejectsInput() {
    assertThrows(
        IllegalArgumentException.class,
        () -> render(500, "code", "message", "request", "GET", "/", "", null, false));
    assertThrows(
        IllegalArgumentException.class,
        () -> render(500, "code", "message", "request", "GET", "/", "x".repeat(257), null, false));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            render(500, "code", "message", "request", "GET", "/", "/assets/main.css", null, false));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ErrorPage.render(
                500,
                "code",
                "message",
                "request",
                "GET",
                "/",
                STYLESHEET_URL,
                "/favicon.svg",
                LOGO_MARK_URL,
                null,
                false));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ErrorPage.render(
                500,
                "code",
                "message",
                "request",
                "GET",
                "/",
                STYLESHEET_URL,
                FAVICON_URL,
                "/logo-mark.svg",
                null,
                false));
  }

  @Test
  void given_exceptionMessageAboveLimit_when_renderingPage_then_boundsDiagnostic() {
    String html =
        render(
            500,
            "internal_error",
            "internal server error",
            "request",
            "GET",
            "/",
            STYLESHEET_URL,
            new IllegalStateException("x".repeat(8_193)),
            true);
    assertTrue(html.contains("x".repeat(8_192)));
    assertFalse(html.contains("x".repeat(8_193)));
  }

  @Test
  void given_maximumEscapingInputs_when_renderingPage_then_returnsBoundedHtml() {
    String html =
        render(
            599,
            "&".repeat(128),
            "&".repeat(1_024),
            "&".repeat(64),
            "&".repeat(32),
            "&".repeat(2_048),
            STYLESHEET_URL,
            new IllegalStateException("&".repeat(8_192)),
            true);
    assertTrue(html.length() <= 128 * 1024);
    assertFalse(html.contains("&&"));
  }

  private static String render(
      int status,
      String code,
      String message,
      String requestId,
      String method,
      String path,
      String stylesheetUrl,
      Throwable failure,
      boolean debug) {
    return ErrorPage.render(
        status,
        code,
        message,
        requestId,
        method,
        path,
        stylesheetUrl,
        FAVICON_URL,
        LOGO_MARK_URL,
        failure,
        debug);
  }
}
