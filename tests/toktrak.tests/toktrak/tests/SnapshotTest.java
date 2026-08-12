package toktrak.tests;

import static com.diffplug.selfie.Selfie.expectSelfie;

import java.time.Instant;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import toktrak.http.ErrorPage;
import toktrak.http.RequestContext;
import toktrak.json.Json;
import toktrak.log.JsonLogFormatter;
import toktrak.store.EventEnvelope;

@Tag("snapshot")
final class SnapshotTest {
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
}
