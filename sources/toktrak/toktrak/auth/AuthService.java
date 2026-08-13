package toktrak.auth;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import toktrak.ClockSource;
import toktrak.identity.IdentityService;
import toktrak.projection.Projection;
import toktrak.projection.Projection.User;
import toktrak.projection.Projection.UserKey;

public final class AuthService {
  public static final String SESSION_COOKIE = "toktrak_session";
  public static final String TRANSACTION_COOKIE = "toktrak_oidc";
  private static final Duration SESSION_LIFETIME = Duration.ofHours(12);
  private static final Duration TRANSACTION_LIFETIME = Duration.ofMinutes(10);

  private final boolean dev;
  private final URI baseUri;
  private final ClockSource clock;
  private final Projection projection;
  private final IdentityService identities;
  private final SignedCookie cookies;
  private final OidcClient oidc;
  private final SecureRandom random = new SecureRandom();

  private AuthService(
      boolean dev,
      URI baseUri,
      ClockSource clock,
      Projection projection,
      IdentityService identities,
      SignedCookie cookies,
      OidcClient oidc) {
    this.dev = dev;
    this.baseUri = Objects.requireNonNull(baseUri, "baseUri");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.projection = Objects.requireNonNull(projection, "projection");
    this.identities = Objects.requireNonNull(identities, "identities");
    this.cookies = Objects.requireNonNull(cookies, "cookies");
    this.oidc = oidc;
    assert dev == (oidc == null);
  }

  public static AuthService development(
      URI baseUri,
      ClockSource clock,
      Projection projection,
      IdentityService identities,
      byte[] sessionSecret) {
    return new AuthService(
        true, baseUri, clock, projection, identities, new SignedCookie(sessionSecret), null);
  }

  public static AuthService production(
      URI baseUri,
      ClockSource clock,
      Projection projection,
      IdentityService identities,
      byte[] sessionSecret,
      OidcClient oidc) {
    return new AuthService(
        false,
        baseUri,
        clock,
        projection,
        identities,
        new SignedCookie(sessionSecret),
        Objects.requireNonNull(oidc, "oidc"));
  }

  public Login beginLogin() {
    if (dev) {
      User user =
          identities.authenticateUser(
              new UserKey("urn:toktrak:development", "viewer"),
              "viewer@development.invalid",
              "Development Viewer",
              "#a8dadc");
      return new Login(tokensUri(), null, issueSession(user.key()));
    }
    OidcClient.Authorization authorization = oidc.begin(callbackUri());
    OidcClient.Transaction transaction = authorization.transaction();
    String transactionCookie =
        cookies.sign(
            Map.of(
                "kind", "transaction",
                "state", transaction.state(),
                "nonce", transaction.nonce(),
                "verifier", transaction.verifier()),
            now().plus(TRANSACTION_LIFETIME));
    return new Login(authorization.redirectUri(), transactionCookie, null);
  }

  public CompletedLogin completeLogin(String rawQuery, String transactionCookie) {
    if (dev) throw new IllegalStateException("OIDC callback is disabled in development");
    Map<String, String> fields = cookies.verify(transactionCookie, now());
    if (!fields.keySet().equals(java.util.Set.of("kind", "state", "nonce", "verifier"))
        || !"transaction".equals(fields.get("kind"))) {
      throw new IllegalArgumentException("OIDC transaction is invalid");
    }
    OidcClient.Profile profile =
        oidc.complete(
            callbackUri(),
            rawQuery,
            new OidcClient.Transaction(
                fields.get("state"), fields.get("nonce"), fields.get("verifier")));
    User user =
        identities.authenticateUser(profile.key(), profile.email(), profile.displayName(), null);
    return new CompletedLogin(tokensUri(), issueSession(user.key()));
  }

