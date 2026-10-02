package toktrak.tests;

import static com.diffplug.selfie.Selfie.expectSelfie;

import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import toktrak.http.BaseView;
import toktrak.http.BaseView.CurrencySwitch;
import toktrak.http.BaseView.CurrentPage;
import toktrak.http.BaseView.RuntimeMode;
import toktrak.http.Changelog;
import toktrak.http.ChangesView;
import toktrak.http.ChangesViewRenderer;
import toktrak.http.CreatedTokenView;
import toktrak.http.CreatedTokenViewRenderer;
import toktrak.http.ErrorPage;
import toktrak.http.HomeView;
import toktrak.http.HomeView.SessionState;
import toktrak.http.HomeViewRenderer;
import toktrak.http.HttpSupport;
import toktrak.http.RequestContext;
import toktrak.http.TokenListView;
import toktrak.http.TokenListView.LastUsage;
import toktrak.http.TokenListView.PageLink;
import toktrak.http.TokenListView.TokenState;
import toktrak.http.TokenListViewRenderer;
import toktrak.json.Json;
import toktrak.log.JsonLogFormatter;
import toktrak.store.EventEnvelope;

@Tag("snapshot")
final class SnapshotTest {

  private static final String STYLESHEET = "/assets/main.0123456789abcdef0123456789abcdef.css";
  private static final String DATASTAR = "/assets/datastar.0123456789abcdef0123456789abcdef.js";
  private static final String CLIPBOARD = "/assets/clipboard.0123456789abcdef0123456789abcdef.js";
  private static final String PLATFORM = "/assets/platform.0123456789abcdef0123456789abcdef.js";
  private static final String TOKEN = "tt_" + "A".repeat(43);
  private static final String SCRIPT = "const TOKEN = \"" + TOKEN + "\";\n";
  private static final String FAVICON = "/assets/favicon.0123456789abcdef0123456789abcdef.svg";
  private static final String LOGO_MARK = "/assets/logo-mark.0123456789abcdef0123456789abcdef.svg";
  private static final String LOGO_WORDMARK =
      "/assets/logo-wordmark.0123456789abcdef0123456789abcdef.svg";
  private static final String LOGO_WORDMARK_DARK =
      "/assets/logo-wordmark-dark.0123456789abcdef0123456789abcdef.svg";
  private static final String LOGO_LOCKUP =
      "/assets/logo-lockup.0123456789abcdef0123456789abcdef.svg";
  private static final String LOGO_LOCKUP_DARK =
      "/assets/logo-lockup-dark.0123456789abcdef0123456789abcdef.svg";

  @Test
  void given_productionHomeView_when_renderingEncodedHtml_then_matchesApprovedDocument()
      throws Exception {
    expectSelfie(
            decode(
                HttpSupport.renderEncoded(
                    HomeViewRenderer.of(),
                    new HomeView(base("TokTrak", false), SessionState.SIGNED_OUT))))
        .toMatchDisk();
  }

  @Test
  void given_developmentHomeView_when_renderingEncodedHtml_then_matchesApprovedDocument()
      throws Exception {
    expectSelfie(
            decode(
                HttpSupport.renderEncoded(
                    HomeViewRenderer.of(),
                    new HomeView(base("TokTrak", true), SessionState.SIGNED_IN))))
        .toMatchDisk();
  }

  @Test
  void given_changesView_when_renderingEncodedHtml_then_matchesApprovedDocument() throws Exception {
    var changesBase =
        new BaseView(
            "What’s new · TokTrak",
            STYLESHEET,
            DATASTAR,
            FAVICON,
            LOGO_WORDMARK,
            LOGO_WORDMARK_DARK,
            LOGO_LOCKUP,
            LOGO_LOCKUP_DARK,
            RuntimeMode.PRODUCTION,
            CurrentPage.CHANGES,
            CurrencySwitch.disabled(),
            "v2");
    String releaseNotes =
        """
        # Changelog

        ## v2

        - Keep scheduled tracker uploads working when native schedulers omit Node.js
          from `PATH`.
        - Reject elevated Windows tracker installation before creating user-scoped
          scheduler state.
        - Restrict automatic development login to loopback authorities.
        - Document Node.js 22 as the minimum tracker runtime.
        - Show the running release and its change history in the UI.

        ## v1

        - Bind OIDC discovery metadata to the configured issuer before trusting
          advertised endpoints.
        - Restore production EUR exchange-rate refresh.
        - Make Windows trackers catch up after sleep and continue on battery power.

        ## v0

        - Initial TokTrak server, dashboard, workstation tracker, and production
          container.
        - Opt-in remote JMX/JFR diagnostics with shell-free `jcmd` and `jfr` tooling.
        """;
    var view = new ChangesView(changesBase, Changelog.parse("v2", releaseNotes));

    expectSelfie(decode(HttpSupport.renderEncoded(ChangesViewRenderer.of(), view))).toMatchDisk();
  }

