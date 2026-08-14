package toktrak.tests;

import static org.junit.jupiter.api.Assertions.*;

import io.jstach.jstachio.Output;
import io.jstach.jstachio.Template;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import toktrak.http.BaseView;
import toktrak.http.HomeViewRenderer;
import toktrak.http.HttpSupport;
import toktrak.http.TokenListView;
import toktrak.http.TokenListViewRenderer;

final class RenderingTest {
  private static final String STYLESHEET = "/assets/main.0123456789abcdef0123456789abcdef.css";
  private static final BaseView TOKEN_BASE =
      new BaseView("My Tracker · TokTrak", STYLESHEET, false);

  @Test
  void given_encodedOutputBoundary_when_rendering_then_acceptsLimitAndRejectsOverflow()
      throws Exception {
    assertEquals(
        HttpSupport.ENCODED_HTML_BYTES_MAX,
        HttpSupport.renderEncoded(
                new FixedSizeRenderer(HttpSupport.ENCODED_HTML_BYTES_MAX), "model")
            .length);
    assertThrows(
        java.io.IOException.class,
        () ->
            HttpSupport.renderEncoded(
                new FixedSizeRenderer(HttpSupport.ENCODED_HTML_BYTES_MAX + 1), "model"));
  }

  @Test
  void given_generatedRenderer_when_inspectingEncoding_then_usesPreEncodedUtf8() {
    assertInstanceOf(Template.EncodedTemplate.class, HomeViewRenderer.of());
    assertEquals(StandardCharsets.UTF_8, HomeViewRenderer.of().templateCharset());
  }

  @Test
  void given_hostileViewText_when_rendering_then_escapesEveryDynamicValue() throws Exception {
    var row =
        new TokenListView.TokenRow(
            "<&\"'>", "00000000-0000-4000-8000-000000000001", "active", false, "", true);
    var view = new TokenListView(TOKEN_BASE, "<&\"'>", List.of(row), 1, false, "", false, "");

    String html =
        new String(
            HttpSupport.renderEncoded(TokenListViewRenderer.of(), view), StandardCharsets.UTF_8);

    assertTrue(html.contains("&lt;&amp;&quot;&#x27;&gt;"), html);
    assertFalse(html.contains("<&\"'>"), html);
  }

  @Test
  void given_mutableRows_when_constructingView_then_defensivelyCopiesCollection() {
    var rows = new ArrayList<TokenListView.TokenRow>();
    var view = new TokenListView(TOKEN_BASE, "csrf", rows, 1, false, "", false, "");

    rows.add(
        new TokenListView.TokenRow(
            "Laptop", "00000000-0000-4000-8000-000000000001", "active", false, "", true));

    assertEquals(List.of(), view.tokens());
    assertThrows(UnsupportedOperationException.class, () -> view.tokens().clear());
  }

  @Test
  void given_invalidViewStates_when_constructingModels_then_rejectsThem() {
    var row =
        new TokenListView.TokenRow(
            "Laptop", "00000000-0000-4000-8000-000000000001", "active", false, "", true);
    assertThrows(
        IllegalArgumentException.class,
        () -> new TokenListView(TOKEN_BASE, "", List.of(), 1, false, "", false, ""));
    assertThrows(
        IllegalArgumentException.class,
        () -> new TokenListView(TOKEN_BASE, "csrf", List.of(), 0, false, "", false, ""));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new TokenListView(
                TOKEN_BASE,
                "csrf",
                java.util.Collections.nCopies(101, row),
                1,
                false,
                "",
                false,
                ""));
    assertThrows(
        IllegalArgumentException.class,
        () -> new TokenListView.TokenRow("Laptop", "not-a-uuid", "active", false, "", true));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new TokenListView.TokenRow(
                "Laptop", "00000000-0000-4000-8000-000000000001", "revoked", false, "", true));
  }

  @Test
  void given_unvalidatedUrls_when_constructingViews_then_rejectsThem() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new BaseView("TokTrak", "https://evil.example/x.css", false));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new TokenListView(
                TOKEN_BASE, "csrf", List.of(), 1, true, "https://evil.example/tokens", false, ""));
  }

  private record FixedSizeRenderer(int size) implements Template.EncodedTemplate<String> {
    @Override
    public <A extends Output<ExceptionType>, ExceptionType extends Exception> A execute(
        String model, A output) throws ExceptionType {
      return output;
    }

    @Override
    public <A extends Output.EncodedOutput<ExceptionType>, ExceptionType extends Exception> A write(
        String model, A output) throws ExceptionType {
      output.write(new byte[size]);
      return output;
    }

    @Override
    public String templateName() {
      return "fixed";
    }

    @Override
    public String templatePath() {
      return "fixed";
    }

    @Override
    public Class<?> templateContentType() {
      return Object.class;
    }

    @Override
    public Charset templateCharset() {
      return StandardCharsets.UTF_8;
    }

    @Override
    public String templateMediaType() {
      return "text/plain";
    }

    @Override
    public Function<String, String> templateEscaper() {
      return Function.identity();
    }

    @Override
    public Function<Object, String> templateFormatter() {
      return String::valueOf;
    }

    @Override
    public boolean supportsType(Class<?> type) {
      return type.equals(String.class);
    }

    @Override
    public Class<?> modelClass() {
      return String.class;
    }
  }
}
