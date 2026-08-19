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
import toktrak.http.BaseView.Currency;
import toktrak.http.BaseView.CurrencySwitch;
import toktrak.http.BaseView.CurrentPage;
import toktrak.http.BaseView.RuntimeMode;
import toktrak.http.CreatedTokenView;
import toktrak.http.HomeView;
import toktrak.http.HomeView.SessionState;
import toktrak.http.HomeViewRenderer;
import toktrak.http.HttpSupport;
import toktrak.http.TokenListView;
import toktrak.http.TokenListView.LastUsage;
import toktrak.http.TokenListView.PageLink;
import toktrak.http.TokenListView.TokenState;
import toktrak.http.TokenListViewRenderer;

final class RenderingTest {
  private static final String STYLESHEET = "/assets/main.0123456789abcdef0123456789abcdef.css";
  private static final String DATASTAR = "/assets/datastar.0123456789abcdef0123456789abcdef.js";
  private static final String CLIPBOARD = "/assets/clipboard.0123456789abcdef0123456789abcdef.js";
  private static final String PLATFORM = "/assets/platform.0123456789abcdef0123456789abcdef.js";
  private static final String TOKEN = "tt_" + "A".repeat(43);
  private static final String SCRIPT = "const TOKEN = \"" + TOKEN + "\";";
  private static final String SHA256 = "0".repeat(64);
  private static final String FAVICON = "/assets/favicon.0123456789abcdef0123456789abcdef.svg";
  private static final String LOGO_WORDMARK =
      "/assets/logo-wordmark.0123456789abcdef0123456789abcdef.svg";
  private static final String LOGO_WORDMARK_DARK =
      "/assets/logo-wordmark-dark.0123456789abcdef0123456789abcdef.svg";
  private static final String LOGO_LOCKUP =
      "/assets/logo-lockup.0123456789abcdef0123456789abcdef.svg";
  private static final String LOGO_LOCKUP_DARK =
      "/assets/logo-lockup-dark.0123456789abcdef0123456789abcdef.svg";
  private static final BaseView TOKEN_BASE =
      new BaseView(
          "My Tracker · TokTrak",
          STYLESHEET,
          DATASTAR,
          FAVICON,
          LOGO_WORDMARK,
          LOGO_WORDMARK_DARK,
          LOGO_LOCKUP,
          LOGO_LOCKUP_DARK,
          RuntimeMode.PRODUCTION,
          CurrentPage.TRACKER,
          CurrencySwitch.disabled());

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
            "<&\"'>", "00000000-0000-4000-8000-000000000001", TokenState.ACTIVE, LastUsage.never());
    var view =
        new TokenListView(
            TOKEN_BASE,
            "<&\"'>",
            List.of(row),
            1,
            PageLink.unavailable(),
            PageLink.unavailable(),
            PLATFORM);

    String html =
        new String(
            HttpSupport.renderEncoded(TokenListViewRenderer.of(), view), StandardCharsets.UTF_8);

    assertTrue(html.contains("&lt;&amp;&quot;&#x27;&gt;"), html);
    assertFalse(html.contains("<&\"'>"), html);
  }

  @Test
  void given_mutableRows_when_constructingView_then_defensivelyCopiesCollection() {
    var rows = new ArrayList<TokenListView.TokenRow>();
    var view =
        new TokenListView(
            TOKEN_BASE, "csrf", rows, 1, PageLink.unavailable(), PageLink.unavailable(), PLATFORM);

    rows.add(
        new TokenListView.TokenRow(
            "Laptop",
            "00000000-0000-4000-8000-000000000001",
            TokenState.ACTIVE,
            LastUsage.never()));

    assertEquals(List.of(), view.tokens());
    assertThrows(UnsupportedOperationException.class, () -> view.tokens().clear());
  }

  @Test
  void given_semanticViewStates_when_projectingTemplatePredicates_then_returnsExpectedValues() {
    var base =
        new BaseView(
            "Visualizations · TokTrak",
            STYLESHEET,
            DATASTAR,
            FAVICON,
            LOGO_WORDMARK,
            LOGO_WORDMARK_DARK,
            LOGO_LOCKUP,
            LOGO_LOCKUP_DARK,
            RuntimeMode.DEVELOPMENT,
            CurrentPage.VISUALIZATIONS,
            CurrencySwitch.enabled(Currency.EUR, "/visualizations?currency=USD", Currency.USD));
    var productionHome =
        new BaseView(
            "TokTrak",
            STYLESHEET,
            DATASTAR,
            FAVICON,
            LOGO_WORDMARK,
            LOGO_WORDMARK_DARK,
            LOGO_LOCKUP,
            LOGO_LOCKUP_DARK,
            RuntimeMode.PRODUCTION,
            CurrentPage.NONE,
            CurrencySwitch.disabled());
    var token =
        new TokenListView.TokenRow(
            "Laptop",
            "00000000-0000-4000-8000-000000000001",
            TokenState.REVOKED,
            LastUsage.at("2026-07-10T12:00:00Z"));
    var unusedToken =
        new TokenListView.TokenRow(
            "Desktop",
            "00000000-0000-4000-8000-000000000002",
            TokenState.ACTIVE,
            LastUsage.never());
    var view =
        new TokenListView(
            TOKEN_BASE,
            "csrf",
            List.of(token),
            2,
            PageLink.available("/tokens?page=1"),
            PageLink.available("/tokens?page=3"),
            PLATFORM);
    var firstPage =
        new TokenListView(
            TOKEN_BASE,
            "csrf",
            List.of(),
            1,
            PageLink.unavailable(),
            PageLink.unavailable(),
            PLATFORM);

    assertAll(
        () -> assertFalse(productionHome.development()),
        () -> assertFalse(productionHome.navigation()),
        () -> assertFalse(productionHome.visualizationsCurrent()),
        () -> assertFalse(productionHome.currencySwitch()),
        () -> assertTrue(base.development()),
        () -> assertTrue(base.navigation()),
        () -> assertFalse(base.overviewCurrent()),
        () -> assertTrue(base.visualizationsCurrent()),
        () -> assertFalse(base.scopeCurrent()),
        () -> assertFalse(base.trackerCurrent()),
        () -> assertTrue(base.currencySwitch()),
        () -> assertFalse(base.usd()),
        () -> assertEquals("/visualizations?currency=USD", base.currencySwitchUrl()),
        () -> assertEquals("USD", base.currencySwitchLabel()),
        () -> assertTrue(new HomeView(base, SessionState.SIGNED_IN).signedIn()),
        () -> assertFalse(new HomeView(base, SessionState.SIGNED_OUT).signedIn()),
        () -> assertTrue(view.hasPrevious()),
        () -> assertEquals("/tokens?page=1", view.previousUrl()),
        () -> assertTrue(view.hasNext()),
        () -> assertEquals("/tokens?page=3", view.nextUrl()),
        () -> assertFalse(firstPage.hasPrevious()),
        () -> assertFalse(firstPage.hasNext()),
        () -> assertEquals("revoked", token.status()),
        () -> assertTrue(token.hasLastUsed()),
        () -> assertEquals("2026-07-10T12:00:00Z", token.lastUsed()),
        () -> assertFalse(token.active()),
        () -> assertFalse(TokenListView.PageLink.unavailable().available()),
        () -> assertFalse(unusedToken.hasLastUsed()),
        () -> assertEquals("", unusedToken.lastUsed()),
        () -> assertTrue(unusedToken.active()));
  }

  @Test
  void given_invalidViewStates_when_constructingModels_then_rejectsThem() {
    var row =
        new TokenListView.TokenRow(
            "Laptop", "00000000-0000-4000-8000-000000000001", TokenState.ACTIVE, LastUsage.never());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new TokenListView(
                TOKEN_BASE,
                "",
                List.of(),
                1,
                PageLink.unavailable(),
                PageLink.unavailable(),
                PLATFORM));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new TokenListView(
                TOKEN_BASE,
                "csrf",
                List.of(),
                0,
                PageLink.unavailable(),
                PageLink.unavailable(),
                PLATFORM));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new TokenListView(
                TOKEN_BASE,
                "csrf",
                java.util.Collections.nCopies(101, row),
                1,
                PageLink.unavailable(),
                PageLink.unavailable(),
                PLATFORM));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new TokenListView.TokenRow(
                "Laptop", "not-a-uuid", TokenState.ACTIVE, LastUsage.never()));
    assertThrows(
        IllegalArgumentException.class,
        () -> new TokenListView.LastUsage(TokenListView.LastUsageState.USED, ""));
  }

  @Test
  void given_unvalidatedUrls_when_constructingViews_then_rejectsThem() {
    assertDoesNotThrow(
        () ->
            new BaseView(
                "x".repeat(128),
                STYLESHEET,
                DATASTAR,
                FAVICON,
                LOGO_WORDMARK,
                LOGO_WORDMARK_DARK,
                LOGO_LOCKUP,
                LOGO_LOCKUP_DARK,
                RuntimeMode.PRODUCTION,
                CurrentPage.NONE,
                CurrencySwitch.disabled()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new BaseView(
                "x".repeat(129),
                STYLESHEET,
                DATASTAR,
                FAVICON,
                LOGO_WORDMARK,
                LOGO_WORDMARK_DARK,
                LOGO_LOCKUP,
                LOGO_LOCKUP_DARK,
                RuntimeMode.PRODUCTION,
                CurrentPage.NONE,
                CurrencySwitch.disabled()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            baseView(
                "https://evil.example/x.css",
                FAVICON,
                LOGO_WORDMARK,
                LOGO_WORDMARK_DARK,
                LOGO_LOCKUP,
                LOGO_LOCKUP_DARK));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            baseView(
                STYLESHEET,
                "/favicon.svg",
                LOGO_WORDMARK,
                LOGO_WORDMARK_DARK,
                LOGO_LOCKUP,
                LOGO_LOCKUP_DARK));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            baseView(
                STYLESHEET,
                FAVICON,
                "/logo-wordmark.svg",
                LOGO_WORDMARK_DARK,
                LOGO_LOCKUP,
                LOGO_LOCKUP_DARK));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            baseView(
                STYLESHEET,
                FAVICON,
                LOGO_WORDMARK,
                "/logo-wordmark-dark.svg",
                LOGO_LOCKUP,
                LOGO_LOCKUP_DARK));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            baseView(
                STYLESHEET,
                FAVICON,
                LOGO_WORDMARK,
                LOGO_WORDMARK_DARK,
                "/logo-lockup.svg",
                LOGO_LOCKUP_DARK));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            baseView(
                STYLESHEET,
                FAVICON,
                LOGO_WORDMARK,
                LOGO_WORDMARK_DARK,
                LOGO_LOCKUP,
                "/logo-lockup-dark.svg"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new TokenListView(
                TOKEN_BASE,
                "csrf",
                List.of(),
                1,
                PageLink.available("https://evil.example/tokens"),
                PageLink.unavailable(),
                PLATFORM));
    assertThrows(
        IllegalArgumentException.class,
        () -> new CreatedTokenView(TOKEN_BASE, TOKEN, SCRIPT, SHA256, "/clipboard.js", PLATFORM));
    assertDoesNotThrow(
        () -> new CreatedTokenView(TOKEN_BASE, TOKEN, SCRIPT, SHA256, CLIPBOARD, PLATFORM));
    String exactScript = TOKEN + " ".repeat(512 * 1024 - TOKEN.length());
    assertDoesNotThrow(
        () -> new CreatedTokenView(TOKEN_BASE, TOKEN, exactScript, SHA256, CLIPBOARD, PLATFORM));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new CreatedTokenView(
                TOKEN_BASE, TOKEN, exactScript + " ", SHA256, CLIPBOARD, PLATFORM));
  }

  private static BaseView baseView(
      String stylesheet,
      String favicon,
      String logoWordmark,
      String logoWordmarkDark,
      String logoLockup,
      String logoLockupDark) {
    return new BaseView(
        "TokTrak",
        stylesheet,
        DATASTAR,
        favicon,
        logoWordmark,
        logoWordmarkDark,
        logoLockup,
        logoLockupDark,
        RuntimeMode.PRODUCTION,
        CurrentPage.NONE,
        CurrencySwitch.disabled());
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
