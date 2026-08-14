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
import toktrak.http.CreatedTokenView;
import toktrak.http.CreatedTokenViewRenderer;
import toktrak.http.ErrorPage;
import toktrak.http.HomeView;
import toktrak.http.HomeViewRenderer;
import toktrak.http.HttpSupport;
import toktrak.http.RequestContext;
import toktrak.http.TokenListView;
import toktrak.http.TokenListViewRenderer;
import toktrak.json.Json;
import toktrak.log.JsonLogFormatter;
import toktrak.store.EventEnvelope;

@Tag("snapshot")
final class SnapshotTest {
  private static final String STYLESHEET = "/assets/main.0123456789abcdef0123456789abcdef.css";

  @Test
  void given_productionHomeView_when_renderingEncodedHtml_then_matchesApprovedDocument()
      throws Exception {
    expectSelfie(
            decode(
                HttpSupport.renderEncoded(
                    HomeViewRenderer.of(), new HomeView(base("TokTrak", false), false))))
        .toMatchDisk();
  }

  @Test
  void given_developmentHomeView_when_renderingEncodedHtml_then_matchesApprovedDocument()
      throws Exception {
    expectSelfie(
            decode(
                HttpSupport.renderEncoded(
                    HomeViewRenderer.of(), new HomeView(base("TokTrak", true), true))))
        .toMatchDisk();
  }

  @Test
  void given_emptyTokenListView_when_renderingEncodedHtml_then_matchesApprovedDocument()
      throws Exception {
    var view =
        new TokenListView(
            base("My Tracker · TokTrak", false), "csrf-value", List.of(), 1, false, "", false, "");
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
                "active",
                false,
                "",
                true),
            new TokenListView.TokenRow(
                "Old workstation",
                "00000000-0000-4000-8000-000000000002",
                "revoked",
                true,
                "2026-07-10T12:00:00Z",
                false));
    var view =
        new TokenListView(
            base("My Tracker · TokTrak", true),
            "csrf-value",
            rows,
            2,
            true,
            "/tokens?page=1",
            true,
            "/tokens?page=3");
    expectSelfie(decode(HttpSupport.renderEncoded(TokenListViewRenderer.of(), view))).toMatchDisk();
  }

  @Test
  void given_createdTokenView_when_renderingEncodedHtml_then_matchesApprovedDocument()
      throws Exception {
    var view =
        new CreatedTokenView(
            base("Tracker token created · TokTrak", false), "tt_" + "A".repeat(43));
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
                "/assets/main.0123456789abcdef0123456789abcdef.css",
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

  private static BaseView base(String title, boolean development) {
    return new BaseView(title, STYLESHEET, development);
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
