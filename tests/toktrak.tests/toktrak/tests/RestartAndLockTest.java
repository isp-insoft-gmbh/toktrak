package toktrak.tests;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import toktrak.*;
import toktrak.store.WriteCommand;

final class RestartAndLockTest {
  @TempDir Path dir;

  @Test
  void restartRebuildsProjectionFromEventLog() throws Exception {
    var env =
        Map.of("TOKTRAK_DEV_AUTH", "true", "TOKTRAK_PORT", "0", "TOKTRAK_DATA_DIR", dir.toString());
    try (var app = App.start(new String[] {}, env)) {
      app.writer().submit(WriteCommand.devTest("system")).get(2, TimeUnit.SECONDS);
      assertEquals(1, app.projection().eventCount());
    }
    try (var app = App.start(new String[] {}, env)) {
      assertEquals(1, app.projection().eventCount());
    }
  }

  @Test
  void failedCorpusStartupCannotReplaceLiveEventLog() throws Exception {
    var environment =
        Map.of(
            "TOKTRAK_DEV_AUTH", "true",
            "TOKTRAK_PORT", "0",
            "TOKTRAK_DATA_DIR", dir.toString());
    var corpus = dir.resolve("replacement.ndjson");
    Files.writeString(
        corpus,
        "{\"id\":\"00000000-0000-4000-8000-000000000001\","
            + "\"at\":\"2026-07-10T00:00:00Z\",\"type\":\"dev-test\","
            + "\"schemaVersion\":1,\"actor\":\"system\",\"data\":{}}\n");
    try (var first = App.start(new String[] {}, environment)) {
      var eventLog = dir.resolve("events.ndjson");
      String contentBefore = Files.readString(eventLog);
      assertThrows(
          IllegalStateException.class,
          () -> App.start(new String[] {"--corpus", corpus.toString()}, environment));
      assertEquals(contentBefore, Files.readString(eventLog));
      assertEquals(0, first.projection().eventCount());
    }
  }

  @Test
  void secondAppCannotOpenSameDataDir() throws Exception {
    var env =
        Map.of("TOKTRAK_DEV_AUTH", "true", "TOKTRAK_PORT", "0", "TOKTRAK_DATA_DIR", dir.toString());
    try (var first = App.start(new String[] {}, env)) {
      assertNotNull(first);
      var ex = assertThrows(IllegalStateException.class, () -> App.start(new String[] {}, env));
      assertEquals("TokTrak data directory is already locked", ex.getMessage());
    }
  }
}
