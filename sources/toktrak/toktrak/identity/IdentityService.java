package toktrak.identity;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import toktrak.projection.Projection;
import toktrak.projection.Projection.TrackerToken;
import toktrak.projection.Projection.User;
import toktrak.projection.Projection.UserKey;
import toktrak.store.WriteCommand;
import toktrak.store.Writer;

public final class IdentityService {
  private static final Duration WRITE_TIMEOUT = Duration.ofSeconds(10);
  private static final int LABEL_CHARACTERS_MAX = 128;
  private static final int TOKEN_BYTES = 32;
  private static final String[] COLORS = {
    "#f4a6a6", "#f7c59f", "#f5e6a8", "#b9e4c9",
    "#a8dadc", "#b8c0ff", "#d0b4f4", "#f4b8d8"
  };

  private final Writer writer;
  private final Projection projection;
  private final byte[] pepper;
  private final SecureRandom random;

  public IdentityService(Writer writer, Projection projection, byte[] pepper) {
    this(writer, projection, pepper, new SecureRandom());
  }

  IdentityService(Writer writer, Projection projection, byte[] pepper, SecureRandom random) {
    this.writer = Objects.requireNonNull(writer, "writer");
    this.projection = Objects.requireNonNull(projection, "projection");
    Objects.requireNonNull(pepper, "pepper");
    if (pepper.length < 32)
      throw new IllegalArgumentException("token pepper must contain 32 bytes");
    this.pepper = pepper.clone();
    this.random = Objects.requireNonNull(random, "random");
  }

  public User authenticateUser(
      UserKey key, String email, String displayName, String colorOverride) {
    Objects.requireNonNull(key, "key");
    String validEmail = required(email, 320, "email");
    String validName = required(displayName, 256, "displayName");
    String color = colorOverride == null ? color(key) : requiredColor(colorOverride);
    write(WriteCommand.userAuthenticated(key, validEmail, validName, color));
    return projection
        .activeUser(key)
        .orElseThrow(() -> new IllegalStateException("authenticated user missing from projection"));
  }

  public void deactivate(UserKey key) {
    Objects.requireNonNull(key, "key");
    write(WriteCommand.userDeactivated(key));
    assert projection.activeUser(key).isEmpty();
  }

  public CreatedToken createTrackerToken(UserKey owner, String label) {
    Objects.requireNonNull(owner, "owner");
    String validLabel =
        required(label == null ? null : label.strip(), LABEL_CHARACTERS_MAX, "label");
    byte[] secret = new byte[TOKEN_BYTES];
    random.nextBytes(secret);
    String plaintext = "tt_" + Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
    byte[] digest = digest(plaintext);
    UUID id = UUID.randomUUID();
    write(WriteCommand.trackerTokenCreated(owner, id, validLabel, digest));
    TrackerToken token =
        projection
            .trackerToken(id)
            .orElseThrow(() -> new IllegalStateException("created tracker token missing"));
    return new CreatedToken(token, plaintext);
  }

  public List<TrackerToken> trackerTokens(UserKey owner) {
    Objects.requireNonNull(owner, "owner");
    requireActive(owner);
    return projection.trackerTokens(owner);
  }

  public void revokeTrackerToken(UserKey owner, UUID tokenId) {
    Objects.requireNonNull(owner, "owner");
    Objects.requireNonNull(tokenId, "tokenId");
    write(WriteCommand.trackerTokenRevoked(owner, tokenId));
  }

  public UserKey authenticateTrackerToken(String plaintext) {
    String value = plaintext == null ? "" : plaintext;
    if (value.length() > 256) throw new IllegalArgumentException("tracker token is invalid");
    byte[] digest = digest(value);
    TrackerToken token =
        projection
            .activeTrackerToken(digest)
            .orElseThrow(() -> new IllegalArgumentException("tracker token is invalid"));
    write(WriteCommand.trackerTokenUsed(token.id(), digest));
    return token.owner();
  }

  private void requireActive(UserKey key) {
    if (projection.activeUser(key).isEmpty()) throw new IllegalStateException("user is inactive");
  }

  private void write(WriteCommand command) {
    Writer.Submission submission = writer.trySubmit(command);
    if (!submission.accepted()) throw new IllegalStateException("writer is unavailable");
    try {
      submission.future().get(WRITE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("identity write interrupted", exception);
    } catch (ExecutionException | java.util.concurrent.TimeoutException exception) {
      Throwable cause = exception instanceof ExecutionException ? exception.getCause() : exception;
      if (cause instanceof RuntimeException runtimeException) throw runtimeException;
      throw new IllegalStateException("identity write failed", cause);
    }
  }

  private byte[] digest(String plaintext) {
    assert plaintext != null;
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(pepper, "HmacSHA256"));
      byte[] result = mac.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
      assert result.length == 32;
      return result;
    } catch (GeneralSecurityException exception) {
      throw new IllegalStateException("HMAC-SHA-256 unavailable", exception);
    }
  }

  private static String color(UserKey key) {
    assert key != null;
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256")
              .digest(key.toString().getBytes(StandardCharsets.UTF_8));
      return COLORS[Byte.toUnsignedInt(digest[0]) % COLORS.length];
    } catch (GeneralSecurityException exception) {
      throw new IllegalStateException("SHA-256 unavailable", exception);
    }
  }

  private static String required(String value, int charactersMax, String name) {
    if (value == null || value.isBlank() || value.length() > charactersMax) {
      throw new IllegalArgumentException(name + " is invalid");
    }
    return value;
  }

  private static String requiredColor(String value) {
    if (!value.matches("#[0-9a-f]{6}")) throw new IllegalArgumentException("color is invalid");
    return value;
  }

  public record CreatedToken(TrackerToken token, String plaintext) {
    public CreatedToken {
      Objects.requireNonNull(token, "token");
      Objects.requireNonNull(plaintext, "plaintext");
    }
  }
}
