package toktrak.auth;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Semaphore;
import toktrak.ClockSource;
import toktrak.http.HttpSupport;
import toktrak.json.Json;
import toktrak.projection.Projection.UserKey;

public final class OidcClient {
  private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(10);
  private static final Duration CLOCK_SKEW = Duration.ofMinutes(5);
  private static final int PROVIDER_BODY_BYTES_MAX = 1024 * 1024;
  private static final int QUERY_CHARACTERS_MAX = 8 * 1024;
  private static final int FIELD_CHARACTERS_MAX = 8 * 1024;
  private static final int PROVIDER_REQUESTS_MAX = 4;
  private static final String DISCOVERY_SUFFIX = "/.well-known/openid-configuration";

  private final URI discoveryUri;
  private final String clientId;
  private final String clientSecret;
  private final String allowedDomain;
  private final ClockSource clock;
  private final HttpClient http;
  private final SecureRandom random;
  private final Semaphore providerRequests = new Semaphore(PROVIDER_REQUESTS_MAX);

  public OidcClient(
      URI discoveryUri,
      String clientId,
      String clientSecret,
      String allowedDomain,
      ClockSource clock) {
    this(
        discoveryUri,
        clientId,
        clientSecret,
        allowedDomain,
        clock,
        HttpClient.newBuilder()
            .connectTimeout(HTTP_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build(),
        new SecureRandom());
  }

  OidcClient(
      URI discoveryUri,
      String clientId,
      String clientSecret,
      String allowedDomain,
      ClockSource clock,
      HttpClient http,
      SecureRandom random) {
    this.discoveryUri = requireEndpoint(discoveryUri, "OIDC discovery URL");
    this.clientId = required(clientId, "OIDC client ID");
    this.clientSecret = required(clientSecret, "OIDC client secret");
    this.allowedDomain =
        required(allowedDomain, "allowed domain").toLowerCase(java.util.Locale.ROOT);
    this.clock = Objects.requireNonNull(clock, "clock");
    this.http = Objects.requireNonNull(http, "http");
    this.random = Objects.requireNonNull(random, "random");
  }

  public Authorization begin(URI callbackUri) {
    requireCallback(callbackUri);
    Metadata metadata = metadata();
    String state = randomValue();
    String nonce = randomValue();
    String verifier = randomValue();
    String challenge = sha256(verifier);
    String query =
        form(
            Map.of(
                "client_id",
                clientId,
                "redirect_uri",
                callbackUri.toString(),
                "response_type",
                "code",
                "scope",
                "openid email profile",
                "state",
                state,
                "nonce",
                nonce,
                "code_challenge",
                challenge,
                "code_challenge_method",
                "S256"));
    return new Authorization(
        URI.create(metadata.authorizationEndpoint + "?" + query),
        new Transaction(state, nonce, verifier));
  }

  public Profile complete(URI callbackUri, String rawQuery, Transaction transaction) {
    requireCallback(callbackUri);
    Objects.requireNonNull(transaction, "transaction");
    Map<String, String> query = parseForm(rawQuery);
    if (!transaction.state.equals(query.get("state")) || query.containsKey("error")) {
      throw invalid();
    }
    String code = required(query.get("code"), "authorization code");
    Metadata metadata = metadata();
    String tokenJson =
        postForm(
            metadata.tokenEndpoint,
            Map.of(
                "grant_type",
                "authorization_code",
                "code",
                code,
                "redirect_uri",
                callbackUri.toString(),
                "client_id",
                clientId,
                "client_secret",
                clientSecret,
                "code_verifier",
                transaction.verifier));
    Map<?, ?> token = object(Json.read(tokenJson, Map.class));
    Object idToken = token.get("id_token");
    if (!(idToken instanceof String compact) || compact.length() > FIELD_CHARACTERS_MAX) {
      throw invalid();
    }
    return validate(compact, transaction.nonce, metadata);
  }

  private Profile validate(String compact, String nonce, Metadata metadata) {
    try {
      SignedJWT jwt = SignedJWT.parse(compact);
      if (!JWSAlgorithm.RS256.equals(jwt.getHeader().getAlgorithm())) throw invalid();
      String keyId = jwt.getHeader().getKeyID();
      if (keyId == null || keyId.isBlank()) throw invalid();
      JWK key = JWKSet.parse(get(metadata.jwksUri)).getKeyByKeyId(keyId);
      if (!(key instanceof RSAKey rsaKey) || !jwt.verify(new RSASSAVerifier(rsaKey)))
        throw invalid();
      JWTClaimsSet claims = jwt.getJWTClaimsSet();
      Instant now = Objects.requireNonNull(clock.instant(), "clock instant");
      if (!metadata.issuer.equals(claims.getIssuer())) throw invalid();
      String subject = required(claims.getSubject(), "subject");
      List<String> audience = claims.getAudience();
      if (audience == null || !audience.contains(clientId)) throw invalid();
      if (audience.size() > 1 && !clientId.equals(claims.getStringClaim("azp"))) throw invalid();
      Date expires = claims.getExpirationTime();
      Date issued = claims.getIssueTime();
      Date notBefore = claims.getNotBeforeTime();
      if (expires == null || !now.minus(CLOCK_SKEW).isBefore(expires.toInstant())) throw invalid();
      if (issued == null || issued.toInstant().isAfter(now.plus(CLOCK_SKEW))) throw invalid();
      if (notBefore != null && notBefore.toInstant().isAfter(now.plus(CLOCK_SKEW))) throw invalid();
      if (!nonce.equals(claims.getStringClaim("nonce"))) throw invalid();
      if (!Boolean.TRUE.equals(claims.getBooleanClaim("email_verified"))) throw invalid();
      String email = required(claims.getStringClaim("email"), "email");
      String hostedDomain = required(claims.getStringClaim("hd"), "hosted domain");
      int separator = email.lastIndexOf('@');
      if (separator <= 0
          || !allowedDomain.equalsIgnoreCase(email.substring(separator + 1))
          || !allowedDomain.equalsIgnoreCase(hostedDomain)) {
        throw invalid();
      }
      String displayName = required(claims.getStringClaim("name"), "display name");
      return new Profile(new UserKey(metadata.issuer, subject), email, displayName);
    } catch (com.nimbusds.jose.JOSEException | java.text.ParseException exception) {
      throw invalid(exception);
    }
  }

  private Metadata metadata() {
    Map<?, ?> data = object(Json.read(get(discoveryUri), Map.class));
    String issuer = requireDiscoveredIssuer(data.get("issuer"));
    URI authorizationEndpoint =
        requireEndpoint(uri(data, "authorization_endpoint"), "authorization endpoint");
    URI tokenEndpoint = requireEndpoint(uri(data, "token_endpoint"), "token endpoint");
    URI jwksUri = requireEndpoint(uri(data, "jwks_uri"), "JWKS endpoint");
    return new Metadata(issuer, authorizationEndpoint, tokenEndpoint, jwksUri);
  }

  private String requireDiscoveredIssuer(Object value) {
    try {
      String issuer = requiredString(value);
      URI issuerUri = requireEndpoint(URI.create(issuer), "issuer");
      if (issuerUri.getUserInfo() != null || issuerUri.getRawQuery() != null) {
        throw new IllegalArgumentException("issuer is invalid");
      }
      String prefix = issuer.endsWith("/") ? issuer.substring(0, issuer.length() - 1) : issuer;
      if (!discoveryUri.toString().equals(prefix + DISCOVERY_SUFFIX)) {
        throw new IllegalArgumentException("issuer does not match discovery URL");
      }
      return issuer;
    } catch (IllegalArgumentException exception) {
      throw new IllegalStateException("OIDC discovery is invalid", exception);
    }
  }

  private String get(URI uri) {
    HttpRequest request =
        HttpRequest.newBuilder(uri)
            .timeout(HTTP_TIMEOUT)
            .header("Accept", "application/json")
            .GET()
            .build();
    return send(request);
  }

  private String postForm(URI uri, Map<String, String> fields) {
    String body = form(fields);
    HttpRequest request =
        HttpRequest.newBuilder(uri)
            .timeout(HTTP_TIMEOUT)
            .header("Accept", "application/json")
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
    return send(request);
  }

  private String send(HttpRequest request) {
    assert request != null;
    if (!providerRequests.tryAcquire()) {
      throw new IllegalStateException("OIDC provider request limit reached");
    }
    try {
      HttpResponse<java.io.InputStream> response =
          http.send(request, HttpResponse.BodyHandlers.ofInputStream());
      try (var input = response.body()) {
        if (response.statusCode() != 200)
          throw new IllegalStateException("OIDC provider rejected request");
        return new String(
            HttpSupport.readLimited(input, PROVIDER_BODY_BYTES_MAX), StandardCharsets.UTF_8);
      }
    } catch (IOException exception) {
      throw new IllegalStateException("OIDC provider request failed", exception);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("OIDC provider request interrupted", exception);
    } finally {
      providerRequests.release();
      assert providerRequests.availablePermits() <= PROVIDER_REQUESTS_MAX;
    }
  }

  private String randomValue() {
    byte[] bytes = new byte[32];
    random.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  private static String sha256(String value) {
    assert value != null;
    try {
      return Base64.getUrlEncoder()
          .withoutPadding()
          .encodeToString(
              MessageDigest.getInstance("SHA-256")
                  .digest(value.getBytes(StandardCharsets.US_ASCII)));
    } catch (java.security.NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 unavailable", exception);
    }
  }

  public static Map<String, String> parseForm(String value) {
    if (value == null || value.length() > QUERY_CHARACTERS_MAX) throw invalid();
    var fields = new HashMap<String, String>();
    if (value.isEmpty()) return Map.of();
    String[] pairs = value.split("&", 65);
    if (pairs.length > 64) throw invalid();
    for (String pair : pairs) {
      int separator = pair.indexOf('=');
      if (separator <= 0) throw invalid();
      String name =
          java.net.URLDecoder.decode(pair.substring(0, separator), StandardCharsets.UTF_8);
      String field =
          java.net.URLDecoder.decode(pair.substring(separator + 1), StandardCharsets.UTF_8);
      if (name.isBlank() || name.length() > 128 || field.length() > FIELD_CHARACTERS_MAX)
        throw invalid();
      if (fields.put(name, field) != null) throw invalid();
    }
    return Map.copyOf(fields);
  }

  private static String form(Map<String, String> fields) {
    return fields.entrySet().stream()
        .sorted(Map.Entry.comparingByKey())
        .map(
            entry ->
                URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8)
                    + "="
                    + URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8))
        .collect(java.util.stream.Collectors.joining("&"));
  }

