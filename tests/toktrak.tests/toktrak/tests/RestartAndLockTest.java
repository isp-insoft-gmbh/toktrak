package toktrak.tests;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
  void given_existingEventLog_when_restartingApp_then_rebuildsProjection() throws Exception {
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
  void given_developmentSessionCookie_when_restartingApp_then_acceptsCookie() throws Exception {
    var env =
        Map.of("TOKTRAK_DEV_AUTH", "true", "TOKTRAK_PORT", "0", "TOKTRAK_DATA_DIR", dir.toString());
    Path corpus = dir.resolve("corpus.ndjson");
    Files.writeString(corpus, "");
    String[] args = {"--corpus", corpus.toString()};
    var client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    String cookie;
    try (var app = App.start(args, env)) {
      var response = get(client, app, "/login", null);
      assertEquals(302, response.statusCode());
      cookie = response.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
    }
    try (var app = App.start(args, env)) {
      assertEquals(200, get(client, app, "/tokens", cookie).statusCode());
    }
  }

  @Test
  void given_liveEventLog_when_corpusStartupFails_then_preservesEventLog() throws Exception {
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
  void given_lockedDataDirectory_when_startingSecondApp_then_rejectsStartup() throws Exception {
    var env =
        Map.of("TOKTRAK_DEV_AUTH", "true", "TOKTRAK_PORT", "0", "TOKTRAK_DATA_DIR", dir.toString());
    try (var first = App.start(new String[] {}, env)) {
      assertNotNull(first);
      var ex = assertThrows(IllegalStateException.class, () -> App.start(new String[] {}, env));
      assertEquals("TokTrak data directory is already locked", ex.getMessage());
    }
  }

  private static HttpResponse<String> get(HttpClient client, App app, String path, String cookie)
      throws Exception {
    var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + path)).GET();
    if (cookie != null) builder.header("Cookie", cookie);
    return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
  }
}
