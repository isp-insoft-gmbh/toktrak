package toktrak.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.IntFunction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import toktrak.BuildInfo;
import toktrak.ClockSource;
import toktrak.auth.AuthService;
import toktrak.health.HealthState;
import toktrak.http.Assets;
import toktrak.http.Router;
import toktrak.identity.IdentityService;
import toktrak.projection.Projection;
import toktrak.store.EventLog;
import toktrak.store.Writer;
import toktrak.usage.UsageService;

/// Pins the development-authority guard: with `TOKTRAK_DEV_AUTH` the router only serves requests
/// whose single `Host` header names loopback on the configured development port.
final class DevelopmentAuthorityHttpTest {
  private static final int HOST_CHARACTERS_MAX = 512;
  private static final int RESPONSE_BYTES_MAX = 64 * 1024;
  private static final String MISDIRECTED_CODE = "\"code\":\"misdirected_request\"";
  @TempDir Path directory;

  @Test
  void given_developmentAuthority_when_hostNamesLoopbackOnDevelopmentPort_then_servesRequest()
      throws Exception {
    try (var fixture = Fixture.start(directory, true, Fixture::loopbackBase)) {
      for (String host : List.of("127.0.0.1", "localhost", "LocalHost")) {
        Response response = fixture.get("/health", "Host: " + host + ":" + fixture.port());
        assertEquals(200, response.status(), host);
        assertEquals("{\"status\":\"ok\"}", response.body(), host);
      }
    }
  }

  @Test
  void given_developmentAuthority_when_hostNamesForeignAuthority_then_answersMisdirectedRequest()
      throws Exception {
    try (var fixture = Fixture.start(directory, true, Fixture::loopbackBase)) {
      Response api = fixture.get("/api/nope", "Host: evil.example:" + fixture.port());
      assertEquals(421, api.status());
      assertContentType(api, "application/json; charset=utf-8");
      assertTrue(api.body().contains(MISDIRECTED_CODE), api.body());
      assertTrue(api.body().contains("\"message\":\"request authority is not accepted\""));

      Response health = fixture.get("/health", "Host: evil.example:" + fixture.port());
      assertEquals(421, health.status());
      assertTrue(health.body().contains(MISDIRECTED_CODE), health.body());

      Response page = fixture.get("/", "Host: evil.example:" + fixture.port());
      assertEquals(421, page.status());
      assertContentType(page, "text/html; charset=utf-8");
      assertTrue(page.body().contains("Request authority is not accepted."), page.body());
      assertTrue(page.body().contains("Request ID"), page.body());
    }
  }

  @Test
  void given_developmentAuthority_when_hostPortDiffersFromDevelopmentPort_then_rejectsRequest()
      throws Exception {
    try (var fixture = Fixture.start(directory, true, Fixture::loopbackBase)) {
      int otherPort = fixture.port() == 65_535 ? 65_534 : fixture.port() + 1;
      assertRejected(fixture.get("/health", "Host: 127.0.0.1:" + otherPort));
      assertRejected(fixture.get("/health", "Host: localhost:" + otherPort));
      // Without an explicit port the authority means port 80, never the bound port.
      assertRejected(fixture.get("/health", "Host: 127.0.0.1"));
      assertRejected(fixture.get("/health", "Host: localhost"));
    }
  }

  @Test
  void given_developmentBaseUriOnPort80_when_hostOmitsPort_then_servesOnlyThatAuthority()
      throws Exception {
    try (var fixture = Fixture.start(directory, true, port -> URI.create("http://127.0.0.1:80"))) {
      assertEquals(200, fixture.get("/health", "Host: 127.0.0.1").status());
      assertEquals(200, fixture.get("/health", "Host: localhost:80").status());
      // Port 0 is an explicit port, not an omitted one, so it must not fall back to 80.
      assertRejected(fixture.get("/health", "Host: 127.0.0.1:0"));
      assertRejected(fixture.get("/health", "Host: 127.0.0.1:" + fixture.port()));
    }
  }

