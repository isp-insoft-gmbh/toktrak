package toktrak;

import java.net.InetAddress;
import java.net.URI;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record Config(
    int port,
    Path dataDirectory,
    String baseUrl,
    boolean devAuth,
    Path corpus,
    boolean failWrites,
    ClockSource clock,
    String oidcDiscoveryUrl,
    String oidcClientId,
    String oidcClientSecret,
    String allowedDomain,
    String sessionSecret,
    String tokenPepper) {
  private static final int ARGUMENTS_MAX = 64;
  private static final int VALUE_CHARACTERS_MAX = 8 * 1024;
  private static final List<String> AUTH_NAMES =
      List.of(
          "TOKTRAK_OIDC_DISCOVERY_URL",
          "TOKTRAK_OIDC_CLIENT_ID",
          "TOKTRAK_OIDC_CLIENT_SECRET",
          "TOKTRAK_ALLOWED_DOMAIN",
          "TOKTRAK_SESSION_SECRET",
          "TOKTRAK_TOKEN_PEPPER");

  public Config {
    if (port < 0 || port > 65_535) throw new IllegalArgumentException("port must be 0..65535");
    Objects.requireNonNull(dataDirectory, "dataDirectory");
    Objects.requireNonNull(clock, "clock");
    assert baseUrl == null || baseUrl.length() <= VALUE_CHARACTERS_MAX;
    assert devAuth == (oidcDiscoveryUrl == null);
  }

  public static Config from(String[] args, Map<String, String> environment) {
    Objects.requireNonNull(args, "args");
    Objects.requireNonNull(environment, "environment");
    if (args.length > ARGUMENTS_MAX) {
      throw new IllegalArgumentException("arguments exceed " + ARGUMENTS_MAX + " entries");
    }
    for (int index = 0; index < args.length; index++) {
      requireLength(Objects.requireNonNull(args[index], "argument"), "argument " + index);
    }
    String devValue = environmentValue(environment, "TOKTRAK_DEV_AUTH");
    boolean dev = "true".equalsIgnoreCase(devValue == null ? "false" : devValue);
    String data = environmentValue(environment, "TOKTRAK_DATA_DIR");
    if (data == null || data.isBlank()) {
      if (!dev)
        throw new IllegalArgumentException("TOKTRAK_DEV_AUTH=true or TOKTRAK_DATA_DIR is required");
      data = Path.of("output", "toktrak-dev", "data").toString();
    }
    String baseUrl = environmentValue(environment, "TOKTRAK_BASE_URL");
    if (!dev && (baseUrl == null || baseUrl.isBlank())) {
      throw new IllegalArgumentException("TOKTRAK_BASE_URL is required");
    }
    if (!dev && baseUrl != null && isNonLocalHttp(baseUrl)) {
      throw new IllegalArgumentException("non-local HTTP requires TOKTRAK_DEV_AUTH=true");
    }
    if (!dev) requireSecureOrLocalUri(baseUrl, "TOKTRAK_BASE_URL");

    String oidcDiscoveryUrl = null;
    String oidcClientId = null;
    String oidcClientSecret = null;
    String allowedDomain = null;
    String sessionSecret = null;
    String tokenPepper = null;
    if (dev) {
      for (String name : AUTH_NAMES) {
        if (environmentValue(environment, name) != null) {
          throw new IllegalArgumentException(name + " is forbidden with TOKTRAK_DEV_AUTH=true");
        }
      }
    } else {
      oidcDiscoveryUrl = requiredEnvironment(environment, "TOKTRAK_OIDC_DISCOVERY_URL");
      requireSecureOrLocalUri(oidcDiscoveryUrl, "TOKTRAK_OIDC_DISCOVERY_URL");
      oidcClientId = requiredEnvironment(environment, "TOKTRAK_OIDC_CLIENT_ID");
      oidcClientSecret = requiredEnvironment(environment, "TOKTRAK_OIDC_CLIENT_SECRET");
      allowedDomain = requiredEnvironment(environment, "TOKTRAK_ALLOWED_DOMAIN");
      if (!allowedDomain.matches(
          "(?i)[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)+")) {
        throw new IllegalArgumentException("TOKTRAK_ALLOWED_DOMAIN is invalid");
      }
      sessionSecret = requiredEnvironment(environment, "TOKTRAK_SESSION_SECRET");
      tokenPepper = requiredEnvironment(environment, "TOKTRAK_TOKEN_PEPPER");
      requireSecret(sessionSecret, "TOKTRAK_SESSION_SECRET");
      requireSecret(tokenPepper, "TOKTRAK_TOKEN_PEPPER");
    }

    String portValue = environmentValue(environment, "TOKTRAK_PORT");
    int port = parsePort(portValue == null ? "8080" : portValue);
    Path corpus = null;
    boolean failWrites = false;
    boolean customClock = false;
    ClockSource clock = ClockSource.system();
    for (int index = 0; index < args.length; index++) {
      switch (args[index]) {
        case "--corpus" -> {
          requireValue(args, index, "--corpus");
          index = Math.addExact(index, 1);
          corpus = Path.of(args[index]);
        }
        case "--fail-writes" -> failWrites = true;
        case "--clock" -> {
          requireValue(args, index, "--clock");
          index = Math.addExact(index, 1);
          clock = ClockSource.fixed(Instant.parse(args[index]));
          customClock = true;
        }
        case "--help" -> {}
        default -> throw new IllegalArgumentException("unknown argument: " + args[index]);
      }
    }
    if (!dev && corpus != null)
      throw new IllegalArgumentException("--corpus requires TOKTRAK_DEV_AUTH=true");
    if (!dev && failWrites)
      throw new IllegalArgumentException("--fail-writes requires TOKTRAK_DEV_AUTH=true");
    if (!dev && customClock)
      throw new IllegalArgumentException("--clock requires TOKTRAK_DEV_AUTH=true");
    return new Config(
        port,
        Path.of(data),
        baseUrl,
        dev,
        corpus,
        failWrites,
        clock,
        oidcDiscoveryUrl,
        oidcClientId,
        oidcClientSecret,
        allowedDomain,
        sessionSecret,
        tokenPepper);
  }

  public InetAddress bindAddress() {
    return InetAddress.ofLiteral(devAuth ? "127.0.0.1" : "0.0.0.0");
  }

  public byte[] sessionSecretBytes() {
    if (devAuth) throw new IllegalStateException("development has no configured session secret");
    return Base64.getDecoder().decode(sessionSecret);
  }

  public byte[] tokenPepperBytes() {
    if (devAuth) throw new IllegalStateException("development has no configured token pepper");
    return Base64.getDecoder().decode(tokenPepper);
  }

  private static String requiredEnvironment(Map<String, String> environment, String name) {
    String value = environmentValue(environment, name);
    if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
    return value;
  }

  private static String environmentValue(Map<String, String> environment, String name) {
    assert environment != null;
    assert name != null && !name.isBlank();
    String value = environment.get(name);
    if (value != null) requireLength(value, name);
    return value;
  }

  private static void requireLength(String value, String name) {
    assert value != null;
    assert name != null && !name.isBlank();
    if (value.length() > VALUE_CHARACTERS_MAX) {
      throw new IllegalArgumentException(name + " exceeds " + VALUE_CHARACTERS_MAX + " characters");
    }
  }

  private static void requireValue(String[] args, int index, String option) {
    assert args != null && args.length <= ARGUMENTS_MAX;
    assert index >= 0 && index < args.length;
    assert option != null && !option.isBlank();
    if (index + 1 >= args.length) throw new IllegalArgumentException(option + " requires a value");
  }

  private static int parsePort(String value) {
    assert value != null;
    try {
      int port = Integer.parseInt(value);
      if (port < 0 || port > 65_535) throw new NumberFormatException();
      return port;
    } catch (NumberFormatException exception) {
      throw new IllegalArgumentException("TOKTRAK_PORT must be 0..65535", exception);
    }
  }

  private static boolean isNonLocalHttp(String value) {
    assert value != null && value.length() <= VALUE_CHARACTERS_MAX;
    URI uri = uri(value, "TOKTRAK_BASE_URL");
    String host = uri.getHost();
    return "http".equalsIgnoreCase(uri.getScheme())
        && host != null
        && !host.equalsIgnoreCase("localhost")
        && !host.equals("127.0.0.1")
        && !host.equals("::1");
  }

  private static void requireSecureOrLocalUri(String value, String name) {
    URI uri = uri(value, name);
    String host = uri.getHost();
    boolean local =
        "http".equalsIgnoreCase(uri.getScheme())
            && ("localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host));
    if (!("https".equalsIgnoreCase(uri.getScheme()) || local)
        || host == null
        || uri.getFragment() != null) {
      throw new IllegalArgumentException(name + " is invalid");
    }
  }

  private static URI uri(String value, String name) {
    try {
      return URI.create(value);
    } catch (IllegalArgumentException exception) {
      throw new IllegalArgumentException(name + " must be a valid URL", exception);
    }
  }

  private static void requireSecret(String value, String name) {
    boolean valid;
    try {
      valid = Base64.getDecoder().decode(value).length >= 32;
    } catch (IllegalArgumentException exception) {
      valid = false;
    }
    if (!valid) throw new IllegalArgumentException(name + " must be Base64 with at least 32 bytes");
  }
}