  public Session requireSession(String cookieHeader) {
    String value =
        cookie(cookieHeader, SESSION_COOKIE)
            .orElseThrow(() -> new IllegalArgumentException("login required"));
    Map<String, String> fields = cookies.verify(value, now());
    if (!fields.keySet().equals(java.util.Set.of("kind", "issuer", "subject", "csrf"))
        || !"session".equals(fields.get("kind"))) {
      throw new IllegalArgumentException("session is invalid");
    }
    UserKey key = new UserKey(fields.get("issuer"), fields.get("subject"));
    User user =
        projection
            .activeUser(key)
            .orElseThrow(() -> new IllegalArgumentException("login required"));
    return new Session(user, required(fields.get("csrf")));
  }

  public void requireCsrf(Session session, String supplied) {
    Objects.requireNonNull(session, "session");
    byte[] expected = session.csrf.getBytes(StandardCharsets.UTF_8);
    byte[] actual = supplied == null ? new byte[0] : supplied.getBytes(StandardCharsets.UTF_8);
    if (!MessageDigest.isEqual(expected, actual))
      throw new IllegalArgumentException("CSRF token is invalid");
  }

  public IdentityService identities() {
    return identities;
  }

  public String sessionCookie(String value) {
    return cookieHeader(SESSION_COOKIE, value, SESSION_LIFETIME);
  }

  public String transactionCookie(String value) {
    return cookieHeader(TRANSACTION_COOKIE, value, TRANSACTION_LIFETIME);
  }

  public String clearSessionCookie() {
    return clearCookie(SESSION_COOKIE);
  }

  public String clearTransactionCookie() {
    return clearCookie(TRANSACTION_COOKIE);
  }

  private String issueSession(UserKey key) {
    assert key != null;
    return cookies.sign(
        Map.of(
            "kind", "session",
            "issuer", key.issuer(),
            "subject", key.subject(),
            "csrf", randomValue()),
        now().plus(SESSION_LIFETIME));
  }

  private String cookieHeader(String name, String value, Duration lifetime) {
    assert name != null;
    assert value != null;
    assert lifetime != null;
    return name
        + "="
        + value
        + "; Path=/; HttpOnly; SameSite=Lax; Max-Age="
        + lifetime.toSeconds()
        + (dev ? "" : "; Secure");
  }

  private String clearCookie(String name) {
    assert name != null;
    return name + "=; Path=/; HttpOnly; SameSite=Lax; Max-Age=0" + (dev ? "" : "; Secure");
  }

  private Instant now() {
    return Objects.requireNonNull(clock.instant(), "clock instant");
  }

  private URI callbackUri() {
    return baseUri.resolve("/oauth/callback");
  }

  private URI tokensUri() {
    return baseUri.resolve("/tokens");
  }

  private String randomValue() {
    byte[] bytes = new byte[32];
    random.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  public static Optional<String> cookie(String header, String name) {
    Objects.requireNonNull(name, "name");
    if (header == null || header.length() > 16 * 1024) return Optional.empty();
    String match = null;
    String[] values = header.split(";", 65);
    if (values.length > 64) return Optional.empty();
    for (String value : values) {
      int separator = value.indexOf('=');
      if (separator <= 0 || !name.equals(value.substring(0, separator).strip())) continue;
      if (match != null) return Optional.empty();
      match = value.substring(separator + 1).strip();
    }
    return Optional.ofNullable(match);
  }

  private static String required(String value) {
    if (value == null || value.isBlank()) throw new IllegalArgumentException("session is invalid");
    return value;
  }

  public record Login(URI redirectUri, String transaction, String session) {
    public Login {
      Objects.requireNonNull(redirectUri, "redirectUri");
      if ((transaction == null) == (session == null))
        throw new IllegalArgumentException("login cookie state is invalid");
    }
  }

  public record CompletedLogin(URI redirectUri, String session) {
    public CompletedLogin {
      Objects.requireNonNull(redirectUri, "redirectUri");
      Objects.requireNonNull(session, "session");
    }
  }

  public record Session(User user, String csrf) {
    public Session {
      Objects.requireNonNull(user, "user");
      required(csrf);
      assert user.active();
    }

    public UserKey key() {
      return user.key();
    }
  }
}
