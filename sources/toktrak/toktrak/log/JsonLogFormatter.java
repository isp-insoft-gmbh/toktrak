package toktrak.log;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.logging.Formatter;
import java.util.logging.LogRecord;
import toktrak.http.RequestContext;
import toktrak.json.Json;

public final class JsonLogFormatter extends Formatter {
  private static final int MESSAGE_CHARACTERS_MAX = 8 * 1024;

  @Override
  public String format(LogRecord record) {
    Objects.requireNonNull(record, "record");
    var fields = new LinkedHashMap<String, Object>();
    fields.put("timestamp", Instant.ofEpochMilli(record.getMillis()).toString());
    fields.put("level", record.getLevel().getName());
    fields.put("message", boundedMessage(formatMessage(record)));
    var context = RequestContext.currentOrNull();
    if (context != null) {
      fields.put("requestId", context.requestId());
      fields.put("method", context.method());
      fields.put("path", context.path());
      fields.put("mode", context.mode());
      if (context.userId() != null) fields.put("userId", context.userId());
      if (context.tokenId() != null) fields.put("tokenId", context.tokenId());
    }
    assert fields.size() >= 3 && fields.size() <= 9;
    String line = Json.write(fields) + "\n";
    assert line.endsWith("\n");
    return line;
  }

  private static String boundedMessage(String message) {
    if (message == null) return "null";
    if (message.length() <= MESSAGE_CHARACTERS_MAX) return message;
    return message.substring(0, MESSAGE_CHARACTERS_MAX);
  }
}