  @Test
  void given_developmentAuthority_when_hostHeaderIsMissingRepeatedOrBlank_then_rejectsRequest()
      throws Exception {
    try (var fixture = Fixture.start(directory, true, Fixture::loopbackBase)) {
      String host = "Host: 127.0.0.1:" + fixture.port();
      assertEquals(200, fixture.get("/health", host).status());
      assertRejected(fixture.send("GET /health HTTP/1.0\r\nConnection: close\r\n\r\n"));
      assertRejected(fixture.get("/health", host, host));
      assertRejected(fixture.get("/health", host, "Host: evil.example"));
      assertRejected(fixture.get("/health", "Host: evil.example", host));
      assertRejected(fixture.get("/health", "Host:"));
      assertRejected(fixture.get("/health", "Host:   "));
    }
  }

  @Test
  void given_developmentAuthority_when_hostCarriesMoreThanAuthority_then_rejectsRequest()
      throws Exception {
    try (var fixture = Fixture.start(directory, true, Fixture::loopbackBase)) {
      String authority = "127.0.0.1:" + fixture.port();
      assertRejected(fixture.get("/health", "Host: user@" + authority));
      assertRejected(fixture.get("/health", "Host: " + authority + "/"));
      assertRejected(fixture.get("/health", "Host: " + authority + "/health"));
      assertRejected(fixture.get("/health", "Host: " + authority + "?health"));
      assertRejected(fixture.get("/health", "Host: " + authority + "#health"));
      assertRejected(fixture.get("/health", "Host: " + authority + " trailing"));
      assertRejected(fixture.get("/health", "Host: [::1"));
      assertRejected(fixture.get("/health", "Host: 127.0.0.1:" + fixture.port() + "x"));
    }
  }

  @Test
  void given_developmentAuthority_when_hostLengthIsAtOrAboveLimit_then_acceptsOnlyWithinLimit()
      throws Exception {
    try (var fixture = Fixture.start(directory, true, Fixture::loopbackBase)) {
      String port = Integer.toString(fixture.port());
      String prefix = "localhost:";
      String atLimit =
          prefix + "0".repeat(HOST_CHARACTERS_MAX - prefix.length() - port.length()) + port;
      assertEquals(HOST_CHARACTERS_MAX, atLimit.length());
      assertEquals(200, fixture.get("/health", "Host: " + atLimit).status());
      assertRejected(
          fixture.get("/health", "Host: " + prefix + "0" + atLimit.substring(prefix.length())));
    }
  }

  @Test
  void given_productionAuthority_when_hostNamesForeignAuthority_then_servesRequest()
      throws Exception {
    try (var fixture =
        Fixture.start(directory, false, port -> URI.create("https://toktrak.test"))) {
      assertEquals(200, fixture.get("/health", "Host: evil.example").status());
      assertEquals(
          200, fixture.get("/health", "Host: toktrak.test", "Host: evil.example").status());
      assertEquals(200, fixture.send("GET /health HTTP/1.0\r\nConnection: close\r\n\r\n").status());
    }
  }

  @Test
  void given_developmentBaseUri_when_notPlainHttpLoopbackWithPort_then_routerRejectsConfiguration()
      throws Exception {
    try (var fixture =
        Fixture.start(directory, false, port -> URI.create("https://toktrak.test"))) {
      for (String baseUri :
          List.of(
              "http://127.0.0.1",
              "http://127.0.0.1:0",
              "http://127.0.0.1:65536",
              "https://127.0.0.1:8080",
              "http://localhost:8080",
              "http://0.0.0.0:8080")) {
        var ex =
            assertThrows(
                IllegalArgumentException.class,
                () -> fixture.router(true, URI.create(baseUri)),
                baseUri);
        assertEquals("development base URI is invalid", ex.getMessage(), baseUri);
      }
      for (String baseUri :
          List.of("http://127.0.0.1:1", "http://127.0.0.1:65535", "HTTP://127.0.0.1:8080")) {
        assertNotNull(fixture.router(true, URI.create(baseUri)), baseUri);
      }
    }
  }

