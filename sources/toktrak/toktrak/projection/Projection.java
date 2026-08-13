package toktrak.projection;

import static toktrak.store.EventTypes.*;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import toktrak.store.EventEnvelope;

public final class Projection {
  public static final int VERSION = 2;
  private static final int USERS_MAX = 10_000;
  private static final int TOKENS_MAX = 100_000;

  private State state = new State(0, Map.of(), Map.of());
  private long revision;

  private Projection() {}

  public static Projection empty() {
    return new Projection();
  }

  public synchronized int eventCount() {
    assert state.eventCount >= 0;
    return state.eventCount;
  }

  public synchronized Optional<User> user(UserKey key) {
    Objects.requireNonNull(key, "key");
    return Optional.ofNullable(state.users.get(key));
  }

  public synchronized Optional<User> activeUser(UserKey key) {
    Objects.requireNonNull(key, "key");
    User user = state.users.get(key);
    return user != null && user.active ? Optional.of(user) : Optional.empty();
  }

  public synchronized List<TrackerToken> trackerTokens(UserKey owner) {
    Objects.requireNonNull(owner, "owner");
    return state.tokens.values().stream()
        .filter(token -> token.owner.equals(owner))
        .sorted(Comparator.comparing(TrackerToken::createdAt).thenComparing(TrackerToken::id))
        .toList();
  }

  public synchronized Optional<TrackerToken> trackerToken(UUID id) {
    Objects.requireNonNull(id, "id");
    return Optional.ofNullable(state.tokens.get(id));
  }

  public synchronized Optional<TrackerToken> activeTrackerToken(byte[] digest) {
    Objects.requireNonNull(digest, "digest");
    TrackerToken match = null;
    for (TrackerToken token : state.tokens.values()) {
      byte[] candidate = Base64.getUrlDecoder().decode(token.digest);
      boolean equal = MessageDigest.isEqual(digest, candidate);
      if (equal && token.revokedAt == null && activeUser(token.owner).isPresent()) {
        if (match != null) throw new IllegalStateException("duplicate tracker token digest");
        match = token;
      }
    }
    return Optional.ofNullable(match);
  }

  public synchronized Transition prepare(EventEnvelope event) {
    Objects.requireNonNull(event, "event");
    State after = transition(state, event);
    return new Transition(revision, state, after);
  }

  public synchronized void commit(Transition transition) {
    Objects.requireNonNull(transition, "transition");
    if (revision != transition.revisionBefore) {
      throw new IllegalStateException("projection changed before prepared transition commit");
    }
    assert state.equals(transition.before);
    state = transition.after;
    revision = Math.addExact(revision, 1);
    assert state.eventCount >= 0;
  }

  public synchronized void apply(EventEnvelope event) {
    commit(prepare(event));
  }

  public synchronized Map<String, Object> snapshotData() {
    var users = new ArrayList<Map<String, Object>>(state.users.size());
    state.users.values().stream()
        .sorted(Comparator.comparing(user -> user.key.toString()))
        .map(Projection::userData)
        .forEach(users::add);
    var tokens = new ArrayList<Map<String, Object>>(state.tokens.size());
    state.tokens.values().stream()
        .sorted(Comparator.comparing(token -> token.id.toString()))
        .map(Projection::tokenData)
        .forEach(tokens::add);
    return Map.of(
        "projectionVersion", VERSION,
        "eventCount", state.eventCount,
        "users", users,
        "trackerTokens", tokens);
  }

  private static State transition(State before, EventEnvelope event) {
    if (event.type().equals(PROJECTION_SNAPSHOT)) {
      return compatible(event) ? snapshotState(event) : before;
    }
    int count = Math.addExact(before.eventCount, 1);
    return switch (event.type()) {
      case IDENTITY_USER_AUTHENTICATED -> authenticated(before, event, count);
      case IDENTITY_USER_DEACTIVATED -> deactivated(before, event, count);
      case IDENTITY_TRACKER_TOKEN_CREATED -> tokenCreated(before, event, count);
      case IDENTITY_TRACKER_TOKEN_REVOKED -> tokenRevoked(before, event, count);
      case IDENTITY_TRACKER_TOKEN_USED -> tokenUsed(before, event, count);
      default -> new State(count, before.users, before.tokens);
    };
  }

