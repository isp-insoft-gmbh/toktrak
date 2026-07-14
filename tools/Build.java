import java.io.IOException;
import java.lang.module.ModuleFinder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

public final class Build {
  private static final Path ROOT = Path.of("").toAbsolutePath().normalize();
  private static final Path OUTPUT = ROOT.resolve("output");
  private static final Path MODULES = OUTPUT.resolve("modules");
  private static final Path APP_MODULE = MODULES.resolve("toktrak");
  private static final Path MAIN_DEPS = OUTPUT.resolve("deps/main");
  private static final Path TEST_DEPS = OUTPUT.resolve("deps/test");
  private static final Path RUNTIMES = OUTPUT.resolve("runtimes");
  private static final Path ARGFILES = OUTPUT.resolve("args");
  private static final boolean ANSI = System.console() != null && System.getenv("NO_COLOR") == null;
  private static final List<String> APP_JDK_MODULES = List.of("java.logging", "jdk.httpserver");
  private static final List<String> TEST_JDK_MODULES = List.of("java.logging", "java.net.http", "jdk.httpserver");
  private static final List<String> TEST_EXPORTS = List.of(
      "toktrak/toktrak.dev=toktrak.tests",
      "toktrak/toktrak.http=toktrak.tests",
      "toktrak/toktrak.log=toktrak.tests");

  private Build() {}

  public static void main(String[] args) throws Exception {
    if (args.length == 0) fail("command required: clean, check, verify, dev, or prod");
    deleteTree(ARGFILES);
    switch (args[0]) {
      case "clean" -> clean();
      case "check" -> compile();
      case "verify" -> test();
      case "dev" -> dev(List.of(args).subList(1, args.length));
      case "prod" -> jlinkProd();
      default -> fail("unknown command: " + args[0]);
    }
  }

  private static void clean() throws IOException {
    deleteTree(MODULES);
    deleteTree(MAIN_DEPS);
    deleteTree(TEST_DEPS);
    deleteTree(RUNTIMES);
    deleteTree(ARGFILES);
  }

  private static void deps() throws Exception {
    ensureDependency("sources/main-deps.txt", MAIN_DEPS, "resolve-toktrak-production-dependencies");
    ensureDependency("sources/test-deps.txt", TEST_DEPS, "resolve-toktrak-test-dependencies");
    verifyModules(MAIN_DEPS);
    verifyModules(TEST_DEPS);
  }

