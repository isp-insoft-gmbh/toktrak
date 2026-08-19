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
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import toktrak.App;
import toktrak.auth.AuthService;
import toktrak.auth.OidcClient;

final class OidcLoginHttpTest {
  @TempDir Path directory;

  @Test
  void given_productionApp_when_completingOidcLogin_then_issuesSessionAndClearsTransaction()
      throws Exception {
    RSAKey key = rsaKey();
    var nonce = new AtomicReference<String>();
    HttpServer provider =
        HttpServer.create(new InetSocketAddress(InetAddress.ofLiteral("127.0.0.1"), 0), 16);
    String issuer = "http://127.0.0.1:" + provider.getAddress().getPort();
    provider.createContext(
        "/.well-known/openid-configuration",
        exchange ->
            json(
                exchange,
                toktrak.json.Json.write(
                    Map.of(
                        "issuer", issuer,
                        "authorization_endpoint", issuer + "/authorize",
                        "token_endpoint", issuer + "/token",
                        "jwks_uri", issuer + "/jwks"))));
    provider.createContext(
        "/jwks", exchange -> json(exchange, new JWKSet(key.toPublicJWK()).toString()));
    provider.createContext(
        "/token",
        exchange ->
            json(
                exchange,
                toktrak.json.Json.write(Map.of("id_token", token(key, issuer, nonce.get())))));
    provider.start();
    try (var app = App.start(new String[] {}, productionEnvironment(directory, issuer))) {
      URI base = URI.create("http://127.0.0.1:" + app.port());
      var client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

      HttpResponse<String> login = send(client, base.resolve("/login"), null);
      assertEquals(302, login.statusCode());
      URI authorization = URI.create(login.headers().firstValue("Location").orElseThrow());
      assertEquals(
          issuer + "/authorize", authorization.resolve(authorization.getPath()).toString());
      Map<String, String> query = OidcClient.parseForm(authorization.getRawQuery());
      assertEquals("client", query.get("client_id"));
      assertEquals("https://toktrak.test/oauth/callback", query.get("redirect_uri"));
      assertEquals("code", query.get("response_type"));
      nonce.set(query.get("nonce"));
      String transactionCookie = login.headers().firstValue("Set-Cookie").orElseThrow();
      assertTrue(transactionCookie.startsWith(AuthService.TRANSACTION_COOKIE + "="));
      assertTrue(transactionCookie.contains("; Path=/"));
      assertTrue(transactionCookie.contains("; HttpOnly"));
      assertTrue(transactionCookie.contains("; SameSite=Lax"));
      assertTrue(transactionCookie.contains("; Max-Age=600"));
      assertTrue(transactionCookie.contains("; Secure"));
      String transaction = transactionCookie.split(";", 2)[0];

      URI callback = base.resolve("/oauth/callback?code=valid&state=" + query.get("state"));
      assertEquals(401, send(client, callback, null).statusCode());
      assertEquals(
          401,
          send(client, base.resolve("/oauth/callback?code=valid&state=wrong"), transaction)
              .statusCode());

      HttpResponse<String> completed = send(client, callback, transaction);
      assertEquals(302, completed.statusCode());
      assertEquals(
          "https://toktrak.test/tokens", completed.headers().firstValue("Location").orElseThrow());
      List<String> cookies = completed.headers().allValues("Set-Cookie");
      assertTrue(
          cookies.stream()
              .anyMatch(
                  value ->
                      value.startsWith(AuthService.TRANSACTION_COOKIE + "=;")
                          && value.contains("Max-Age=0")),
          cookies.toString());
      String sessionCookie =
          cookies.stream()
              .filter(value -> value.startsWith(AuthService.SESSION_COOKIE + "="))
              .findFirst()
              .orElseThrow();
      assertTrue(sessionCookie.contains("; Max-Age=43200"));
      assertTrue(sessionCookie.contains("; Secure"));
      String session = sessionCookie.split(";", 2)[0];

      HttpResponse<String> tokens = send(client, base.resolve("/tokens"), session);
      assertEquals(200, tokens.statusCode());
      HttpResponse<String> scope = send(client, base.resolve("/scope"), session);
      assertEquals(200, scope.statusCode());
      assertTrue(scope.body().contains("Signed in as <strong>Example User</strong>"), scope.body());

      // A signed session cookie must not be replayable as an OIDC transaction cookie.
      HttpResponse<String> confused =
          send(client, callback, AuthService.TRANSACTION_COOKIE + "=" + session.split("=", 2)[1]);
      assertEquals(401, confused.statusCode());
      assertTrue(confused.body().contains("Authentication failed."), confused.body());
      assertFalse(confused.body().contains("OIDC transaction"), confused.body());
    } finally {
      provider.stop(0);
    }
  }

  private static HttpResponse<String> send(HttpClient client, URI uri, String cookie)
      throws Exception {
    var request = HttpRequest.newBuilder(uri).timeout(java.time.Duration.ofSeconds(5)).GET();
    if (cookie != null) request.header("Cookie", cookie);
    return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  private static Map<String, String> productionEnvironment(Path directory, String issuer) {
    String secret = java.util.Base64.getEncoder().encodeToString(new byte[32]);
    return Map.of(
        "TOKTRAK_DATA_DIR", directory.toString(),
        "TOKTRAK_BASE_URL", "https://toktrak.test",
        "TOKTRAK_PORT", "0",
        "TOKTRAK_OIDC_DISCOVERY_URL", issuer + "/.well-known/openid-configuration",
        "TOKTRAK_OIDC_CLIENT_ID", "client",
        "TOKTRAK_OIDC_CLIENT_SECRET", "secret",
        "TOKTRAK_ALLOWED_DOMAIN", "example.com",
        "TOKTRAK_SESSION_SECRET", secret,
        "TOKTRAK_TOKEN_PEPPER", secret);
  }

  private static RSAKey rsaKey() throws Exception {
    var generator = new RSAKeyGenerator(2_048);
    generator.keyID("key-1");
    return generator.generate();
  }

  private static String token(RSAKey key, String issuer, String nonce) throws IOException {
    Instant now = Instant.now();
    JWTClaimsSet claims =
        new JWTClaimsSet.Builder()
            .issuer(issuer)
            .subject("subject-1")
            .audience("client")
            .issueTime(Date.from(now))
            .expirationTime(Date.from(now.plusSeconds(300)))
            .claim("nonce", nonce)
            .claim("email", "user@example.com")
            .claim("email_verified", true)
            .claim("hd", "example.com")
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
