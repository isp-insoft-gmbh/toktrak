package toktrak;

import java.net.URI;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;

public record Config(
    int port,
    Path dataDir,
    String baseUrl,
    boolean devAuth,
    Path corpus,
    boolean failWrites,
    ClockSource clock) {

  public static Config from(String[] args, Map<String, String> env) {
    boolean dev = "true".equalsIgnoreCase(env.getOrDefault("TOKTRAK_DEV_AUTH", "false"));
    String data = env.get("TOKTRAK_DATA_DIR");
    if (data == null || data.isBlank()) {
      if (!dev) throw new IllegalArgumentException("TOKTRAK_DEV_AUTH=true or TOKTRAK_DATA_DIR is required");
      data = Path.of("output", "toktrak-dev", "data").toString();
    }
    String baseUrl = env.get("TOKTRAK_BASE_URL");
    if (!dev && (baseUrl == null || baseUrl.isBlank())) {
      throw new IllegalArgumentException("TOKTRAK_BASE_URL is required");
    }
    if (!dev && baseUrl != null && isNonLocalHttp(baseUrl)) {
      throw new IllegalArgumentException("non-local HTTP requires TOKTRAK_DEV_AUTH=true");
    }

    int port = parsePort(env.getOrDefault("TOKTRAK_PORT", "8080"));
    Path corpus = null;
    boolean failWrites = false;
    ClockSource clock = ClockSource.system();
    for (int i = 0; i < args.length; i++) {
      switch (args[i]) {
        case "--corpus" -> {
          requireValue(args, i++, "--corpus");
          corpus = Path.of(args[i]);
        }
        case "--fail-writes" -> failWrites = true;
        case "--clock" -> {
          requireValue(args, i++, "--clock");
          clock = ClockSource.fixed(Instant.parse(args[i]));
        }
        case "--help" -> {}
        default -> throw new IllegalArgumentException("unknown argument: " + args[i]);
      }
    }
    if (!dev && corpus != null) throw new IllegalArgumentException("--corpus requires TOKTRAK_DEV_AUTH=true");
    if (!dev && failWrites) throw new IllegalArgumentException("--fail-writes requires TOKTRAK_DEV_AUTH=true");
    if (!dev && clock != null && argsContain(args, "--clock")) {
      throw new IllegalArgumentException("--clock requires TOKTRAK_DEV_AUTH=true");
    }
    return new Config(port, Path.of(data), baseUrl, dev, corpus, failWrites, clock);
  }

  private static boolean argsContain(String[] args, String wanted) {
    for (String arg : args) if (arg.equals(wanted)) return true;
    return false;
  }

  private static void requireValue(String[] args, int index, String option) {
    if (index + 1 >= args.length) throw new IllegalArgumentException(option + " requires a value");
  }

  private static int parsePort(String value) {
    try {
      int port = Integer.parseInt(value);
      if (port < 0 || port > 65535) throw new NumberFormatException();
      return port;
    } catch (NumberFormatException ex) {
      throw new IllegalArgumentException("TOKTRAK_PORT must be 0..65535");
    }
  }

  private static boolean isNonLocalHttp(String value) {
    URI uri;
    try {
      uri = URI.create(value);
    } catch (IllegalArgumentException ex) {
      throw new IllegalArgumentException("TOKTRAK_BASE_URL must be a valid URL", ex);
    }
    String host = uri.getHost();
    return "http".equalsIgnoreCase(uri.getScheme())
        && host != null
        && !host.equalsIgnoreCase("localhost")
        && !host.equals("127.0.0.1")
        && !host.equals("::1");
  }
}
