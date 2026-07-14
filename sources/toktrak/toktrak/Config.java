package toktrak;

import java.net.URI;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

public record Config(
    int port,
    Path dataDirectory,
    String baseUrl,
    boolean devAuth,
    Path corpus,
    boolean failWrites,
    ClockSource clock) {
  private static final int ARGUMENTS_MAX = 64;
  private static final int VALUE_CHARACTERS_MAX = 8 * 1024;

  public Config {
    if (port < 0 || port > 65_535) throw new IllegalArgumentException("port must be 0..65535");
    Objects.requireNonNull(dataDirectory, "dataDirectory");
    Objects.requireNonNull(clock, "clock");
    assert baseUrl == null || baseUrl.length() <= VALUE_CHARACTERS_MAX;
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
    var config = new Config(port, Path.of(data), baseUrl, dev, corpus, failWrites, clock);
    assert config.port == port;
    return config;
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
      throw new IllegalArgumentException("TOKTRAK_PORT must be 0..65535");
    }
  }

  private static boolean isNonLocalHttp(String value) {
    assert value != null && value.length() <= VALUE_CHARACTERS_MAX;
    URI uri;
    try {
      uri = URI.create(value);
    } catch (IllegalArgumentException exception) {
      throw new IllegalArgumentException("TOKTRAK_BASE_URL must be a valid URL", exception);
    }
    String host = uri.getHost();
    return "http".equalsIgnoreCase(uri.getScheme())
        && host != null
        && !host.equalsIgnoreCase("localhost")
        && !host.equals("127.0.0.1")
        && !host.equals("::1");
  }
}