  private static State authenticated(State before, EventEnvelope event, int count) {
    UserKey key = userKey(event.data());
    User user =
        new User(
            key,
            string(event.data(), "email", 320),
            string(event.data(), "displayName", 256),
            color(event.data()),
            true,
            event.at());
    var users = new HashMap<>(before.users);
    users.put(key, user);
    if (users.size() > USERS_MAX) throw new IllegalStateException("users exceed " + USERS_MAX);
    return new State(count, Map.copyOf(users), before.tokens);
  }

  private static State deactivated(State before, EventEnvelope event, int count) {
    UserKey key = userKey(event.data());
    User existing = before.users.get(key);
    if (existing == null || !existing.active) {
      throw new IllegalStateException("cannot deactivate inactive user");
    }
    var users = new HashMap<>(before.users);
    users.put(
        key,
        new User(
            key,
            existing.email,
            existing.displayName,
            existing.color,
            false,
            existing.authenticatedAt));
    var tokens = new HashMap<>(before.tokens);
    for (TrackerToken token : before.tokens.values()) {
      if (token.owner.equals(key) && token.revokedAt == null) {
        tokens.put(
            token.id,
            new TrackerToken(
                token.id,
                token.owner,
                token.label,
                token.digest,
                token.createdAt,
                token.lastUsedAt,
                event.at()));
      }
    }
    return new State(count, Map.copyOf(users), Map.copyOf(tokens));
  }

  private static State tokenCreated(State before, EventEnvelope event, int count) {
    UserKey owner = userKey(event.data());
    if (!active(before, owner)) throw new IllegalStateException("tracker token owner is inactive");
    UUID id = uuid(event.data(), "tokenId");
    String digest = digest(event.data());
    if (before.tokens.containsKey(id))
      throw new IllegalStateException("duplicate tracker token ID");
    for (TrackerToken token : before.tokens.values()) {
      if (token.digest.equals(digest))
        throw new IllegalStateException("duplicate tracker token digest");
    }
    var tokens = new HashMap<>(before.tokens);
    tokens.put(
        id,
        new TrackerToken(
            id, owner, string(event.data(), "label", 128), digest, event.at(), null, null));
    if (tokens.size() > TOKENS_MAX)
      throw new IllegalStateException("tracker tokens exceed " + TOKENS_MAX);
    return new State(count, before.users, Map.copyOf(tokens));
  }

  private static State tokenRevoked(State before, EventEnvelope event, int count) {
    UserKey owner = userKey(event.data());
    UUID id = uuid(event.data(), "tokenId");
    TrackerToken token = before.tokens.get(id);
    if (token == null
        || !token.owner.equals(owner)
        || token.revokedAt != null
        || !active(before, owner)) {
      throw new IllegalStateException("tracker token cannot be revoked");
    }
    var tokens = new HashMap<>(before.tokens);
    tokens.put(
        id,
        new TrackerToken(
            id,
            token.owner,
            token.label,
            token.digest,
            token.createdAt,
            token.lastUsedAt,
            event.at()));
    return new State(count, before.users, Map.copyOf(tokens));
  }

  private static State tokenUsed(State before, EventEnvelope event, int count) {
    UUID id = uuid(event.data(), "tokenId");
    TrackerToken token = before.tokens.get(id);
    if (token == null || token.revokedAt != null || !active(before, token.owner)) {
      throw new IllegalStateException("tracker token is inactive");
    }
    var tokens = new HashMap<>(before.tokens);
    tokens.put(
        id,
        new TrackerToken(
            id, token.owner, token.label, token.digest, token.createdAt, event.at(), null));
    return new State(count, before.users, Map.copyOf(tokens));
  }

