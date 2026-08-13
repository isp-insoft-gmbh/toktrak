package toktrak.auth;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class SignedCookie {
  private static final int COOKIE_CHARACTERS_MAX = 8 * 1024;
  private static final int FIELDS_MAX = 16;
  private final byte[] secret;

  public SignedCookie(byte[] secret) {
    Objects.requireNonNull(secret, "secret");
    if (secret.length < 32)
      throw new IllegalArgumentException("cookie secret must contain 32 bytes");
    this.secret = secret.clone();
  }

  public String sign(Map<String, String> fields, Instant expiresAt) {
    Objects.requireNonNull(fields, "fields");
    Objects.requireNonNull(expiresAt, "expiresAt");
    if (fields.size() >= FIELDS_MAX)
      throw new IllegalArgumentException("cookie fields exceed limit");
    var values = new TreeMap<>(fields);
    values.put("exp", Long.toString(expiresAt.getEpochSecond()));
    var payload = new StringBuilder();
    for (Map.Entry<String, String> entry : values.entrySet()) {
      requireField(entry.getKey(), "cookie field name");
      requireField(entry.getValue(), "cookie field value");
      if (!payload.isEmpty()) payload.append('&');
      payload
          .append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8))
          .append('=')
          .append(URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
    }
    String encoded =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(payload.toString().getBytes(StandardCharsets.UTF_8));
    String result =
        encoded + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(hmac(encoded));
    if (result.length() > COOKIE_CHARACTERS_MAX)
      throw new IllegalArgumentException("signed cookie exceeds limit");
    return result;
  }

  public Map<String, String> verify(String value, Instant now) {
    Objects.requireNonNull(now, "now");
    if (value == null || value.isBlank() || value.length() > COOKIE_CHARACTERS_MAX) {
      throw new IllegalArgumentException("cookie is invalid");
    }
    int separator = value.indexOf('.');
    if (separator <= 0 || separator != value.lastIndexOf('.')) {
      throw new IllegalArgumentException("cookie is invalid");
    }
    String payload = value.substring(0, separator);
    byte[] supplied;
    byte[] decoded;
    try {
      supplied = Base64.getUrlDecoder().decode(value.substring(separator + 1));
      decoded = Base64.getUrlDecoder().decode(payload);
    } catch (IllegalArgumentException exception) {
      throw new IllegalArgumentException("cookie is invalid");
    }
    if (!MessageDigest.isEqual(hmac(payload), supplied)) {
      throw new IllegalArgumentException("cookie is invalid");
    }
    String text = new String(decoded, StandardCharsets.UTF_8);
    var fields = new HashMap<String, String>();
    if (!text.isEmpty()) {
      String[] pairs = text.split("&", FIELDS_MAX + 1);
      if (pairs.length > FIELDS_MAX) throw new IllegalArgumentException("cookie is invalid");
      for (String pair : pairs) {
        int equals = pair.indexOf('=');
        if (equals <= 0) throw new IllegalArgumentException("cookie is invalid");
        String name = URLDecoder.decode(pair.substring(0, equals), StandardCharsets.UTF_8);
        String field = URLDecoder.decode(pair.substring(equals + 1), StandardCharsets.UTF_8);
        requireField(name, "cookie field name");
        requireField(field, "cookie field value");
        if (fields.put(name, field) != null)
          throw new IllegalArgumentException("cookie is invalid");
      }
    }
    String expiration = fields.remove("exp");
    try {
      if (expiration == null || !now.isBefore(Instant.ofEpochSecond(Long.parseLong(expiration)))) {
        throw new IllegalArgumentException("cookie is expired");
      }
    } catch (NumberFormatException | java.time.DateTimeException exception) {
      throw new IllegalArgumentException("cookie is invalid");
    }
    return Map.copyOf(fields);
  }

  private byte[] hmac(String payload) {
    assert payload != null;
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret, "HmacSHA256"));
      return mac.doFinal(payload.getBytes(StandardCharsets.US_ASCII));
    } catch (GeneralSecurityException exception) {
      throw new IllegalStateException("HMAC-SHA-256 unavailable", exception);
    }
  }

  private static void requireField(String value, String name) {
    if (value == null || value.isBlank() || value.length() > 2_048) {
      throw new IllegalArgumentException(name + " is invalid");
    }
  }
}