  private static void assertContentType(Response response, String contentType) {
    String headers = response.headers().toLowerCase(Locale.ROOT);
    assertTrue(headers.contains("\r\ncontent-type: " + contentType + "\r\n"), response.headers());
  }

  private static void assertRejected(Response response) {
    assertEquals(421, response.status(), response.body());
    assertTrue(response.body().contains(MISDIRECTED_CODE), response.body());
  }

  private record Response(int status, String headers, String body) {}

  /// A router with the given authority mode serving on an ephemeral loopback port. The base URI is
  /// derived from the bound port so tests can name the accepted authority exactly.
  private static final class Fixture implements AutoCloseable {
    private final HttpServer server;
    private final ExecutorService requests;
    private final EventLog log;
    private final Writer writer;
    private final Projection projection;
    private final HealthState health;

    private Fixture(
        HttpServer server,
        ExecutorService requests,
        EventLog log,
        Writer writer,
        Projection projection,
        HealthState health) {
      this.server = server;
      this.requests = requests;
      this.log = log;
      this.writer = writer;
      this.projection = projection;
      this.health = health;
    }

    static Fixture start(Path directory, boolean devAuth, IntFunction<URI> baseUriForPort)
        throws IOException {
      HttpServer server =
          HttpServer.create(new InetSocketAddress(InetAddress.ofLiteral("127.0.0.1"), 0), 16);
      var requests = Executors.newVirtualThreadPerTaskExecutor();
      var health = new HealthState();
      var projection = Projection.empty();
      var log = EventLog.open(directory.resolve("events.ndjson"));
      var writer = Writer.start(log, projection, health, ClockSource.system());
      var fixture = new Fixture(server, requests, log, writer, projection, health);
      server.createContext(
          "/", fixture.router(devAuth, baseUriForPort.apply(server.getAddress().getPort())));
      server.setExecutor(Runnable::run);
      server.start();
      return fixture;
    }

    static URI loopbackBase(int port) {
      return URI.create("http://127.0.0.1:" + port);
    }

    Router router(boolean devAuth, URI baseUri) {
      var identities = new IdentityService(writer, projection, new byte[32]);
      var auth =
          AuthService.development(
              URI.create("http://127.0.0.1"),
              ClockSource.system(),
              projection,
              identities,
              new byte[32]);
      return new Router(
          health,
          devAuth,
          requests,
          Assets.load(),
          auth,
          new UsageService(writer, ClockSource.system()),
          projection,
          ClockSource.system(),
          baseUri,
          BuildInfo.from(Map.of()));
    }

    int port() {
      return server.getAddress().getPort();
    }

    Response get(String path, String... headerLines) throws IOException {
      var request = new StringBuilder("GET ").append(path).append(" HTTP/1.1\r\n");
      for (String headerLine : headerLines) request.append(headerLine).append("\r\n");
      request.append("Connection: close\r\n\r\n");
      return send(request.toString());
    }

    Response send(String request) throws IOException {
      try (var socket = new Socket()) {
        socket.connect(new InetSocketAddress(InetAddress.ofLiteral("127.0.0.1"), port()), 2_000);
        socket.setSoTimeout(5_000);
        socket.getOutputStream().write(request.getBytes(StandardCharsets.ISO_8859_1));
        socket.getOutputStream().flush();
        byte[] bytes = socket.getInputStream().readNBytes(RESPONSE_BYTES_MAX + 1);
        assertTrue(bytes.length <= RESPONSE_BYTES_MAX, "response exceeds " + RESPONSE_BYTES_MAX);
        String text = new String(bytes, StandardCharsets.UTF_8);
        assertTrue(text.startsWith("HTTP/1.1 "), text);
        int headersEnd = text.indexOf("\r\n\r\n");
        assertTrue(headersEnd > 0, text);
        int status =
            Integer.parseInt(text.substring("HTTP/1.1 ".length(), "HTTP/1.1 ".length() + 3));
        return new Response(
            status, text.substring(0, headersEnd + 2), text.substring(headersEnd + 4));
      }
    }

    @Override
    public void close() {
      server.stop(0);
      requests.close();
      writer.close();
      log.close();
    }
  }
}
