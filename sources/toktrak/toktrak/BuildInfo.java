package toktrak;

import java.util.Map;
import java.util.Objects;

public record BuildInfo(String version, String revision) {
  private static final String DEVELOPMENT = "dev";

  public BuildInfo {
    Objects.requireNonNull(version, "version");
    Objects.requireNonNull(revision, "revision");
    boolean canonicalRevision = revision.matches("[0-9a-f]{40}|[0-9a-f]{64}");
    boolean development =
        version.equals(DEVELOPMENT) && (revision.equals(DEVELOPMENT) || canonicalRevision);
    boolean release = releaseVersion(version) && canonicalRevision;
    if (!development && !release) {
      throw new IllegalArgumentException("build version and revision are invalid");
    }
  }

  private static boolean releaseVersion(String value) {
    assert value != null;
    if (!value.matches("v(?:0|[1-9][0-9]{0,9})")) return false;
    try {
      Integer.parseInt(value.substring(1));
      return true;
    } catch (NumberFormatException exception) {
      return false;
    }
  }

  public static BuildInfo from(Map<String, String> environment) {
    Objects.requireNonNull(environment, "environment");
    String version = environment.get("TOKTRAK_VERSION");
    String revision = environment.get("TOKTRAK_REVISION");
    if (version == null && revision == null) return new BuildInfo(DEVELOPMENT, DEVELOPMENT);
    if (version == null) {
      throw new IllegalArgumentException("TOKTRAK_VERSION is required with TOKTRAK_REVISION");
    }
    if (revision == null) {
      throw new IllegalArgumentException("TOKTRAK_REVISION is required with TOKTRAK_VERSION");
    }
    return new BuildInfo(version, revision);
  }

  public String shortRevision() {
    return revision.equals(DEVELOPMENT) ? DEVELOPMENT : revision.substring(0, 12);
  }

  public boolean development() {
    return version.equals(DEVELOPMENT);
  }
}
