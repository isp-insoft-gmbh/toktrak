import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

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
  private static final long BENCHMARK_RESULT_BYTES_MAX = 1024L * 1024;
  private static final long GITHUB_SUMMARY_BYTES_MAX = 1024L * 1024;
  private static final String JSON_NUMBER_PATTERN =
      "-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?";
  private static final Pattern JMH_PRIMARY_SCORE_PATTERN =
      Pattern.compile(
          "\\\"primaryMetric\\\"\\s*:\\s*\\{\\s*"
              + "\\\"score\\\"\\s*:\\s*("
              + JSON_NUMBER_PATTERN
              + ")\\s*,\\s*"
              + "\\\"scoreError\\\"\\s*:\\s*("
              + JSON_NUMBER_PATTERN
              + ")\\s*,");
  private static final Pattern JMH_SCORE_UNIT_PATTERN =
      Pattern.compile("\\\"scoreUnit\\\"\\s*:\\s*\\\"([A-Za-z]+/[A-Za-z]+)\\\"");
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
    Path runDirectory =
        OUTPUT.resolve(
            RUN_DIRECTORY_FORMAT.format(Instant.now()) + "-" + ProcessHandle.current().pid());
    Files.createDirectories(OUTPUT);
    Files.createDirectory(runDirectory);
    Host host = host();
    Files.writeString(runDirectory.resolve("host.json"), host.json(), StandardCharsets.UTF_8);
    List<Result> results = new ArrayList<>();
    for (Benchmark benchmark : selected) {
      results.add(runBenchmark(runDirectory, benchmark));
    }
    String summary = summary(runDirectory, host, results);
    Files.writeString(runDirectory.resolve("summary.md"), summary, StandardCharsets.UTF_8);
    appendGitHubSummary(summary);
    System.out.print(summary);
    if (results.stream().anyMatch(result -> result.exitCode() != 0)) {
      throw new IllegalStateException("one or more performance benchmarks failed");
    }
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
            ResultFormat.JMH_JSON,
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
    Optional<Measurement> measurement =
        exitCode == 0
            ? Optional.of(
                measurement(benchmark.resultFormat(), benchmarkDirectory.resolve("result.json")))
            : Optional.empty();
    return new Result(benchmark, exitCode, elapsedNanos, measurement);
  }

  private static Measurement measurement(ResultFormat format, Path resultFile) throws IOException {
    assert format != null;
    assert resultFile != null;
    return switch (format) {
      case JMH_JSON -> jmhMeasurement(resultFile);
    };
  }

  private static Measurement jmhMeasurement(Path resultFile) throws IOException {
    if (!Files.isRegularFile(resultFile)) {
      throw new IllegalStateException("JMH result is missing: " + projectPath(resultFile));
    }
    long resultBytes = Files.size(resultFile);
    if (resultBytes <= 0 || resultBytes > BENCHMARK_RESULT_BYTES_MAX) {
      throw new IllegalStateException(
          "JMH result must be 1.."
              + BENCHMARK_RESULT_BYTES_MAX
              + " bytes: "
              + projectPath(resultFile));
    }
    String json = Files.readString(resultFile, StandardCharsets.UTF_8);
    var scoreMatcher = JMH_PRIMARY_SCORE_PATTERN.matcher(json);
    if (!scoreMatcher.find()) {
      throw new IllegalStateException(
          "JMH result has no primary score: " + projectPath(resultFile));
    }
    int scoreEnd = scoreMatcher.end();
    double score = finiteNonnegative(scoreMatcher.group(1), "score", resultFile);
    double error = finiteNonnegative(scoreMatcher.group(2), "score error", resultFile);
    if (scoreMatcher.find()) {
      throw new IllegalStateException(
          "JMH result has multiple primary scores: " + projectPath(resultFile));
    }
    var unitMatcher = JMH_SCORE_UNIT_PATTERN.matcher(json);
    unitMatcher.region(scoreEnd, json.length());
    int secondaryMetrics = json.indexOf("\"secondaryMetrics\"", scoreEnd);
    if (!unitMatcher.find() || secondaryMetrics < 0 || unitMatcher.start() > secondaryMetrics) {
      throw new IllegalStateException(
          "JMH result has no primary score unit: " + projectPath(resultFile));
    }
    return new Measurement(score, error, unitMatcher.group(1));
  }

  private static double finiteNonnegative(String value, String description, Path resultFile) {
    assert value != null;
    assert description != null && !description.isBlank();
    assert resultFile != null;
    double parsed = Double.parseDouble(value);
    if (!Double.isFinite(parsed) || parsed < 0) {
      throw new IllegalStateException(
          "JMH " + description + " is invalid: " + projectPath(resultFile));
    }
    return parsed;
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
    markdown.append("| Benchmark | Purpose | JMH score ± 99.9% error | Exit | Elapsed |\n");
    markdown.append("| --- | --- | ---: | ---: | ---: |\n");
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
                    .append(result.measurement().map(Measurement::display).orElse("unavailable"))
                    .append(" | ")
                    .append(result.exitCode())
                    .append(" | ")
                    .append(formatDuration(result.elapsedNanos()))
                    .append(" |\n"));
    return markdown.toString();
  }

  private static void appendGitHubSummary(String markdown) throws IOException {
    assert markdown != null && !markdown.isBlank();
    String configured = System.getenv("GITHUB_STEP_SUMMARY");
    if (configured == null) return;
    Path summary = Path.of(configured).toAbsolutePath().normalize();
    if (Files.isSymbolicLink(summary) || !Files.isRegularFile(summary)) {
      throw new IllegalStateException("GITHUB_STEP_SUMMARY is not a regular file: " + summary);
    }
    long resultingBytes =
        Math.addExact(Files.size(summary), markdown.getBytes(StandardCharsets.UTF_8).length);
    if (resultingBytes > GITHUB_SUMMARY_BYTES_MAX) {
      throw new IllegalStateException(
          "GITHUB_STEP_SUMMARY would exceed " + GITHUB_SUMMARY_BYTES_MAX + " bytes: " + summary);
    }
    Files.writeString(summary, markdown, StandardCharsets.UTF_8, StandardOpenOption.APPEND);
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

  record Benchmark(
      String id, String purpose, ResultFormat resultFormat, List<String> commandTemplate) {
    Benchmark {
      if (!id.matches("[a-z0-9][a-z0-9-]{0,62}"))
        throw new IllegalArgumentException("invalid benchmark id: " + id);
      commandTemplate = List.copyOf(commandTemplate);
      if (purpose.isBlank()) throw new IllegalArgumentException("empty benchmark purpose: " + id);
      if (resultFormat == null)
        throw new IllegalArgumentException("empty benchmark result format: " + id);
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

  enum ResultFormat {
    JMH_JSON
  }

  record Measurement(double score, double error, String unit) {
    Measurement {
      if (!Double.isFinite(score) || score < 0) {
        throw new IllegalArgumentException("measurement score is invalid");
      }
      if (!Double.isFinite(error) || error < 0) {
        throw new IllegalArgumentException("measurement error is invalid");
      }
      if (unit == null || !unit.matches("[A-Za-z]+/[A-Za-z]+")) {
        throw new IllegalArgumentException("measurement unit is invalid");
      }
    }

    String display() {
      return String.format(Locale.ROOT, "%.3f ± %.3f %s", score, error, unit);
    }
  }

  record Result(
      Benchmark benchmark, int exitCode, long elapsedNanos, Optional<Measurement> measurement) {
    Result {
      assert benchmark != null;
      assert elapsedNanos >= 0;
      assert measurement != null;
      assert (exitCode == 0) == measurement.isPresent();
    }
  }

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
