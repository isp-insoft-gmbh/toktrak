package toktrak.store;

public final class EventTypes {
  public static final String DEV_TEST = "dev-test";
  public static final String PROJECTION_SNAPSHOT = "projection-snapshot";
  public static final String IDENTITY_USER_AUTHENTICATED = "identity-user-authenticated";
  public static final String IDENTITY_USER_DEACTIVATED = "identity-user-deactivated";
  public static final String IDENTITY_TRACKER_TOKEN_CREATED = "identity-tracker-token-created";
  public static final String IDENTITY_TRACKER_TOKEN_REVOKED = "identity-tracker-token-revoked";
  public static final String IDENTITY_TRACKER_TOKEN_USED = "identity-tracker-token-used";

  private EventTypes() {}
}
