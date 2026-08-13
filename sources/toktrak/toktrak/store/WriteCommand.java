package toktrak.store;

import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import toktrak.projection.Projection;
import toktrak.projection.Projection.UserKey;

public final class WriteCommand {
  private enum Kind {
    DEV_TEST,
    SNAPSHOT,
    USER_AUTHENTICATED,
    USER_DEACTIVATED,
    TOKEN_CREATED,
    TOKEN_REVOKED,
    TOKEN_USED
  }

  private final Kind kind;
  private final String actor;
  private final Map<String, Object> data;
  private final byte[] digest;

  private WriteCommand(Kind kind, String actor, Map<String, Object> data, byte[] digest) {
    assert kind != null;
    if (actor == null || actor.isBlank()) throw new IllegalArgumentException("actor is required");
    this.kind = kind;
    this.actor = actor;
    this.data = Map.copyOf(data);
    this.digest = digest == null ? null : digest.clone();
  }

  public static WriteCommand devTest(String actor) {
    return new WriteCommand(Kind.DEV_TEST, actor, Map.of(), null);
  }

  public static WriteCommand snapshot(String actor) {
    return new WriteCommand(Kind.SNAPSHOT, actor, Map.of(), null);
  }

  public static WriteCommand userAuthenticated(
      UserKey key, String email, String displayName, String color) {
    Objects.requireNonNull(key, "key");
    return new WriteCommand(
        Kind.USER_AUTHENTICATED,
        key.subject(),
        userData(key, Map.of("email", email, "displayName", displayName, "color", color)),
        null);
  }

  public static WriteCommand userDeactivated(UserKey key) {
    Objects.requireNonNull(key, "key");
    return new WriteCommand(Kind.USER_DEACTIVATED, key.subject(), userData(key, Map.of()), null);
  }

  public static WriteCommand trackerTokenCreated(
      UserKey owner, UUID tokenId, String label, byte[] digest) {
    Objects.requireNonNull(owner, "owner");
    Objects.requireNonNull(tokenId, "tokenId");
    Objects.requireNonNull(digest, "digest");
    if (digest.length != 32) throw new IllegalArgumentException("digest must contain 32 bytes");
    return new WriteCommand(
        Kind.TOKEN_CREATED,
        owner.subject(),
        userData(
            owner,
            Map.of(
                "tokenId", tokenId.toString(),
                "label", label,
                "digest", Base64.getUrlEncoder().withoutPadding().encodeToString(digest))),
        null);
  }

  public static WriteCommand trackerTokenRevoked(UserKey owner, UUID tokenId) {
    Objects.requireNonNull(owner, "owner");
    Objects.requireNonNull(tokenId, "tokenId");
    return new WriteCommand(
        Kind.TOKEN_REVOKED,
        owner.subject(),
        userData(owner, Map.of("tokenId", tokenId.toString())),
        null);
  }

  public static WriteCommand trackerTokenUsed(UUID tokenId, byte[] digest) {
    Objects.requireNonNull(tokenId, "tokenId");
    Objects.requireNonNull(digest, "digest");
    if (digest.length != 32) throw new IllegalArgumentException("digest must contain 32 bytes");
    return new WriteCommand(
        Kind.TOKEN_USED, tokenId.toString(), Map.of("tokenId", tokenId.toString()), digest);
  }

  EventEnvelope event(Instant at, Projection projection) {
    assert at != null;
    assert projection != null;
    return switch (kind) {
      case DEV_TEST -> EventEnvelope.create(EventTypes.DEV_TEST, at, actor, Map.of());
      case SNAPSHOT ->
          EventEnvelope.create(
              EventTypes.PROJECTION_SNAPSHOT, at, actor, projection.snapshotData());
      case USER_AUTHENTICATED ->
          EventEnvelope.create(EventTypes.IDENTITY_USER_AUTHENTICATED, at, actor, data);
      case USER_DEACTIVATED -> {
        requireActiveUser(projection, data);
        yield EventEnvelope.create(EventTypes.IDENTITY_USER_DEACTIVATED, at, actor, data);
      }
      case TOKEN_CREATED -> {
        requireActiveUser(projection, data);
        yield EventEnvelope.create(EventTypes.IDENTITY_TRACKER_TOKEN_CREATED, at, actor, data);
      }
      case TOKEN_REVOKED -> {
        UserKey owner = key(data);
        requireActiveUser(projection, data);
        UUID tokenId = UUID.fromString((String) data.get("tokenId"));
        Projection.TrackerToken token =
            projection
                .trackerToken(tokenId)
                .filter(candidate -> candidate.owner().equals(owner))
                .filter(candidate -> candidate.revokedAt() == null)
                .orElseThrow(() -> new IllegalStateException("tracker token cannot be revoked"));
        assert token.id().equals(tokenId);
        yield EventEnvelope.create(EventTypes.IDENTITY_TRACKER_TOKEN_REVOKED, at, actor, data);
      }
      case TOKEN_USED -> {
        UUID tokenId = UUID.fromString((String) data.get("tokenId"));
        Projection.TrackerToken token =
            projection
                .activeTrackerToken(digest)
                .filter(candidate -> candidate.id().equals(tokenId))
                .orElseThrow(() -> new IllegalStateException("tracker token is inactive"));
        assert token.id().equals(tokenId);
        yield EventEnvelope.create(EventTypes.IDENTITY_TRACKER_TOKEN_USED, at, actor, data);
      }
    };
  }

  private static void requireActiveUser(Projection projection, Map<String, Object> data) {
    assert projection != null;
    assert data != null;
    if (projection.activeUser(key(data)).isEmpty()) {
      throw new IllegalStateException("user is inactive");
    }
  }

  private static UserKey key(Map<String, Object> data) {
    return new UserKey((String) data.get("issuer"), (String) data.get("subject"));
  }

  private static Map<String, Object> userData(UserKey key, Map<String, Object> additional) {
    assert key != null;
    assert additional != null;
    var data = new HashMap<String, Object>();
    data.put("issuer", key.issuer());
    data.put("subject", key.subject());
    data.putAll(additional);
    return Map.copyOf(data);
  }
}
