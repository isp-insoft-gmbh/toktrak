import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/// TokTrak performance suite entrypoint.
///
/// Usage: `java -ea tools/perf/Perf.java [benchmark-id...]`
///
/// Side effects: writes disposable benchmark artifacts under `output/perf/<timestamp>/`.
public final class Perf {
  private static final Path ROOT = Path.of("").toAbsolutePath().normalize();
  private static final Path OUTPUT = ROOT.resolve("output/perf");
  private static final Path MAIN_DEPS = ROOT.resolve("output/deps/main");
  private static final Path MODULE_CLASSES = ROOT.resolve("output/modules/target/classes");
  private static final Path JMH_DEPS = ROOT.resolve("output/perf-deps/jmh");
  private static final Path PERF_CLASSES = ROOT.resolve("output/perf-classes");
  private static final Path PERF_DEPENDENCIES = ROOT.resolve("sources/perf-deps.txt");
  private static final String RESULT_FILE_ARGUMENT = "{result-file}";
  private static final DateTimeFormatter RUN_DIRECTORY_FORMAT =
      DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'", Locale.ROOT).withZone(ZoneOffset.UTC);

  private Perf() {}

  public static void main(String[] args) throws Exception {
    requireAssertions();
    if (List.of(args).equals(List.of("--help"))) {
      help();
      return;
    }
    if (List.of(args).equals(List.of("--jmh-classpath"))) {
      System.out.println(jmhClasspath());
      return;
    }
    List<Benchmark> selected = selectBenchmarks(benchmarks(), List.of(args));
    Path runDirectory = OUTPUT.resolve(RUN_DIRECTORY_FORMAT.format(Instant.now()));
    Files.createDirectories(runDirectory);
    Host host = host();
    Files.writeString(runDirectory.resolve("host.json"), host.json(), StandardCharsets.UTF_8);
    List<Result> results = new ArrayList<>();
    for (Benchmark benchmark : selected) {
      results.add(runBenchmark(runDirectory, benchmark));
    }
    Files.writeString(
        runDirectory.resolve("summary.md"),
        summary(runDirectory, host, results),
        StandardCharsets.UTF_8);
    if (results.stream().anyMatch(result -> result.exitCode() != 0)) {
      throw new IllegalStateException("one or more performance benchmarks failed");
    }
    System.out.println("perf artifacts: " + projectPath(runDirectory));
  }

  private static void requireAssertions() {
    boolean enabled = false;
    assert enabled = true;
    if (!enabled) throw new IllegalStateException("assertions must be enabled with -ea");
  }

  private static void help() {
    System.out.println("usage: java -ea tools/perf/Perf.java [benchmark-id...]");
    System.out.println("       java -ea tools/perf/Perf.java --jmh-classpath");
  }

  private static List<Benchmark> benchmarks() throws IOException {
    return List.of(
        new Benchmark(
            "corpus-replay",
            "Rebuild the in-memory projection from the development event corpus",
            List.of(
                javaExecutable(),
                "-ea",
                "--module-path",
                applicationModulePath(),
                "--add-modules",
                "toktrak",
                "--class-path",
                benchmarkClasspath(),
                "org.openjdk.jmh.Main",
                "^toktrak\\.perf\\.CorpusReplayBenchmark\\.replay$",
                "-foe",
                "true",
                "-rf",
                "json",
                "-rff",
                RESULT_FILE_ARGUMENT)));
  }

  private static List<Benchmark> selectBenchmarks(
      List<Benchmark> benchmarks, List<String> requested) {
    assert benchmarks != null;
    assert requested != null;
    if (requested.isEmpty()) return benchmarks;
    var selected = new ArrayList<Benchmark>();
    for (String id : new LinkedHashSet<>(requested)) {
      selected.add(
          benchmarks.stream()
              .filter(benchmark -> benchmark.id().equals(id))
              .findFirst()
              .orElseThrow(() -> new IllegalArgumentException("unknown benchmark: " + id)));
    }
    return List.copyOf(selected);
  }

  private static Result runBenchmark(Path runDirectory, Benchmark benchmark)
      throws IOException, InterruptedException {
    assert runDirectory != null;
    assert benchmark != null;
    Path benchmarkDirectory = runDirectory.resolve(benchmark.id());
    Files.createDirectories(benchmarkDirectory);
    long started = System.nanoTime();
    List<String> command = benchmark.command(benchmarkDirectory);
    Process process =
        new ProcessBuilder(command)
            .directory(ROOT.toFile())
            .redirectOutput(benchmarkDirectory.resolve("stdout.txt").toFile())
            .redirectError(benchmarkDirectory.resolve("stderr.txt").toFile())
            .start();
    int exitCode = process.waitFor();
    long elapsedNanos = System.nanoTime() - started;
    Files.writeString(
        benchmarkDirectory.resolve("benchmark.json"),
        benchmark.json(command, exitCode, elapsedNanos),
        StandardCharsets.UTF_8);
    return new Result(benchmark, exitCode, elapsedNanos);
  }

