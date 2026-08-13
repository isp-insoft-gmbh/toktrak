package toktrak.tests;

import static org.junit.jupiter.api.Assertions.*;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import toktrak.ClockSource;
import toktrak.auth.OidcClient;
import toktrak.json.Json;

final class OidcClientTest {
  private static final Instant NOW = Instant.parse("2026-07-10T12:00:00Z");

  @Test
  void given_fakeProvider_when_completingAuthorizationCodeFlow_then_validatesPkceAndClaims()
      throws Exception {
    RSAKey key = rsaKey();
    var transaction = new AtomicReference<OidcClient.Transaction>();
    var tokenRequest = new AtomicReference<String>();
    var domain = new AtomicReference<>("example.com");
    HttpServer provider =
        HttpServer.create(new InetSocketAddress(InetAddress.ofLiteral("127.0.0.1"), 0), 16);
    String issuer = "http://127.0.0.1:" + provider.getAddress().getPort();
    provider.createContext(
        "/.well-known/openid-configuration",
        exchange ->
            json(
                exchange,
                Json.write(
                    Map.of(
                        "issuer", issuer,
                        "authorization_endpoint", issuer + "/authorize",
                        "token_endpoint", issuer + "/token",
                        "jwks_uri", issuer + "/jwks"))));
    provider.createContext(
        "/jwks", exchange -> json(exchange, new JWKSet(key.toPublicJWK()).toString()));
    provider.createContext(
        "/token",
        exchange -> {
          tokenRequest.set(
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          json(
              exchange,
              Json.write(
                  Map.of("id_token", token(key, issuer, transaction.get().nonce(), domain.get()))));
        });
    provider.start();
    try {
      var client =
          new OidcClient(
              URI.create(issuer + "/.well-known/openid-configuration"),
              "client",
              "secret",
              "example.com",
              ClockSource.fixed(NOW));
      URI callback = URI.create("http://127.0.0.1/callback");
      OidcClient.Authorization authorization = client.begin(callback);
      transaction.set(authorization.transaction());
      Map<String, String> authorizationQuery =
          OidcClient.parseForm(authorization.redirectUri().getRawQuery());
      String expectedChallenge =
          java.util.Base64.getUrlEncoder()
              .withoutPadding()
              .encodeToString(
                  java.security.MessageDigest.getInstance("SHA-256")
                      .digest(
                          authorization
                              .transaction()
                              .verifier()
                              .getBytes(StandardCharsets.US_ASCII)));
      assertEquals("S256", authorizationQuery.get("code_challenge_method"));
      assertEquals(expectedChallenge, authorizationQuery.get("code_challenge"));

      OidcClient.Profile profile =
          client.complete(
              callback,
              "code=valid&state=" + authorization.transaction().state(),
              authorization.transaction());
      assertEquals("subject-1", profile.key().subject());
      assertEquals("user@example.com", profile.email());
      assertTrue(
          tokenRequest.get().contains("code_verifier=" + authorization.transaction().verifier()));

      domain.set("attacker.example");
      OidcClient.Authorization rejected = client.begin(callback);
      assertNotEquals(authorization.transaction().state(), rejected.transaction().state());
      assertNotEquals(authorization.transaction().nonce(), rejected.transaction().nonce());
      assertNotEquals(authorization.transaction().verifier(), rejected.transaction().verifier());
      transaction.set(rejected.transaction());
      assertThrows(
          IllegalArgumentException.class,
          () ->
              client.complete(
                  callback,
                  "code=valid&state=" + rejected.transaction().state(),
                  rejected.transaction()));
    } finally {
      provider.stop(0);
    }
  }

  @Test
  void given_invalidFormValues_when_parsingProviderInput_then_rejectsBoundedMalformedValues() {
    assertEquals(Map.of(), OidcClient.parseForm(""));
    assertThrows(IllegalArgumentException.class, () -> OidcClient.parseForm(null));
    assertThrows(
        IllegalArgumentException.class, () -> OidcClient.parseForm("x".repeat(8 * 1024 + 1)));
    assertThrows(IllegalArgumentException.class, () -> OidcClient.parseForm("missing-equals"));
    assertThrows(IllegalArgumentException.class, () -> OidcClient.parseForm("%20=value"));
    assertThrows(IllegalArgumentException.class, () -> OidcClient.parseForm("a=1&a=2"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            OidcClient.parseForm(
                java.util.Collections.nCopies(65, "a=b").stream()
                    .collect(java.util.stream.Collectors.joining("&"))));
  }

  @Test
  void given_callbackWithWrongState_when_completingFlow_then_rejectsBeforeTokenExchange()
      throws Exception {
    HttpServer provider =
        HttpServer.create(new InetSocketAddress(InetAddress.ofLiteral("127.0.0.1"), 0), 16);
    String issuer = "http://127.0.0.1:" + provider.getAddress().getPort();
    provider.createContext(
        "/.well-known/openid-configuration",
        exchange ->
            json(
                exchange,
                Json.write(
                    Map.of(
                        "issuer", issuer,
                        "authorization_endpoint", issuer + "/authorize",
                        "token_endpoint", issuer + "/token",
                        "jwks_uri", issuer + "/jwks"))));
    provider.start();
    try {
      var client =
          new OidcClient(
              URI.create(issuer + "/.well-known/openid-configuration"),
              "client",
              "secret",
              "example.com",
              ClockSource.fixed(NOW));
      URI callback = URI.create("http://127.0.0.1/callback");
      OidcClient.Authorization authorization = client.begin(callback);
      assertThrows(
          IllegalArgumentException.class,
          () -> client.complete(callback, "code=valid&state=wrong", authorization.transaction()));
    } finally {
      provider.stop(0);
    }
  }

  private static RSAKey rsaKey() throws Exception {
    var generator = new RSAKeyGenerator(2_048);
    generator.keyID("key-1");
    return generator.generate();
  }

  private static String token(RSAKey key, String issuer, String nonce, String domain)
      throws IOException {
    JWTClaimsSet claims =
        new JWTClaimsSet.Builder()
            .issuer(issuer)
            .subject("subject-1")
            .audience("client")
            .issueTime(Date.from(NOW))
            .expirationTime(Date.from(NOW.plusSeconds(300)))
            .claim("nonce", nonce)
            .claim("email", "user@example.com")
            .claim("email_verified", true)
            .claim("hd", domain)
            .claim("name", "Example User")
            .build();
    var jwt =
        new SignedJWT(
            new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), claims);
    try {
      jwt.sign(new RSASSASigner(key));
      return jwt.serialize();
    } catch (com.nimbusds.jose.JOSEException exception) {
      throw new IOException("cannot sign test token", exception);
    }
  }

  private static void json(HttpExchange exchange, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    exchange.sendResponseHeaders(200, bytes.length);
    try (var output = exchange.getResponseBody()) {
      output.write(bytes);
    }
  }
}
