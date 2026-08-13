package toktrak.tests;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import toktrak.ClockSource;
import toktrak.auth.AuthService;
import toktrak.auth.OidcClient;
import toktrak.auth.SignedCookie;
import toktrak.health.HealthState;
import toktrak.identity.IdentityService;
import toktrak.projection.Projection;
import toktrak.store.EventLog;
import toktrak.store.Writer;

final class AuthServiceTest {
  private static final Instant NOW = Instant.parse("2026-07-10T12:00:00Z");
  @TempDir Path directory;

  @Test
  void given_signedCookie_when_tamperedOrExpired_then_rejectsCookie() {
    var cookies = new SignedCookie(new byte[32]);
    String cookie = cookies.sign(Map.of("kind", "session"), NOW.plusSeconds(60));
    assertEquals(Map.of("kind", "session"), cookies.verify(cookie, NOW));
    assertThrows(
        IllegalArgumentException.class,
        () -> cookies.verify(cookie.substring(0, cookie.length() - 1) + "x", NOW));
    assertThrows(IllegalArgumentException.class, () -> cookies.verify(cookie, NOW.plusSeconds(60)));
  }

  @Test
  void given_malformedCookies_when_verifying_then_rejectsEveryMalformedShape() {
    var cookies = new SignedCookie(new byte[32]);
    assertThrows(IllegalArgumentException.class, () -> new SignedCookie(new byte[31]));
    assertThrows(IllegalArgumentException.class, () -> cookies.verify(null, NOW));
    assertThrows(IllegalArgumentException.class, () -> cookies.verify("", NOW));
    assertThrows(IllegalArgumentException.class, () -> cookies.verify("payload", NOW));
    assertThrows(IllegalArgumentException.class, () -> cookies.verify("a.b.c", NOW));
    assertThrows(IllegalArgumentException.class, () -> cookies.verify("@.@", NOW));
    var tooMany = new java.util.HashMap<String, String>();
    for (int index = 0; index < 16; index++) tooMany.put("field" + index, "value");
    assertThrows(
        IllegalArgumentException.class,
        () -> cookies.sign(Map.copyOf(tooMany), NOW.plusSeconds(60)));
    assertTrue(AuthService.cookie(null, AuthService.SESSION_COOKIE).isEmpty());
    assertTrue(AuthService.cookie("x".repeat(16 * 1024 + 1), AuthService.SESSION_COOKIE).isEmpty());
    assertTrue(
        AuthService.cookie(
                java.util.Collections.nCopies(65, "a=b").stream()
                    .collect(java.util.stream.Collectors.joining(";")),
                AuthService.SESSION_COOKIE)
            .isEmpty());
    assertTrue(
        AuthService.cookie(
                AuthService.SESSION_COOKIE + "=first; " + AuthService.SESSION_COOKIE + "=second",
                AuthService.SESSION_COOKIE)
            .isEmpty());
  }

  @Test
  void given_developmentSession_when_userDeactivatesAndLogsInAgain_then_checksCurrentUserState() {
    var projection = Projection.empty();
    try (var log = EventLog.open(directory.resolve("events.ndjson"));
        var writer =
            Writer.start(log, projection, new HealthState(), ClockSource.fixed(NOW), false)) {
      var identities = new IdentityService(writer, projection, new byte[32]);
      var auth =
          AuthService.development(
              URI.create("http://127.0.0.1:8080"),
              ClockSource.fixed(NOW),
              projection,
              identities,
              new byte[32]);
      var production =
          AuthService.production(
              URI.create("https://toktrak.example"),
              ClockSource.fixed(NOW),
              projection,
              identities,
              new byte[32],
              new OidcClient(
                  URI.create("https://accounts.example/.well-known/openid-configuration"),
                  "client",
                  "secret",
                  "example.com",
                  ClockSource.fixed(NOW)));
      assertTrue(production.sessionCookie("value").endsWith("; Secure"));
      assertTrue(production.clearSessionCookie().endsWith("; Secure"));
      AuthService.Login login = auth.beginLogin();
      AuthService.Session session =
          auth.requireSession(AuthService.SESSION_COOKIE + "=" + login.session());
      String extraFieldSession =
          new SignedCookie(new byte[32])
              .sign(
                  Map.of(
                      "kind", "session",
                      "issuer", session.key().issuer(),
                      "subject", session.key().subject(),
                      "csrf", session.csrf(),
                      "extra", "forbidden"),
                  NOW.plusSeconds(60));
      assertThrows(
          IllegalArgumentException.class,
          () -> auth.requireSession(AuthService.SESSION_COOKIE + "=" + extraFieldSession));
      assertEquals("viewer", session.key().subject());
      assertDoesNotThrow(() -> auth.requireCsrf(session, session.csrf()));
      assertThrows(IllegalArgumentException.class, () -> auth.requireCsrf(session, "wrong"));

      identities.deactivate(session.key());
      assertThrows(
          IllegalArgumentException.class,
          () -> auth.requireSession(AuthService.SESSION_COOKIE + "=" + login.session()));
      AuthService.Login reactivated = auth.beginLogin();
      AuthService.Session reactivatedSession =
          auth.requireSession(AuthService.SESSION_COOKIE + "=" + reactivated.session());
      assertEquals(session.key(), reactivatedSession.key());
      assertNotEquals(session.csrf(), reactivatedSession.csrf());
    }
  }
}
