package toktrak.tests;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import toktrak.http.Assets;
import toktrak.http.TrackerScript;
import toktrak.json.Json;

final class TrackerScriptTest {
  private static final String TOKEN = "tt_" + "A".repeat(43);
  @TempDir Path directory;

  @Test
  void given_invalidTemplateInputs_when_renderingTracker_then_rejectsThem() {
    byte[] template = Assets.load().privateBytes("tracker.mjs");
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new TrackerScript(
                "export {};".getBytes(StandardCharsets.UTF_8),
                URI.create("https://toktrak.example")));
    assertThrows(
        IllegalArgumentException.class,
        () -> new TrackerScript(template, URI.create("https://toktrak.example/path")));
    byte[] exactLimit = new byte[512 * 1024];
    Arrays.fill(exactLimit, (byte) ' ');
    byte[] placeholders = "__TOKTRAK_BASE_URL____TOKTRAK_TOKEN__".getBytes(StandardCharsets.UTF_8);
    System.arraycopy(placeholders, 0, exactLimit, 0, placeholders.length);
    assertDoesNotThrow(() -> new TrackerScript(exactLimit, URI.create("https://toktrak.example")));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new TrackerScript(
                new byte[exactLimit.length + 1], URI.create("https://toktrak.example")));
    var scripts = new TrackerScript(template, URI.create("https://toktrak.example/"));
    assertThrows(IllegalArgumentException.class, () -> scripts.render("invalid"));

    TrackerScript.Personalized personalized = scripts.render(TOKEN);
    byte[] changed = personalized.bytes();
    changed[0] = 0;
    assertNotEquals(0, personalized.bytes()[0]);
    assertTrue(personalized.text().contains(TOKEN));
    assertTrue(personalized.sha256().matches("[0-9a-f]{64}"));
  }

  @Test
  void given_fakeCcusageAndServer_when_runningFullAndDaily_then_retriesUploadsPartialsAndUpdates()
      throws Exception {
    var usageRequests = new AtomicInteger();
    var updateRequests = new AtomicInteger();
    var uploads = new ArrayList<String>();
    HttpServer server =
        HttpServer.create(new InetSocketAddress(InetAddress.ofLiteral("127.0.0.1"), 0), 8);
    URI baseUri = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    var scripts = new TrackerScript(Assets.load().privateBytes("tracker.mjs"), baseUri);
    TrackerScript.Personalized script = scripts.render(TOKEN);
    server.createContext(
        "/api/usage",
        exchange -> {
          assertAuthorization(exchange);
          String body =
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
          int request = usageRequests.incrementAndGet();
          if (request == 2) {
            respond(exchange, 503, "{}");
          } else {
            uploads.add(body);
            respond(exchange, 200, "{}");
          }
        });
    server.createContext(
        "/api/tracker",
        exchange -> {
          assertAuthorization(exchange);
          updateRequests.incrementAndGet();
          exchange.getResponseHeaders().set("X-TokTrak-SHA256", script.sha256());
          respond(exchange, 200, script.bytes());
        });
    server.start();
    try {
      Path tracker = directory.resolve("toktrak.mjs");
      Files.write(tracker, script.bytes());
      Path commandLog = directory.resolve("ccusage.log");
      Path piLog = directory.resolve("pi.log");
      Path piSessions = directory.resolve("pi sessions");
      Path environmentPiSessions = directory.resolve("environment pi sessions");
      Path piAgent = directory.resolve("pi agent");
      Files.createDirectory(piSessions);
      Files.createDirectory(environmentPiSessions);
      Files.createDirectory(piAgent);
      Files.writeString(
          piAgent.resolve("settings.json"),
          Json.write(Map.of("sessionDir", piSessions.toString())));
      Path commandDirectory = directory.resolve("bin");
      Files.createDirectory(commandDirectory);
      writeFakeNpx(commandDirectory);

      ProcessResult full =
          runTracker(
              tracker, commandDirectory, commandLog, "0", "full", environmentPiSessions.toString());
      assertEquals(0, full.exitCode(), full.output());
      ProcessResult daily = runTracker(tracker, commandDirectory, commandLog, "1", "daily", null);
      assertEquals(0, daily.exitCode(), daily.output());
      assertFalse(full.output().contains("DeprecationWarning"), full.output());
      assertFalse(daily.output().contains("DeprecationWarning"), daily.output());
      assertTrue(full.output().contains("starting full mode on "), full.output());
      assertTrue(full.output().contains("collecting full usage snapshot"), full.output());
      assertTrue(full.output().contains("reading daily usage with ccusage@20.0.17"), full.output());
      assertTrue(full.output().contains("daily usage ready (1 rows)"), full.output());
      assertTrue(full.output().contains("3/3 usage reports ready"), full.output());
      assertTrue(full.output().contains("reading codex daily detail"), full.output());
      assertTrue(full.output().contains("codex daily detail ready (1 rows)"), full.output());
      assertTrue(full.output().contains("reading codex session detail"), full.output());
      assertTrue(full.output().contains("codex session detail ready (1 rows)"), full.output());
      assertTrue(full.output().contains("checking for tracker update"), full.output());
      assertTrue(full.output().contains("tracker already current"), full.output());
      assertTrue(
          full.output()
              .contains("Pi sessions: " + environmentPiSessions + " (PI_CODING_AGENT_SESSION_DIR)"),
          full.output());
      assertTrue(
          daily.output().contains("Pi sessions: " + piSessions + " (Pi settings)"), daily.output());
      assertTrue(daily.output().contains("2/3 usage reports ready"), daily.output());

      assertEquals(3, usageRequests.get());
      assertEquals(2, updateRequests.get());
      assertEquals(2, uploads.size());
      assertTrue(uploads.get(0).contains("\"full\":true"), uploads.get(0));
      assertTrue(uploads.get(0).contains("\"session\":{\"ok\":true"), uploads.get(0));
      String expectedProject =
          System.getProperty("os.name").startsWith("Windows")
              ? "C:\\work\\project"
              : "/work/project";
      assertTrue(
          uploads.get(0).contains("\"projectPath\":" + Json.write(expectedProject)),
          uploads.get(0));
      assertTrue(
          uploads.get(0).contains("\"sourceReports\":{\"codex\":{\"daily\":{\"ok\":true"),
          uploads.get(0));
      assertTrue(uploads.get(0).contains("\"reasoningOutputTokens\":7"), uploads.get(0));
      assertTrue(uploads.get(0).contains("\"futureField\":{\"preserved\":true}"), uploads.get(0));
      assertTrue(uploads.get(1).contains("\"full\":false"), uploads.get(1));
      assertTrue(uploads.get(1).contains("\"blocks\":{\"ok\":false"), uploads.get(1));
      assertTrue(
          uploads.get(1).contains("\"sourceReports\":{\"codex\":{\"daily\":{\"ok\":true"),
          uploads.get(1));
      assertTrue(daily.output().contains("retrying once"), daily.output());
      assertArrayEquals(script.bytes(), Files.readAllBytes(tracker));

      List<String> commands = Files.readAllLines(commandLog);
      assertEquals(10, commands.size(), commands.toString());
      assertTrue(commands.subList(0, 5).stream().noneMatch(value -> value.contains("--since")));
      assertTrue(commands.subList(5, 10).stream().allMatch(value -> value.contains("--since")));
      assertTrue(commands.stream().allMatch(value -> value.contains("ccusage@20.0.17")));
      assertEquals(
          List.of(
              environmentPiSessions.toString(),
              environmentPiSessions.toString(),
              environmentPiSessions.toString(),
              environmentPiSessions.toString(),
              environmentPiSessions.toString(),
              piSessions.toString(),
              piSessions.toString(),
              piSessions.toString(),
              piSessions.toString(),
              piSessions.toString()),
          Files.readAllLines(piLog));
    } finally {
      server.stop(0);
    }
  }

  @Test
  void given_crossOriginRedirect_when_checkingForTrackerUpdate_then_rejectsRedirect()
      throws Exception {
    var redirectedRequests = new AtomicInteger();
    HttpServer redirected =
        HttpServer.create(new InetSocketAddress(InetAddress.ofLiteral("127.0.0.1"), 0), 8);
    HttpServer origin =
        HttpServer.create(new InetSocketAddress(InetAddress.ofLiteral("127.0.0.1"), 0), 8);
    URI baseUri = URI.create("http://127.0.0.1:" + origin.getAddress().getPort());
    var scripts = new TrackerScript(Assets.load().privateBytes("tracker.mjs"), baseUri);
    TrackerScript.Personalized original = scripts.render(TOKEN);
    TrackerScript.Personalized replacement = scripts.render("tt_" + "B".repeat(43));
    URI redirectedUri =
        URI.create("http://127.0.0.1:" + redirected.getAddress().getPort() + "/replacement");
    redirected.createContext(
        "/replacement",
        exchange -> {
          redirectedRequests.incrementAndGet();
          exchange.getResponseHeaders().set("X-TokTrak-SHA256", replacement.sha256());
          respond(exchange, 200, replacement.bytes());
        });
    origin.createContext("/api/usage", exchange -> respond(exchange, 200, "{}"));
    origin.createContext(
        "/api/tracker",
        exchange -> {
          exchange.getResponseHeaders().set("Location", redirectedUri.toString());
          exchange.sendResponseHeaders(307, -1);
          exchange.close();
        });
    redirected.start();
    origin.start();
    try {
      Path tracker = directory.resolve("redirected-update.mjs");
      Files.write(tracker, original.bytes());
      Path commandDirectory = directory.resolve("redirect-bin");
      Files.createDirectory(commandDirectory);
      writeFakeNpx(commandDirectory);

      ProcessResult result =
          runTracker(
              tracker, commandDirectory, directory.resolve("redirect.log"), "0", "full", null);

      assertEquals(0, result.exitCode(), result.output());
      assertTrue(result.output().contains("self-update failed"), result.output());
      assertEquals(0, redirectedRequests.get());
      assertArrayEquals(original.bytes(), Files.readAllBytes(tracker));
    } finally {
      origin.stop(0);
      redirected.stop(0);
    }
  }

  @Test
  void given_schedulerInputs_when_generatingDefinitions_then_usesNativeUserSchedulers()
      throws Exception {
    var scripts =
        new TrackerScript(
            Assets.load().privateBytes("tracker.mjs"), URI.create("https://toktrak.example"));
    Path tracker = directory.resolve("toktrak.mjs");
    Files.write(tracker, scripts.render(TOKEN).bytes());
    String program =
        "const m=await"
            + " import(process.argv[1]);console.log(JSON.stringify({linux:m.linuxUnits('/node"
            + " path','/script path','/pi sessions'),mac:m.macPlist('/node path','/script"
            + " path','/pi sessions'),win:m.windowsTaskXml('C:\\\\Node\\\\n"
            + "ode.exe','C:\\\\User\\\\toktrak.mjs','C:\\\\Pi Sessions')}));";

    Process process =
        new ProcessBuilder(
                "node", "--input-type=module", "--eval", program, tracker.toUri().toString())
            .redirectErrorStream(true)
            .start();
    assertTrue(process.waitFor(20, TimeUnit.SECONDS));
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

    assertEquals(0, process.exitValue(), output);
    assertTrue(output.contains("WantedBy=timers.target"), output);
    assertTrue(output.contains("RandomizedDelaySec=30m"), output);
    assertTrue(output.contains("de.isp-insoft.toktrak"), output);
    assertTrue(output.contains("LaunchAgents") || output.contains("StartCalendarInterval"), output);
    assertTrue(output.contains("<RunLevel>LeastPrivilege</RunLevel>"), output);
    assertTrue(output.contains("<StartWhenAvailable>true</StartWhenAvailable>"), output);
    assertTrue(
        output.contains("<DisallowStartIfOnBatteries>false</DisallowStartIfOnBatteries>"), output);
    assertTrue(output.contains("<StopIfGoingOnBatteries>false</StopIfGoingOnBatteries>"), output);
    assertTrue(output.contains("--scheduled"), output);
    assertTrue(output.contains("--pi-path"), output);
    assertTrue(output.contains("pi sessions") || output.contains("Pi Sessions"), output);
  }

  private ProcessResult runTracker(
      Path tracker,
      Path commandDirectory,
      Path commandLog,
      String partial,
      String mode,
      String sessionDirectory)
      throws Exception {
    ProcessBuilder builder = new ProcessBuilder("node", tracker.toString(), mode);
    builder.redirectErrorStream(true);
    String oldPath = builder.environment().getOrDefault("PATH", "");
    builder.environment().put("PATH", commandDirectory + java.io.File.pathSeparator + oldPath);
    builder.environment().put("FAKE_LOG", commandLog.toString());
    builder.environment().put("FAKE_PI_LOG", directory.resolve("pi.log").toString());
    builder.environment().put("FAKE_PARTIAL", partial);
    builder.environment().remove("PI_AGENT_DIR");
    if (sessionDirectory == null) builder.environment().remove("PI_CODING_AGENT_SESSION_DIR");
    else builder.environment().put("PI_CODING_AGENT_SESSION_DIR", sessionDirectory);
    builder.environment().put("PI_CODING_AGENT_DIR", directory.resolve("pi agent").toString());
    Process process = builder.start();
    assertTrue(process.waitFor(Duration.ofSeconds(30)));
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    return new ProcessResult(process.exitValue(), output);
  }

  private void writeFakeNpx(Path commandDirectory) throws IOException {
    if (System.getProperty("os.name").startsWith("Windows")) {
      Files.writeString(
          commandDirectory.resolve("npx.cmd"),
          "@echo off\r\n"
              + "echo %*>>\"%FAKE_LOG%\"\r\n"
              + "echo %PI_AGENT_DIR%>>\"%FAKE_PI_LOG%\"\r\n"
              + "if \"%3\"==\"daily\" echo"
              + " {\"daily\":[{\"period\":\"2026-08-15\",\"agent\":\"all\",\"inputTokens\":1,\"outputTokens\":1,\"cacheCreationTokens\":0,\"cacheReadTokens\":0,\"totalTokens\":2,\"totalCost\":0.1}]}\r\n"
              + "if \"%3\"==\"session\" echo"
              + " {\"session\":[{\"period\":\"codex-session\",\"agent\":\"codex\",\"metadata\":{\"lastActivity\":\"2026-08-15T00:00:00Z\"}}]}\r\n"
              + "if \"%3\"==\"blocks\" if \"%FAKE_PARTIAL%\"==\"1\" exit /b 7\r\n"
              + "if \"%3\"==\"blocks\" echo {\"blocks\":[]}\r\n"
              + "if \"%3\"==\"codex\" if \"%4\"==\"daily\" echo"
              + " {\"daily\":[{\"date\":\"2026-08-15\",\"reasoningOutputTokens\":7}],\"totals\":{}}\r\n"
              + "if \"%3\"==\"codex\" if \"%4\"==\"session\" echo"
              + " {\"sessions\":[{\"sessionId\":\"codex-session\",\"directory\":\"C:\\\\work\\\\project\",\"futureField\":{\"preserved\":true}}],\"totals\":{}}\r\n");
      return;
    }
    Path command = commandDirectory.resolve("npx");
    Files.writeString(
        command,
        "#!/bin/sh\n"
            + "printf '%s\\n"
            + "' \"$*\" >>\"$FAKE_LOG\"\n"
            + "printf '%s\\n"
            + "' \"$PI_AGENT_DIR\" >>\"$FAKE_PI_LOG\"\n"
            + "case \"$3\" in\n"
            + "daily) printf '%s\\n"
            + "' '{\"daily\":[{\"period\":\"2026-08-15\",\"agent\":\"all\",\"inputTokens\":1,\"outputTokens\":1,\"cacheCreationTokens\":0,\"cacheReadTokens\":0,\"totalTokens\":2,\"totalCost\":0.1}]}'"
            + " ;;\n"
            + "session) printf '%s\\n"
            + "' '{\"session\":[{\"period\":\"codex-session\",\"agent\":\"codex\",\"metadata\":{\"lastActivity\":\"2026-08-15T00:00:00Z\"}}]}'"
            + " ;;\n"
            + "blocks) [ \"$FAKE_PARTIAL\" = 1 ] && exit 7; printf '%s\\n"
            + "' '{\"blocks\":[]}' ;;\n"
            + "codex) if [ \"$4\" = daily ]; then printf '%s\\n"
            + "' '{\"daily\":[{\"date\":\"2026-08-15\",\"reasoningOutputTokens\":7}],\"totals\":{}}';"
            + " else printf '%s\\n"
            + "' '{\"sessions\":[{\"sessionId\":\"codex-session\",\"directory\":\"/work/project\",\"futureField\":{\"preserved\":true}}],\"totals\":{}}';"
            + " fi ;;\n"
            + "esac\n");
    Set<PosixFilePermission> permissions =
        EnumSet.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE);
    Files.setPosixFilePermissions(command, permissions);
  }

  private static void assertAuthorization(HttpExchange exchange) {
    assertEquals("Bearer " + TOKEN, exchange.getRequestHeaders().getFirst("Authorization"));
  }

  private static void respond(HttpExchange exchange, int status, String body) throws IOException {
    respond(exchange, status, body.getBytes(StandardCharsets.UTF_8));
  }

  private static void respond(HttpExchange exchange, int status, byte[] body) throws IOException {
    exchange.sendResponseHeaders(status, body.length);
    try (var output = exchange.getResponseBody()) {
      output.write(body);
    }
  }

  private record ProcessResult(int exitCode, String output) {}
}
