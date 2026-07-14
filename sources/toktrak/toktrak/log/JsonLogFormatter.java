package toktrak.log;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.logging.Formatter;
import java.util.logging.LogRecord;
import toktrak.http.RequestContext;
import toktrak.json.Json;

public final class JsonLogFormatter extends Formatter {
  @Override
  public String format(LogRecord record) {
    var fields = new LinkedHashMap<String, Object>();
    fields.put("timestamp", Instant.ofEpochMilli(record.getMillis()).toString());
    fields.put("level", record.getLevel().getName());
    fields.put("message", formatMessage(record));
    var context = RequestContext.currentOrNull();
    if (context != null) {
      fields.put("requestId", context.requestId());
      fields.put("method", context.method());
      fields.put("path", context.path());
      fields.put("mode", context.mode());
      if (context.userId() != null) fields.put("userId", context.userId());
      if (context.tokenId() != null) fields.put("tokenId", context.tokenId());
    }
    return Json.write(fields) + "\n";
  }
}