  private static Host host() {
    return new Host(
        getenv("GITHUB_ACTIONS").equals("true") ? getenv("PERF_RUNNER_LABEL") : "local",
        getenv("ImageOS"),
        System.getProperty("os.name"),
        System.getProperty("os.version"),
        System.getProperty("os.arch"),
        cpuModel(),
        Runtime.getRuntime().availableProcessors(),
        System.getProperty("java.runtime.version"),
        System.getProperty("java.vendor"),
        commandVersion("node", "--version"),
        commandVersion("hyperfine", "--version"),
        jmhVersion(),
        commandVersion("git", "rev-parse", "HEAD"));
  }

  private static String cpuModel() {
    if (System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("windows")) {
      return getenv("PROCESSOR_IDENTIFIER");
    }
    Path cpuinfo = Path.of("/proc/cpuinfo");
    if (!Files.isRegularFile(cpuinfo)) return "unknown";
    try {
      return Files.readAllLines(cpuinfo, StandardCharsets.UTF_8).stream()
          .filter(line -> line.startsWith("model name"))
          .map(line -> line.substring(line.indexOf(':') + 1).trim())
          .findFirst()
          .orElse("unknown");
    } catch (IOException exception) {
      return "unknown";
    }
  }

  private static String applicationModulePath() throws IOException {
    return MODULE_CLASSES + File.pathSeparator + jarClasspath(MAIN_DEPS, "application");
  }

  private static String benchmarkClasspath() throws IOException {
    return PERF_CLASSES + File.pathSeparator + jmhClasspath();
  }

  private static String jmhClasspath() throws IOException {
    return jarClasspath(JMH_DEPS, "JMH");
  }

  private static String jarClasspath(Path directory, String description) throws IOException {
    assert directory != null;
    assert description != null && !description.isBlank();
    var jars = new ArrayList<Path>();
    int entries = 0;
    try (var stream = Files.newDirectoryStream(directory)) {
      for (Path path : stream) {
        entries = Math.addExact(entries, 1);
        if (entries > 256) {
          throw new IllegalStateException(
              "resolved " + description + " classpath directory is too large");
        }
        if (path.getFileName().toString().endsWith(".jar")) jars.add(path);
      }
    }
    if (jars.isEmpty()) {
      throw new IllegalStateException("resolved " + description + " classpath is empty");
    }
    jars.sort(Comparator.naturalOrder());
    return jars.stream()
        .map(Path::toString)
        .reduce((left, right) -> left + File.pathSeparator + right)
        .orElseThrow();
  }

  private static String javaExecutable() {
    String name =
        System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("windows")
            ? "java.exe"
            : "java";
    return Path.of(System.getProperty("java.home"), "bin", name).toString();
  }

  private static String jmhVersion() {
    String prefix = "pkg:maven/org.openjdk.jmh/jmh-core@";
    try {
      if (Files.size(PERF_DEPENDENCIES) > 64 * 1024) return "unavailable";
      return Files.readAllLines(PERF_DEPENDENCIES, StandardCharsets.UTF_8).stream()
          .filter(line -> line.startsWith(prefix))
          .map(line -> line.substring(prefix.length()))
          .findFirst()
          .orElse("unavailable");
    } catch (IOException exception) {
      return "unavailable";
    }
  }

  private static String commandVersion(String... command) {
    assert command != null;
    try {
      Process process =
          new ProcessBuilder(command).directory(ROOT.toFile()).redirectErrorStream(true).start();
      String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      if (process.waitFor() != 0) return "unavailable";
      return output.lines().findFirst().orElse("unknown").trim();
    } catch (IOException exception) {
      return "unavailable";
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      return "interrupted";
    }
  }

  private static String getenv(String name) {
    assert name != null;
    String value = System.getenv(name);
    return value == null || value.isBlank() ? "unknown" : value;
  }