  private static State snapshotState(EventEnvelope event) {
    int count = exactInt(event.data().get("eventCount"));
    if (count < 0) throw new IllegalStateException("projection snapshot is corrupt");
    var users = new HashMap<UserKey, User>();
    for (Map<String, Object> data : objects(event.data().getOrDefault("users", List.of()))) {
      UserKey key = userKey(data);
      User user =
          new User(
              key,
              string(data, "email", 320),
              string(data, "displayName", 256),
              color(data),
              bool(data, "active"),
              instant(data, "authenticatedAt"));
      if (users.put(key, user) != null) throw new IllegalStateException("duplicate snapshot user");
    }
    var tokens = new HashMap<UUID, TrackerToken>();
    for (Map<String, Object> data :
        objects(event.data().getOrDefault("trackerTokens", List.of()))) {
      UUID id = uuid(data, "tokenId");
      UserKey owner = userKey(data);
      if (!users.containsKey(owner))
        throw new IllegalStateException("snapshot token owner missing");
      TrackerToken token =
          new TrackerToken(
              id,
              owner,
              string(data, "label", 128),
              digest(data),
              instant(data, "createdAt"),
              nullableInstant(data.get("lastUsedAt")),
              nullableInstant(data.get("revokedAt")));
      if (tokens.put(id, token) != null)
        throw new IllegalStateException("duplicate snapshot tracker token");
    }
    if (users.size() > USERS_MAX || tokens.size() > TOKENS_MAX) {
      throw new IllegalStateException("projection snapshot exceeds collection limits");
    }
    return new State(count, Map.copyOf(users), Map.copyOf(tokens));
  }

  private static boolean active(State state, UserKey key) {
    User user = state.users.get(key);
    return user != null && user.active;
  }

  private static boolean compatible(EventEnvelope event) {
    try {
      return exactInt(event.data().get("projectionVersion")) == VERSION;
    } catch (IllegalStateException exception) {
      return false;
    }
  }

  private static UserKey userKey(Map<String, Object> data) {
    return new UserKey(string(data, "issuer", 2_048), string(data, "subject", 256));
  }

  private static String color(Map<String, Object> data) {
    String color = string(data, "color", 7);
    if (!color.matches("#[0-9a-f]{6}")) throw new IllegalStateException("invalid user color");
    return color;
  }

  private static String digest(Map<String, Object> data) {
    String digest = string(data, "digest", 64);
    try {
      if (Base64.getUrlDecoder().decode(digest).length != 32) throw new IllegalArgumentException();
      return digest;
    } catch (IllegalArgumentException exception) {
      throw new IllegalStateException("invalid tracker token digest", exception);
    }
  }

  private static String string(Map<String, Object> data, String name, int charactersMax) {
    Object value = data.get(name);
    if (!(value instanceof String string)
        || string.isBlank()
        || string.length() > charactersMax
        || string.getBytes(StandardCharsets.UTF_8).length > charactersMax * 4L) {
      throw new IllegalStateException("invalid " + name);
    }
    return string;
  }

  private static boolean bool(Map<String, Object> data, String name) {
    Object value = data.get(name);
    if (!(value instanceof Boolean result)) throw new IllegalStateException("invalid " + name);
    return result;
  }

  private static UUID uuid(Map<String, Object> data, String name) {
    try {
      return UUID.fromString(string(data, name, 36));
    } catch (IllegalArgumentException exception) {
      throw new IllegalStateException("invalid " + name, exception);
    }
  }

  private static Instant instant(Map<String, Object> data, String name) {
    return nullableInstant(data.get(name));
  }

  private static Instant nullableInstant(Object value) {
    if (value == null) return null;
    if (!(value instanceof String string)) throw new IllegalStateException("invalid instant");
    try {
      return Instant.parse(string);
    } catch (RuntimeException exception) {
      throw new IllegalStateException("invalid instant", exception);
    }
  }

