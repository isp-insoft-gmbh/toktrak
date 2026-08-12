package toktrak.tests;

import static org.junit.jupiter.api.Assertions.*;

import java.util.logging.Level;
import java.util.logging.LogRecord;
import org.junit.jupiter.api.Test;
import toktrak.http.RequestContext;
import toktrak.log.JsonLogFormatter;

final class JsonLogFormatterTest {
  @Test
  void given_infoLogRecord_when_formattingRecord_then_returnsLineWithExpectedFieldsAndNoEmail() {
    var record = new LogRecord(Level.INFO, "hello");
    var line = new JsonLogFormatter().format(record);
    assertTrue(line.endsWith("\n"));
    assertTrue(line.contains("\"level\":\"INFO\""));
    assertTrue(line.contains("\"message\":\"hello\""));
    assertFalse(line.contains("email"));
  }

  @Test
  void given_requestContext_when_formattingRecord_then_returnsBoundedContextFields()
      throws Exception {
    var context =
        new RequestContext("request-1", "POST", "/api/usage", "user-1", "token-1", "prod");
    var record = new LogRecord(Level.WARNING, "x".repeat(8_193));
    record.setInstant(java.time.Instant.EPOCH);

    String line = RequestContext.with(context, () -> new JsonLogFormatter().format(record));

    assertTrue(line.contains("\"timestamp\":\"1970-01-01T00:00:00Z\""));
    assertTrue(line.contains("\"requestId\":\"request-1\""));
    assertTrue(line.contains("\"method\":\"POST\""));
    assertTrue(line.contains("\"path\":\"/api/usage\""));
    assertTrue(line.contains("\"mode\":\"prod\""));
    assertTrue(line.contains("\"userId\":\"user-1\""));
    assertTrue(line.contains("\"tokenId\":\"token-1\""));
    assertTrue(line.contains("x".repeat(8_192)));
    assertFalse(line.contains("x".repeat(8_193)));
  }

  @Test
  void given_requestContextWithoutIdentity_when_formattingRecord_then_omitsIdentityFields()
      throws Exception {
    var context = new RequestContext("request-1", "GET", "/", null, null, "dev");

    String line =
        RequestContext.with(
            context, () -> new JsonLogFormatter().format(new LogRecord(Level.INFO, "hello")));

    assertTrue(line.contains("\"requestId\":\"request-1\""));
    assertFalse(line.contains("userId"));
    assertFalse(line.contains("tokenId"));
  }

  @Test
  void given_nullMessage_when_formattingRecord_then_serializesLiteralNullText() {
    var record = new LogRecord(Level.INFO, null);

    assertTrue(new JsonLogFormatter().format(record).contains("\"message\":\"null\""));
  }
}
