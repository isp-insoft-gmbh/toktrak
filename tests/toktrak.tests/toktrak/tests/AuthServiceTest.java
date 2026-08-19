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
  void given_malformedCookies_when_verifying_then_rejectsEveryMalformedShape() throws Exception {
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
    assertThrows(
        IllegalArgumentException.class,
        () -> cookies.sign(Map.of("kind", " "), NOW.plusSeconds(60)));
    assertThrows(
        IllegalArgumentException.class,
        () -> cookies.sign(Map.of(" ", "value"), NOW.plusSeconds(60)));
    assertThrows(
        IllegalArgumentException.class,
        () -> cookies.sign(Map.of("kind", "x".repeat(2_049)), NOW.plusSeconds(60)));
    // Correctly signed payloads with blank names or values must still be rejected.
    assertThrows(
        IllegalArgumentException.class,
        () -> cookies.verify(forge("a=%20&exp=" + NOW.plusSeconds(60).getEpochSecond()), NOW));
    assertThrows(
        IllegalArgumentException.class,
        () -> cookies.verify(forge("%20=a&exp=" + NOW.plusSeconds(60).getEpochSecond()), NOW));
    assertTrue(AuthService.cookie(null, AuthService.SESSION_COOKIE).isEmpty());
    assertTrue(AuthService.cookie("x".repeat(16 * 1024 + 1), AuthService.SESSION_COOKIE).isEmpty());
    String atHeaderLimit =
        AuthService.SESSION_COOKIE
            + "="
            + "v".repeat(16 * 1024 - AuthService.SESSION_COOKIE.length() - 1);
    assertEquals(16 * 1024, atHeaderLimit.length());
    assertTrue(AuthService.cookie(atHeaderLimit, AuthService.SESSION_COOKIE).isPresent());
    assertTrue(
        AuthService.cookie(
                AuthService.SESSION_COOKIE + "=value" + ";a=b".repeat(63),
                AuthService.SESSION_COOKIE)
            .isPresent());
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

  private static String forge(String payload) throws Exception {
    String encoded =
        java.util.Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    var mac = javax.crypto.Mac.getInstance("HmacSHA256");
    mac.init(new javax.crypto.spec.SecretKeySpec(new byte[32], "HmacSHA256"));
    return encoded
        + "."
        + java.util.Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(
                mac.doFinal(encoded.getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
  }

  @Test
  void given_invalidTransactionCookies_when_completingProductionLogin_then_rejectsTransaction() {
    var secret = new byte[32];
    var cookies = new SignedCookie(secret);
    var projection = Projection.empty();
    try (var log = EventLog.open(directory.resolve("events.ndjson"));
        var writer = Writer.start(log, projection, new HealthState(), ClockSource.fixed(NOW))) {
      var identities = new IdentityService(writer, projection, secret);
      var production =
          AuthService.production(
              URI.create("https://toktrak.example"),
              ClockSource.fixed(NOW),
              projection,
              identities,
              secret,
              new OidcClient(
                  URI.create("https://accounts.example/.well-known/openid-configuration"),
                  "client",
                  "secret",
                  "example.com",
                  ClockSource.fixed(NOW)));
      Map<String, String> transaction =
          Map.of(
              "kind", "transaction",
              "state", "state-1",
              "nonce", "nonce-1",
              "verifier", "verifier-1");
      String expired = cookies.sign(transaction, NOW);
      assertThrows(
          IllegalArgumentException.class,
          () -> production.completeLogin("code=valid&state=state-1", expired));
      String wrongKind =
          cookies.sign(
              Map.of(
                  "kind", "session",
                  "state", "state-1",
                  "nonce", "nonce-1",
                  "verifier", "verifier-1"),
              NOW.plusSeconds(60));
      assertThrows(
          IllegalArgumentException.class,
          () -> production.completeLogin("code=valid&state=state-1", wrongKind));
      var extraField = new java.util.HashMap<>(transaction);
      extraField.put("extra", "forbidden");
      String extra = cookies.sign(Map.copyOf(extraField), NOW.plusSeconds(60));
      assertThrows(
          IllegalArgumentException.class,
          () -> production.completeLogin("code=valid&state=state-1", extra));
    }
  }

  @Test
  void given_developmentSession_when_userDeactivatesAndLogsInAgain_then_checksCurrentUserState() {
    var projection = Projection.empty();
    try (var log = EventLog.open(directory.resolve("events.ndjson"));
        var writer = Writer.start(log, projection, new HealthState(), ClockSource.fixed(NOW))) {
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

      assertThrows(
          IllegalStateException.class, () -> auth.completeLogin("code=valid&state=any", "cookie"));

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
