package toktrak.tests;

import toktrak.*;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import toktrak.dev.DevData;

final class DevDataTest {
  @TempDir Path dir;

  @Test
  void copiesCorpusToDisposableDataDir() throws Exception {
    var corpus = dir.resolve("corpus.ndjson");
    Files.writeString(corpus, "{\"id\":\"00000000-0000-4000-8000-000000000001\",\"at\":\"2026-07-10T00:00:00Z\",\"type\":\"dev-test\",\"schemaVersion\":1,\"actor\":\"system\",\"data\":{}}\n");
    var dataDir = dir.resolve("data");
    DevData.prepareDisposableCorpus(corpus, dataDir);
    assertEquals(Files.readString(corpus), Files.readString(dataDir.resolve("events.ndjson")));
  }

  @Test
  void appStartupCopiesCorpusBeforeOpeningEventLog() throws Exception {
    var corpus = dir.resolve("corpus.ndjson");
    Files.writeString(corpus, "{\"id\":\"00000000-0000-4000-8000-000000000001\",\"at\":\"2026-07-10T00:00:00Z\",\"type\":\"dev-test\",\"schemaVersion\":1,\"actor\":\"system\",\"data\":{}}\n");
    var dataDir = dir.resolve("app-data");
    try (var app = App.start(
        new String[] {"--corpus", corpus.toString()},
        Map.of("TOKTRAK_DEV_AUTH", "true", "TOKTRAK_PORT", "0", "TOKTRAK_DATA_DIR", dataDir.toString()))) {
      assertNotNull(app);
      assertEquals(Files.readString(corpus), Files.readString(dataDir.resolve("events.ndjson")));
    }
  }
}
