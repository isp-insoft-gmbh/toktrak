package toktrak.http;

import java.util.Objects;

/// Independently renders bounded browser errors without templates or generated code.
///
/// This Java-only boundary prevents a template, renderer, or encoded-output failure from breaking
/// its own fallback response.
public final class ErrorPage {
  private static final int CODE_CHARACTERS_MAX = 128;
  private static final int MESSAGE_CHARACTERS_MAX = 1024;
  private static final int REQUEST_ID_CHARACTERS_MAX = 64;
  private static final int METHOD_CHARACTERS_MAX = 32;
  private static final int PATH_CHARACTERS_MAX = 2 * 1024;
  private static final int STYLESHEET_URL_CHARACTERS_MAX = 256;
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
      String stylesheetUrl,
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
    requireText(stylesheetUrl, STYLESHEET_URL_CHARACTERS_MAX, "stylesheetUrl");

    var html = new StringBuilder(2 * 1024);
    html.append("<!doctype html><meta charset=\"utf-8\"><title>")
        .append(status)
        .append(" · TokTrak</title><link rel=\"stylesheet\" href=\"")
        .append(escape(stylesheetUrl))
        .append("\"><main class=\"error-page\">");
    if (debug) {
      html.append("<div class=\"environment-banner\">DEV AUTH · DEBUG</div>");
    }
    html.append("<h1>")
        .append(status)
        .append("</h1><p>")
        .append(escape(message))
        .append("</p><dl>");
    detail(html, "Path", path);
    detail(html, "Request ID", requestId);
    if (debug) {
      detail(html, "Code", code);
      detail(html, "Method", method);
      if (failure != null) {
        String failureClass = bounded(failure.getClass().getName(), EXCEPTION_CLASS_CHARACTERS_MAX);
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
        case '"' -> escaped.append("&quot;");
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
