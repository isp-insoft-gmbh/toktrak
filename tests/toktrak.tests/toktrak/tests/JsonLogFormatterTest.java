package toktrak.tests;

import static org.junit.jupiter.api.Assertions.*;

import java.util.logging.Level;
import java.util.logging.LogRecord;
import org.junit.jupiter.api.Test;
import toktrak.log.JsonLogFormatter;

final class JsonLogFormatterTest {
  @Test
  void formatsOneCompactJsonLine() {
    var record = new LogRecord(Level.INFO, "hello");
    var line = new JsonLogFormatter().format(record);
    assertTrue(line.endsWith("\n"));
    assertTrue(line.contains("\"level\":\"INFO\""));
    assertTrue(line.contains("\"message\":\"hello\""));
    assertFalse(line.contains("email"));
  }
}