  @Test
  void given_emptyTokenListView_when_renderingEncodedHtml_then_matchesApprovedDocument()
      throws Exception {
    var view =
        new TokenListView(
            trackerBase("My Tracker · TokTrak", false),
            "csrf-value",
            List.of(),
            1,
            PageLink.unavailable(),
            PageLink.unavailable(),
            PLATFORM);
    expectSelfie(decode(HttpSupport.renderEncoded(TokenListViewRenderer.of(), view))).toMatchDisk();
  }

  @Test
  void given_representativeTokenListView_when_renderingEncodedHtml_then_matchesApprovedDocument()
      throws Exception {
    var rows =
        List.of(
            new TokenListView.TokenRow(
                "Laptop <primary>",
                "00000000-0000-4000-8000-000000000001",
                TokenState.ACTIVE,
                LastUsage.never()),
            new TokenListView.TokenRow(
                "Old workstation",
                "00000000-0000-4000-8000-000000000002",
                TokenState.REVOKED,
                LastUsage.at("2026-07-10T12:00:00Z")));
    var view =
        new TokenListView(
            trackerBase("My Tracker · TokTrak", true),
            "csrf-value",
            rows,
            2,
            PageLink.available("/tokens?page=1"),
            PageLink.available("/tokens?page=3"),
            PLATFORM);
    expectSelfie(decode(HttpSupport.renderEncoded(TokenListViewRenderer.of(), view))).toMatchDisk();
  }

  @Test
  void given_createdTokenView_when_renderingEncodedHtml_then_matchesApprovedDocument()
      throws Exception {
    var view =
        new CreatedTokenView(
            trackerBase("Tracker token created · TokTrak", false),
            TOKEN,
            SCRIPT,
            "0".repeat(64),
            CLIPBOARD,
            PLATFORM);
    expectSelfie(decode(HttpSupport.renderEncoded(CreatedTokenViewRenderer.of(), view)))
        .toMatchDisk();
  }

  @Test
  void given_productionErrorPage_when_renderingHtml_then_matchesApprovedDocument() {
    expectSelfie(
            ErrorPage.render(
                418,
                "teapot",
                "cannot brew coffee",
                "00000000-0000-4000-8000-000000000001",
                "POST",
                "/coffee",
                STYLESHEET,
                FAVICON,
                LOGO_MARK,
                new IllegalStateException("debug failure"),
                false))
        .toMatchDisk();
  }

  @Test
  void given_healthPayload_when_serializingJson_then_matchesApprovedDocument() {
    var payload = new java.util.LinkedHashMap<String, String>();
    payload.put("status", "degraded");
    payload.put("reason", "writes_failed");
    expectSelfie(Json.write(payload)).toMatchDisk();
  }

  @Test
  void given_eventEnvelope_when_serializingJson_then_matchesApprovedDocument() {
    var event =
        new EventEnvelope(
            java.util.UUID.fromString("00000000-0000-4000-8000-000000000001"),
            Instant.parse("2026-07-10T00:00:00Z"),
            "projection-snapshot",
            1,
            "system",
            Map.of("eventCount", 7));
    expectSelfie(Json.write(event)).toMatchDisk();
  }

  @Test
  void given_requestLog_when_formattingJson_then_matchesApprovedLine() throws Exception {
    var record = new LogRecord(Level.INFO, "request complete");
    record.setInstant(Instant.EPOCH);
    var context =
        new RequestContext("request-1", "GET", "/health", "user-1", "token-1", "production");
    String line = RequestContext.with(context, () -> new JsonLogFormatter().format(record));
    expectSelfie(line).toMatchDisk();
  }

  private static BaseView trackerBase(String title, boolean development) {
    return new BaseView(
        title,
        STYLESHEET,
        DATASTAR,
        FAVICON,
        LOGO_WORDMARK,
        LOGO_WORDMARK_DARK,
        LOGO_LOCKUP,
        LOGO_LOCKUP_DARK,
        development ? RuntimeMode.DEVELOPMENT : RuntimeMode.PRODUCTION,
        CurrentPage.TRACKER,
        CurrencySwitch.disabled(),
        development ? "dev" : "v2");
  }

  private static BaseView base(String title, boolean development) {
    return new BaseView(
        title,
        STYLESHEET,
        DATASTAR,
        FAVICON,
        LOGO_WORDMARK,
        LOGO_WORDMARK_DARK,
        LOGO_LOCKUP,
        LOGO_LOCKUP_DARK,
        development ? RuntimeMode.DEVELOPMENT : RuntimeMode.PRODUCTION,
        CurrentPage.NONE,
        CurrencySwitch.disabled(),
        development ? "dev" : "v2");
  }

  private static String decode(byte[] bytes) throws Exception {
    return StandardCharsets.UTF_8
        .newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString();
  }
}