  private static URI uri(Map<?, ?> data, String name) {
    try {
      return URI.create(requiredString(data.get(name)));
    } catch (IllegalArgumentException exception) {
      throw new IllegalStateException("OIDC discovery is invalid", exception);
    }
  }

  private static URI requireEndpoint(URI uri, String name) {
    Objects.requireNonNull(uri, name);
    String host = uri.getHost();
    boolean local =
        "http".equalsIgnoreCase(uri.getScheme())
            && ("127.0.0.1".equals(host) || "localhost".equalsIgnoreCase(host));
    if (!("https".equalsIgnoreCase(uri.getScheme()) || local)
        || host == null
        || uri.getFragment() != null) {
      throw new IllegalArgumentException(name + " is invalid");
    }
    return uri;
  }

  private static void requireCallback(URI uri) {
    Objects.requireNonNull(uri, "callbackUri");
    if (!uri.isAbsolute() || uri.getFragment() != null || uri.getRawQuery() != null) {
      throw new IllegalArgumentException("callback URI is invalid");
    }
  }

  private static String requiredString(Object value) {
    return value instanceof String string
        ? required(string, "OIDC metadata field")
        : required(null, "OIDC metadata field");
  }

  private static String required(String value, String name) {
    if (value == null || value.isBlank() || value.length() > FIELD_CHARACTERS_MAX) {
      throw new IllegalArgumentException(name + " is invalid");
    }
    return value;
  }