  private static String summary(Path runDirectory, Host host, List<Result> results) {
    assert runDirectory != null;
    assert host != null;
    assert results != null;
    var markdown = new StringBuilder();
    markdown.append("# TokTrak performance run\n\n");
    markdown.append("Artifacts: `").append(projectPath(runDirectory)).append("`\n\n");
    markdown.append("Host key: `").append(host.hostKey()).append("`\n\n");
    if (results.isEmpty()) {
      markdown.append(
          "No benchmarks registered yet. Perf golem owns adding the first benchmark.\n");
      return markdown.toString();
    }
    markdown.append("| Benchmark | Purpose | Exit | Elapsed |\n");
    markdown.append("| --- | --- | ---: | ---: |\n");
    results.stream()
        .sorted(Comparator.comparing(result -> result.benchmark().id()))
        .forEach(
            result ->
                markdown
                    .append("| `")
                    .append(result.benchmark().id())
                    .append("` | ")
                    .append(result.benchmark().purpose())
                    .append(" | ")
                    .append(result.exitCode())
                    .append(" | ")
                    .append(formatDuration(result.elapsedNanos()))
                    .append(" |\n"));
    return markdown.toString();
  }

  private static String projectPath(Path path) {
    assert path != null;
    return ROOT.relativize(path).toString().replace('\\', '/');
  }

  private static String formatDuration(long nanos) {
    double seconds = nanos / 1_000_000_000.0;
    return String.format(Locale.ROOT, "%.3f s", seconds);
  }

  private static String json(String value) {
    assert value != null;
    var escaped = new StringBuilder(value.length() + 2);
    escaped.append('"');
    for (int index = 0; index < value.length(); index++) {
      char c = value.charAt(index);
      switch (c) {
        case '"' -> escaped.append("\\\"");
        case '\\' -> escaped.append("\\\\");
        case '\b' -> escaped.append("\\b");
        case '\f' -> escaped.append("\\f");
        case '\n' -> escaped.append("\\n");
        case '\r' -> escaped.append("\\r");
        case '\t' -> escaped.append("\\t");
        default -> {
          if (c < 0x20) escaped.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
          else escaped.append(c);
        }
      }
    }
    return escaped.append('"').toString();
  }

  record Benchmark(String id, String purpose, List<String> commandTemplate) {
    Benchmark {
      if (!id.matches("[a-z0-9][a-z0-9-]{0,62}"))
        throw new IllegalArgumentException("invalid benchmark id: " + id);
      commandTemplate = List.copyOf(commandTemplate);
      if (purpose.isBlank()) throw new IllegalArgumentException("empty benchmark purpose: " + id);
      if (commandTemplate.isEmpty())
        throw new IllegalArgumentException("empty benchmark command: " + id);
    }

    List<String> command(Path benchmarkDirectory) {
      assert benchmarkDirectory != null;
      String resultFile = benchmarkDirectory.resolve("result.json").toString();
      return commandTemplate.stream()
          .map(argument -> argument.equals(RESULT_FILE_ARGUMENT) ? resultFile : argument)
          .toList();
    }

    String json(List<String> command, int exitCode, long elapsedNanos) {
      assert command != null;
      return """
      {
        "id": %s,
        "purpose": %s,
        "command": [%s],
        "exitCode": %d,
        "elapsedNanos": %d
      }
      """
          .formatted(
              Perf.json(id),
              Perf.json(purpose),
              command.stream()
                  .map(Perf::json)
                  .reduce((left, right) -> left + ", " + right)
                  .orElse(""),
              exitCode,
              elapsedNanos);
    }
  }

  record Result(Benchmark benchmark, int exitCode, long elapsedNanos) {}

  record Host(
      String runnerLabel,
      String imageOs,
      String osName,
      String osVersion,
      String osArch,
      String cpuModel,
      int cores,
      String javaVersion,
      String javaVendor,
      String nodeVersion,
      String hyperfineVersion,
      String jmhVersion,
      String gitSha) {
    String hostKey() {
      return String.join(
          "|", runnerLabel, imageOs, osName, osArch, cpuModel, Integer.toString(cores));
    }

    String json() {
      return """
      {
        "hostKey": %s,
        "runnerLabel": %s,
        "imageOs": %s,
        "osName": %s,
        "osVersion": %s,
        "osArch": %s,
        "cpuModel": %s,
        "cores": %d,
        "javaVersion": %s,
        "javaVendor": %s,
        "nodeVersion": %s,
        "hyperfineVersion": %s,
        "jmhVersion": %s,
        "gitSha": %s,
        "jvmName": %s
      }
      """
          .formatted(
              Perf.json(hostKey()),
              Perf.json(runnerLabel),
              Perf.json(imageOs),
              Perf.json(osName),
              Perf.json(osVersion),
              Perf.json(osArch),
              Perf.json(cpuModel),
              cores,
              Perf.json(javaVersion),
              Perf.json(javaVendor),
              Perf.json(nodeVersion),
              Perf.json(hyperfineVersion),
              Perf.json(jmhVersion),
              Perf.json(gitSha),
              Perf.json(ManagementFactory.getRuntimeMXBean().getVmName()));
    }
  }
}