  private static List<Map<String, Object>> objects(Object value) {
    if (!(value instanceof List<?> list)) throw new IllegalStateException("invalid snapshot list");
    var result = new ArrayList<Map<String, Object>>(list.size());
    for (Object element : list) {
      if (!(element instanceof Map<?, ?> map))
        throw new IllegalStateException("invalid snapshot object");
      var converted = new HashMap<String, Object>();
      for (Map.Entry<?, ?> entry : map.entrySet()) {
        if (!(entry.getKey() instanceof String key))
          throw new IllegalStateException("invalid snapshot object key");
        converted.put(key, entry.getValue());
      }
      result.add(java.util.Collections.unmodifiableMap(converted));
    }
    return List.copyOf(result);
  }

  private static int exactInt(Object value) {
    if (!(value instanceof Number number)) throw new IllegalStateException("invalid event count");
    try {
      return new BigDecimal(number.toString()).intValueExact();
    } catch (ArithmeticException | NumberFormatException exception) {
      throw new IllegalStateException("invalid event count", exception);
    }
  }

  private static Map<String, Object> userData(User user) {
    return Map.of(
        "issuer", user.key.issuer,
        "subject", user.key.subject,
        "email", user.email,
        "displayName", user.displayName,
        "color", user.color,
        "active", user.active,
        "authenticatedAt", user.authenticatedAt.toString());
  }

  private static Map<String, Object> tokenData(TrackerToken token) {
    var data = new HashMap<String, Object>();
    data.put("tokenId", token.id.toString());
    data.put("issuer", token.owner.issuer);
    data.put("subject", token.owner.subject);
    data.put("label", token.label);
    data.put("digest", token.digest);
    data.put("createdAt", token.createdAt.toString());
    data.put("lastUsedAt", token.lastUsedAt == null ? null : token.lastUsedAt.toString());
    data.put("revokedAt", token.revokedAt == null ? null : token.revokedAt.toString());
    return data;
  }

  private record State(int eventCount, Map<UserKey, User> users, Map<UUID, TrackerToken> tokens) {
    private State {
      assert eventCount >= 0;
      assert users != null && users.size() <= USERS_MAX;
      assert tokens != null && tokens.size() <= TOKENS_MAX;
    }
  }

  public static final class Transition {
    private final long revisionBefore;
    private final State before;
    private final State after;

    private Transition(long revisionBefore, State before, State after) {
      assert revisionBefore >= 0;
      assert before != null;
      assert after != null;
      this.revisionBefore = revisionBefore;
      this.before = before;
      this.after = after;
    }
  }

  public record UserKey(String issuer, String subject) {
    public UserKey {
      if (issuer == null || issuer.isBlank() || issuer.length() > 2_048)
        throw new IllegalArgumentException("issuer is required");
      if (subject == null
          || subject.isBlank()
          || subject.length() > 256
          || subject.getBytes(StandardCharsets.UTF_8).length > 256) {
        throw new IllegalArgumentException("subject is required");
      }
    }

    @Override
    public String toString() {
      return issuer + "\n" + subject;
    }
  }

  public record User(
      UserKey key,
      String email,
      String displayName,
      String color,
      boolean active,
      Instant authenticatedAt) {
    public User {
      Objects.requireNonNull(key, "key");
      Objects.requireNonNull(email, "email");
      Objects.requireNonNull(displayName, "displayName");
      Objects.requireNonNull(color, "color");
      Objects.requireNonNull(authenticatedAt, "authenticatedAt");
    }
  }

  public record TrackerToken(
      UUID id,
      UserKey owner,
      String label,
      String digest,
      Instant createdAt,
      Instant lastUsedAt,
      Instant revokedAt) {
    public TrackerToken {
      Objects.requireNonNull(id, "id");
      Objects.requireNonNull(owner, "owner");
      Objects.requireNonNull(label, "label");
      Objects.requireNonNull(digest, "digest");
      Objects.requireNonNull(createdAt, "createdAt");
    }
  }
}