  private static Map<?, ?> object(Object value) {
    if (!(value instanceof Map<?, ?> map)) throw invalid();
    return map;
  }

  private static IllegalArgumentException invalid() {
    return new IllegalArgumentException("OIDC response is invalid");
  }

  private static IllegalArgumentException invalid(Exception cause) {
    return new IllegalArgumentException("OIDC response is invalid", cause);
  }

  private record Metadata(
      String issuer, URI authorizationEndpoint, URI tokenEndpoint, URI jwksUri) {
    private Metadata {
      required(issuer, "issuer");
      requireEndpoint(authorizationEndpoint, "authorization endpoint");
      requireEndpoint(tokenEndpoint, "token endpoint");
      requireEndpoint(jwksUri, "JWKS endpoint");
    }
  }

  public record Authorization(URI redirectUri, Transaction transaction) {
    public Authorization {
      Objects.requireNonNull(redirectUri, "redirectUri");
      Objects.requireNonNull(transaction, "transaction");
    }
  }

  public record Transaction(String state, String nonce, String verifier) {
    public Transaction {
      required(state, "state");
      required(nonce, "nonce");
      required(verifier, "verifier");
    }
  }

  public record Profile(UserKey key, String email, String displayName) {
    public Profile {
      Objects.requireNonNull(key, "key");
      required(email, "email");
      required(displayName, "displayName");
    }
  }
}
