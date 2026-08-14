package toktrak.identity;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import toktrak.projection.Projection;
import toktrak.projection.Projection.TokenPage;
import toktrak.projection.Projection.TrackerToken;
import toktrak.projection.Projection.User;
import toktrak.projection.Projection.UserKey;
import toktrak.store.WriteCommand;
import toktrak.store.Writer;

public final class IdentityService {
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

  public PreparedToken prepareTrackerToken(UserKey owner, String label) {
    Objects.requireNonNull(owner, "owner");
    requireActive(owner);
    String validLabel =
        required(label == null ? null : label.strip(), LABEL_CHARACTERS_MAX, "label");
    byte[] secret = new byte[TOKEN_BYTES];
    random.nextBytes(secret);
    String plaintext = "tt_" + Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
    return new PreparedToken(owner, UUID.randomUUID(), validLabel, digest(plaintext), plaintext);
  }

  public CreatedToken commitTrackerToken(PreparedToken prepared) {
    Objects.requireNonNull(prepared, "prepared");
    write(
        WriteCommand.trackerTokenCreated(
            prepared.owner(), prepared.id(), prepared.label(), prepared.digest()));
    TrackerToken token =
        projection
            .trackerToken(prepared.id())
            .orElseThrow(() -> new IllegalStateException("created tracker token missing"));
    return new CreatedToken(token, prepared.plaintext());
  }

  public List<TrackerToken> trackerTokens(UserKey owner) {
    Objects.requireNonNull(owner, "owner");
    requireActive(owner);
    return projection.trackerTokens(owner);
  }

  public TokenPage trackerTokenPage(UserKey owner, int page, int pageSize) {
    Objects.requireNonNull(owner, "owner");
    requireActive(owner);
    return projection.trackerTokenPage(owner, page, pageSize);
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
    writer.write(command);
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

  public static final class PreparedToken {
    private final UserKey owner;
    private final UUID id;
    private final String label;
    private final byte[] digest;
    private final String plaintext;

    private PreparedToken(UserKey owner, UUID id, String label, byte[] digest, String plaintext) {
      this.owner = Objects.requireNonNull(owner, "owner");
      this.id = Objects.requireNonNull(id, "id");
      this.label = Objects.requireNonNull(label, "label");
      Objects.requireNonNull(digest, "digest");
      this.plaintext = Objects.requireNonNull(plaintext, "plaintext");
      if (digest.length != 32) throw new IllegalArgumentException("digest must contain 32 bytes");
      this.digest = digest.clone();
    }

    public UserKey owner() {
      return owner;
    }

    public UUID id() {
      return id;
    }

    public String label() {
      return label;
    }

    public byte[] digest() {
      return digest.clone();
    }

    public String plaintext() {
      return plaintext;
    }
  }

  public record CreatedToken(TrackerToken token, String plaintext) {
    public CreatedToken {
      Objects.requireNonNull(token, "token");
      Objects.requireNonNull(plaintext, "plaintext");
    }
  }
}
