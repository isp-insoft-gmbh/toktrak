package toktrak.tests;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import toktrak.http.ErrorPage;

final class ErrorPageTest {
  private static final String STYLESHEET_URL = "/assets/main.0123456789abcdef0123456789abcdef.css";

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
            STYLESHEET_URL,
            new IllegalStateException("debug failure"),
            false);
    assertTrue(html.contains("<h1>418</h1>"));
    assertTrue(html.contains("cannot brew coffee"));
    assertTrue(html.contains("<code>/coffee</code>"));
    assertTrue(html.contains("00000000-0000-4000-8000-000000000001"));
    assertTrue(html.contains("href=\"" + STYLESHEET_URL + "\""));
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
            "/assets/main.<hash>&\".css",
            new IllegalStateException("debug <failure> & safe"),
            true);
    assertTrue(html.contains("DEV AUTH · DEBUG"));
    assertTrue(html.contains("internal_&lt;error&gt;"));
    assertTrue(html.contains("failed &lt;publicly&gt;"));
    assertTrue(html.contains("request&amp;1"));
    assertTrue(html.contains("/bad?&lt;value&gt;&quot;&#39;"));
    assertTrue(html.contains("href=\"/assets/main.&lt;hash&gt;&amp;&quot;.css\""));
    assertTrue(html.contains("<dt>Method</dt><dd><code>GET</code></dd>"));
    assertTrue(html.contains("java.lang.IllegalStateException: debug &lt;failure&gt; &amp; safe"));
    assertFalse(html.contains("<failure>"));
    assertFalse(html.contains("at toktrak"));
  }

  @Test
  void given_failureWithoutMessage_when_renderingDevelopmentPage_then_reportsMissingMessage() {
    String html =
        ErrorPage.render(
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
        () ->
            ErrorPage.render(
                400, "bad", "bad", "request", "GET", "/", STYLESHEET_URL, null, false));
    assertDoesNotThrow(
        () ->
            ErrorPage.render(
                599, "bad", "bad", "request", "GET", "/", STYLESHEET_URL, null, false));
  }

  @Test
  void given_errorStatusOutsideRange_when_renderingPage_then_rejectsStatus() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ErrorPage.render(
                399, "bad", "bad", "request", "GET", "/", STYLESHEET_URL, null, false));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ErrorPage.render(
                600, "bad", "bad", "request", "GET", "/", STYLESHEET_URL, null, false));
  }

  @Test
  void given_blankRendererInputs_when_renderingPage_then_rejectsInputs() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ErrorPage.render(
                500, "", "message", "request", "GET", "/", STYLESHEET_URL, null, false));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ErrorPage.render(500, "code", "", "request", "GET", "/", STYLESHEET_URL, null, false));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ErrorPage.render(500, "code", "message", "", "GET", "/", STYLESHEET_URL, null, false));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ErrorPage.render(
                500, "code", "message", "request", "", "/", STYLESHEET_URL, null, false));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ErrorPage.render(
                500, "code", "message", "request", "GET", "", STYLESHEET_URL, null, false));
  }

  @Test
  void given_invalidStylesheetUrl_when_renderingPage_then_rejectsInput() {
    assertThrows(
        IllegalArgumentException.class,
        () -> ErrorPage.render(500, "code", "message", "request", "GET", "/", "", null, false));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ErrorPage.render(
                500, "code", "message", "request", "GET", "/", "x".repeat(257), null, false));
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
            STYLESHEET_URL,
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
            "&".repeat(256),
            new IllegalStateException("&".repeat(8_192)),
            true);
    assertTrue(html.length() <= 128 * 1024);
    assertFalse(html.contains("&&"));
  }
}
