package toktrak.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import toktrak.App;
import toktrak.dev.DevData;

final class DevDataTest {
  private static final String EVENT =
      "{\"id\":\"00000000-0000-4000-8000-000000000001\","
          + "\"at\":\"2026-07-10T00:00:00Z\",\"type\":\"dev-test\",\"schemaVersion\":1,"
          + "\"actor\":\"system\",\"data\":{}}\n";

  @TempDir Path directory;

  @Test
  void given_developmentCorpus_when_preparingData_then_copiesCorpus() throws Exception {
    var corpus = directory.resolve("corpus.ndjson");
    Files.writeString(corpus, EVENT);
    var dataDirectory = directory.resolve("data");
    DevData.prepareDisposableCorpus(corpus, dataDirectory);
    assertEquals(
        Files.readString(corpus), Files.readString(dataDirectory.resolve("events.ndjson")));
  }

  @Test
  void given_oversizedCorpusAndExistingData_when_copyFails_then_preservesExistingData()
      throws Exception {
    var corpus = directory.resolve("oversized.ndjson");
    Files.writeString(corpus, "x".repeat(17));
    var dataDirectory = directory.resolve("preserved-data");
    Files.createDirectories(dataDirectory);
    var destination = dataDirectory.resolve("events.ndjson");
    Files.writeString(destination, "keep\n");

    var exception =
        assertThrows(
            IllegalStateException.class,
            () -> DevData.prepareDisposableCorpusForTest(corpus, dataDirectory, 16));
    assertEquals("dev corpus exceeds 16 bytes", exception.getMessage());
    assertEquals("keep\n", Files.readString(destination));
  }

  @Test
  void given_developmentCorpus_when_startingApp_then_eventLogMatchesCorpus() throws Exception {
    var corpus = directory.resolve("corpus.ndjson");
    Files.writeString(corpus, EVENT);
    var dataDirectory = directory.resolve("app-data");
    try (var app =
        App.start(
            new String[] {"--corpus", corpus.toString()},
            Map.of(
                "TOKTRAK_DEV_AUTH", "true",
                "TOKTRAK_PORT", "0",
                "TOKTRAK_DATA_DIR", dataDirectory.toString()))) {
      assertNotNull(app);
      assertEquals(
          Files.readString(corpus), Files.readString(dataDirectory.resolve("events.ndjson")));
    }
  }
}