  private static void ensureDependency(String dependencyFile, Path output, String argFileName) throws Exception {
    long started = System.nanoTime();
    Path source = ROOT.resolve(dependencyFile);
    Path argFile = writeArgFile(
        argFileName,
        List.of(
            "-jar",
            ROOT.resolve("vendored/jresolve.jar").toString(),
            "--use-module-names",
            "--output-directory=" + output,
            "--dependency-file=" + source));
    String fingerprint = dependencyFingerprint(source);
    Path stamp = output.resolve(".fingerprint");
    if (Files.isDirectory(output) && Files.exists(stamp) && Files.readString(stamp).equals(fingerprint)) {
      printCached(javaExecutable(), argFile, started);
      return;
    }

    deleteTree(output);
    Files.createDirectories(output);
    runArgFile(javaExecutable(), argFile);
    Files.writeString(stamp, fingerprint, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
  }

  private static String dependencyFingerprint(Path dependencyFile) throws Exception {
    var digest = MessageDigest.getInstance("SHA-256");
    digest.update(Files.readAllBytes(dependencyFile));
    digest.update(Files.readAllBytes(ROOT.resolve("vendored/jresolve.jar")));
    return HexFormat.of().formatHex(digest.digest());
  }

  private static void compile() throws Exception {
    deps();
    deleteTree(MODULES);
    Files.createDirectories(MODULES);
    List<String> arguments = new ArrayList<>();
    arguments.add("-Xlint:all");
    arguments.add("-Werror");
    arguments.add("-g");
    addModuleSourcePaths(arguments);
    arguments.add("--module-path");
    arguments.add(modulePath(List.of(MAIN_DEPS, TEST_DEPS)));
    addExports(arguments);
    arguments.add("-d");
    arguments.add(MODULES.toString());
    arguments.add("--module");
    arguments.add("toktrak,toktrak.tests");
    runArgFile(javacExecutable(), "compile-toktrak-and-test-modules", arguments);
  }

  private static void test() throws Exception {
    compile();
    Path runtime = ensureTestRuntime();
    List<String> arguments = new ArrayList<>();
    arguments.add("-ea");
    arguments.add("--module-path");
    arguments.add(MODULES.toString());
    arguments.add("--add-modules");
    arguments.add("toktrak.tests,toktrak," + String.join(",", moduleNames(TEST_DEPS)));
    addExports(arguments);
    arguments.add("-m");
    arguments.add("toktrak.tests/toktrak.tests.TestLauncher");
    runArgFile(runtimeJava(runtime), "run-toktrak-test-suite", arguments);
  }

  private static void jlinkProd() throws Exception {
    compile();
    verifyModules(MAIN_DEPS);
    Path runtime = ensureRuntime("prod", List.of(MAIN_DEPS), List.of("toktrak"), true);
    runArgFile(
        runtimeJava(runtime),
        "smoke-test-toktrak-production-runtime",
        List.of("-ea", "-m", "toktrak/toktrak.Main", "--help"));
  }

  private static void dev(List<String> args) throws Exception {
    compile();
    Path runtime = ensureDevRuntime();
    List<String> arguments = new ArrayList<>();
    arguments.add("-ea");
    arguments.add("--module-path");
    arguments.add(MODULES.toString());
    arguments.add("-m");
    arguments.add("toktrak/toktrak.Main");
    arguments.addAll(args.stream().filter(arg -> !arg.equals("--")).toList());
    runArgFile(runtimeJava(runtime), "run-toktrak-development-server", arguments);
  }

  private static Path ensureDevRuntime() throws Exception {
    return ensureRuntime("dev", List.of(MAIN_DEPS), moduleNames(List.of(MAIN_DEPS), APP_JDK_MODULES), false);
  }

  private static Path ensureTestRuntime() throws Exception {
    var roots = new ArrayList<String>();
    roots.addAll(APP_JDK_MODULES);
    roots.addAll(TEST_JDK_MODULES);
    return ensureRuntime("test", List.of(MAIN_DEPS, TEST_DEPS), moduleNames(List.of(MAIN_DEPS, TEST_DEPS), roots), false);
  }

  private static Path ensureRuntime(String name, List<Path> dependencyDirectories, List<String> roots, boolean includeApp)
      throws Exception {
    long started = System.nanoTime();
    Path image = RUNTIMES.resolve(name);
    List<String> modulePath = new ArrayList<>();
    modulePath.add(Path.of(System.getProperty("java.home"), "jmods").toString());
    modulePath.addAll(jarPaths(dependencyDirectories).stream().map(Path::toString).toList());
    if (includeApp) modulePath.add(APP_MODULE.toString());
    Path argFile = writeArgFile(
        "link-toktrak-" + runtimeName(name) + "-runtime",
        List.of(
            "--module-path", String.join(java.io.File.pathSeparator, modulePath),
            "--add-modules", String.join(",", roots),
            "--output", image.toString(),
            "--strip-debug",
            "--no-header-files",
            "--no-man-pages"));
    String fingerprint = fingerprint(name, dependencyDirectories, roots, includeApp);
    Path stamp = image.resolve(".fingerprint");
    if (Files.isDirectory(image) && Files.exists(stamp) && Files.readString(stamp).equals(fingerprint)) {
      printCached(jlinkExecutable(), argFile, started);
      return image;
    }

    for (Path directory : dependencyDirectories) verifyModules(directory);
    deleteTree(image);
    Files.createDirectories(RUNTIMES);
    runArgFile(jlinkExecutable(), argFile);
    Files.writeString(stamp, fingerprint, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    return image;
  }

  private static String fingerprint(String name, List<Path> dependencyDirectories, List<String> roots, boolean includeApp)
      throws Exception {
    var digest = MessageDigest.getInstance("SHA-256");
    update(digest, "runtime\n" + name + "\n" + System.getProperty("java.runtime.version") + "\n");
    update(digest, String.join("\n", roots) + "\n" + includeApp + "\n");
    for (Path jar : jarPaths(dependencyDirectories)) {
      update(digest, jar.getFileName().toString() + "\n");
      digest.update(Files.readAllBytes(jar));
    }
    if (includeApp) updateTree(digest, APP_MODULE, ".class");
    return HexFormat.of().formatHex(digest.digest());
  }

  private static void updateTree(MessageDigest digest, Path directory, String suffix) throws IOException {
    try (Stream<Path> paths = Files.walk(directory)) {
      for (Path path : paths.filter(path -> path.toString().endsWith(suffix)).sorted().toList()) {
        update(digest, directory.relativize(path).toString() + "\n");
        digest.update(Files.readAllBytes(path));
      }
    }
  }

  private static void update(MessageDigest digest, String value) {
    digest.update(value.getBytes(StandardCharsets.UTF_8));
  }

  private static void addExports(List<String> command) {
    for (String export : TEST_EXPORTS) {
      command.add("--add-exports");
      command.add(export);
    }
  }

  private static void verifyModules(Path directory) throws IOException {
    if (!Files.isDirectory(directory)) fail("dependency directory missing: " + directory);
    for (Path jar : jarPaths(List.of(directory))) moduleName(jar);
  }

  private static List<String> moduleNames(List<Path> directories, List<String> additional) throws IOException {
    var names = new ArrayList<String>();
    for (Path directory : directories) names.addAll(moduleNames(directory));
    names.addAll(additional);
    return names.stream().distinct().sorted().toList();
  }

  private static List<String> moduleNames(Path directory) throws IOException {
    return jarPaths(List.of(directory)).stream().map(Build::moduleName).toList();
  }

  private static String moduleName(Path jar) {
    var modules = ModuleFinder.of(jar).findAll();
    if (modules.size() != 1) fail("module descriptor missing: " + jar);
    var descriptor = modules.iterator().next().descriptor();
    if (descriptor.isAutomatic()) fail("automatic module rejected: " + jar);
    return descriptor.name();
  }

  private static List<Path> jarPaths(List<Path> directories) throws IOException {
    var result = new ArrayList<Path>();
    for (Path directory : directories) {
      try (Stream<Path> paths = Files.list(directory)) {
        result.addAll(paths.filter(Build::isJar).sorted().toList());
      }
    }
    return result;
  }

  private static void addModuleSourcePaths(List<String> command) {
    command.add("--module-source-path");
    command.add("toktrak=" + ROOT.resolve("sources/toktrak"));
    command.add("--module-source-path");
    command.add("toktrak.tests=" + ROOT.resolve("tests/toktrak.tests"));
  }

  private static String modulePath(List<Path> entries) throws IOException {
    var paths = new ArrayList<String>();
    for (Path entry : entries) {
      if (Files.isDirectory(entry) && !entry.equals(MODULES)) {
        paths.addAll(jarPaths(List.of(entry)).stream().map(Path::toString).toList());
      } else if (Files.isDirectory(entry)) {
        paths.add(entry.toString());
      } else {
        paths.add(entry.toString());
      }
    }
    return String.join(java.io.File.pathSeparator, paths);
  }

  private static boolean isJar(Path path) {
    return path.getFileName().toString().endsWith(".jar");
  }

  private static void deleteTree(Path path) throws IOException {
    if (!Files.exists(path)) return;
    try (Stream<Path> paths = Files.walk(path)) {
      for (Path child : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(child);
    }
  }

  private static String runtimeName(String name) {
    return switch (name) {
      case "dev" -> "development";
      case "test" -> "test";
      case "prod" -> "production";
      default -> throw new IllegalArgumentException("unknown runtime: " + name);
    };
  }

  private static void runArgFile(String executable, String name, List<String> arguments) throws Exception {
    runArgFile(executable, writeArgFile(name, arguments));
  }

  private static Path writeArgFile(String name, List<String> arguments) throws IOException {
    Files.createDirectories(ARGFILES);
    Path argFile = ARGFILES.resolve(name + ".args");
    String content = arguments.stream().map(Build::quoteArg).reduce((left, right) -> left + "\n" + right).orElse("") + "\n";
    Files.writeString(argFile, content, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    return argFile;
  }

  private static void runArgFile(String executable, Path argFile) throws Exception {
    printInvocation(executable, argFile);
    long started = System.nanoTime();
    Process process = new ProcessBuilder(executable, "@" + argFile).inheritIO().start();
    int code = process.waitFor();
    String cpu = process.info().totalCpuDuration().map(duration -> formatDuration(duration.toNanos())).orElse(null);
    printCompletion(code == 0 ? "done" : "failed", System.nanoTime() - started, cpu);
    if (code != 0) fail("command failed with exit code " + code);
  }

  private static void printCached(String executable, Path argFile, long started) {
    printInvocation(executable, argFile);
    printCompletion("cached", System.nanoTime() - started, null);
  }

  private static void printInvocation(String executable, Path argFile) {
    String tool = Path.of(executable).getFileName().toString().replaceFirst("(?i)\\.exe$", "");
    String directory = "@" + ROOT.relativize(argFile.getParent()).toString().replace('\\', '/') + "/";
    System.out.println(emphasize(tool) + "  " + dim(directory) + emphasize(argFile.getFileName().toString()));
  }

  private static void printCompletion(String state, long elapsedNanos, String cpu) {
    String statePadded = String.format(Locale.ROOT, "%-7s", state);
    String styledState = state.equals("failed") ? emphasize(statePadded) : dim(statePadded);
    String cpuSuffix = cpu == null ? "" : dim("  cpu ") + emphasize(cpu);
    System.out.println("       " + styledState + " " + emphasize(formatDuration(elapsedNanos)) + cpuSuffix);
  }

  private static String formatDuration(long nanoseconds) {
    long milliseconds = Math.max(0, nanoseconds / 1_000_000);
    if (milliseconds < 1_000) return milliseconds + " ms";
    double seconds = nanoseconds / 1_000_000_000.0;
    if (seconds < 60) return String.format(Locale.ROOT, "%.2f s", seconds);
    long minutes = (long) seconds / 60;
    return String.format(Locale.ROOT, "%d min %.1f s", minutes, seconds - minutes * 60);
  }

  private static String emphasize(String value) {
    return ANSI ? "\033[1m" + value + "\033[0m" : value;
  }

  private static String dim(String value) {
    return ANSI ? "\033[2m" + value + "\033[0m" : value;
  }

  private static String quoteArg(String argument) {
    return "\"" + argument.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
  }

  private static String runtimeJava(Path runtime) {
    return runtime.resolve("bin").resolve(isWindows() ? "java.exe" : "java").toString();
  }

  private static String javaExecutable() {
    return Path.of(System.getProperty("java.home"), "bin", isWindows() ? "java.exe" : "java").toString();
  }

  private static String javacExecutable() {
    return Path.of(System.getProperty("java.home"), "bin", isWindows() ? "javac.exe" : "javac").toString();
  }

  private static String jlinkExecutable() {
    return Path.of(System.getProperty("java.home"), "bin", isWindows() ? "jlink.exe" : "jlink").toString();
  }

  private static boolean isWindows() {
    return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
  }

  private static void fail(String message) {
    throw new IllegalStateException(message);
  }
}
