import java.io.IOException;
import java.lang.module.ModuleFinder;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
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
  private static final Path BUILD_TESTS = OUTPUT.resolve("build-tests");
  private static final long FILE_BYTES_MAX = 512L * 1024 * 1024;
  private static final int TREE_ENTRIES_MAX = 100_000;
  private static final int COLLECTION_ENTRIES_MAX = 10_000;
  private static final int ARGUMENTS_MAX = 10_000;
  private static final int ARGUMENT_BYTES_MAX = 32 * 1024;
  private static final int ARGFILE_BYTES_MAX = 8 * 1024 * 1024;
  private static final int COPY_BUFFER_BYTES = 64 * 1024;
  private static final int STAMP_BYTES_MAX = 128;
  private static final Duration PROCESS_TIMEOUT = Duration.ofMinutes(10);
  private static final Duration PROCESS_KILL_TIMEOUT = Duration.ofSeconds(5);
  private static final boolean ANSI = System.console() != null && System.getenv("NO_COLOR") == null;
  private static final List<String> APP_JDK_MODULES = List.of("java.logging", "jdk.httpserver");
  private static final List<String> TEST_JDK_MODULES =
      List.of("java.logging", "java.net.http", "jdk.httpserver");
  private static final List<String> TEST_EXPORTS =
      List.of(
          "toktrak/toktrak.dev=toktrak.tests",
          "toktrak/toktrak.http=toktrak.tests",
          "toktrak/toktrak.log=toktrak.tests");

  private Build() {}

  public static void main(String[] args) throws Exception {
    requireAssertions();
    Objects.requireNonNull(args, "args");
    if (args.length == 0) fail("command required: clean, check, verify, dev, or prod");
    if (args.length > 256) fail("command arguments exceed 256 entries");
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
    deleteTree(BUILD_TESTS);
  }

  private static void deps() throws Exception {
    ensureDependency("sources/main-deps.txt", MAIN_DEPS, "resolve-toktrak-production-dependencies");
    ensureDependency("sources/test-deps.txt", TEST_DEPS, "resolve-toktrak-test-dependencies");
    verifyModules(MAIN_DEPS);
    verifyModules(TEST_DEPS);
  }

  private static void ensureDependency(String dependencyFile, Path output, String argFileName)
      throws Exception {
    long started = System.nanoTime();
    Path source = ROOT.resolve(dependencyFile);
    Path argFile =
        writeArgFile(
            argFileName,
            List.of(
                "-ea",
                "-jar",
                ROOT.resolve("vendored/jresolve.jar").toString(),
                "--use-module-names",
                "--output-directory=" + output,
                "--dependency-file=" + source));
    String fingerprint = dependencyFingerprint(source);
    Path stamp = output.resolve(".fingerprint");
    if (Files.isDirectory(output) && Files.exists(stamp) && readStamp(stamp).equals(fingerprint)) {
      printCached(javaExecutable(), argFile, started);
      return;
    }

    deleteTree(output);
    Files.createDirectories(output);
    runArgFile(javaExecutable(), argFile);
    Files.writeString(
        stamp, fingerprint, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
  }

  private static String dependencyFingerprint(Path dependencyFile) throws Exception {
    var digest = MessageDigest.getInstance("SHA-256");
    updateDigestFromFile(digest, dependencyFile);
    updateDigestFromFile(digest, ROOT.resolve("vendored/jresolve.jar"));
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
    testBuildTool();
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

  private static void testBuildTool() throws Exception {
    deleteTree(BUILD_TESTS);
    Files.createDirectories(BUILD_TESTS);
    runArgFile(
        javacExecutable(),
        "compile-build-tool-tests",
        List.of(
            "-Xlint:all",
            "-Werror",
            "-d",
            BUILD_TESTS.toString(),
            ROOT.resolve("tools/Build.java").toString(),
            ROOT.resolve("tests/tools/BuildTest.java").toString()));
    runArgFile(
        javaExecutable(),
        "run-build-tool-tests",
        List.of("-ea", "-cp", BUILD_TESTS.toString(), "BuildTest"));
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
    return ensureRuntime(
        "dev", List.of(MAIN_DEPS), moduleNames(List.of(MAIN_DEPS), APP_JDK_MODULES), false);
  }

  private static Path ensureTestRuntime() throws Exception {
    var roots = new ArrayList<String>();
    roots.addAll(APP_JDK_MODULES);
    roots.addAll(TEST_JDK_MODULES);
    return ensureRuntime(
        "test",
        List.of(MAIN_DEPS, TEST_DEPS),
        moduleNames(List.of(MAIN_DEPS, TEST_DEPS), roots),
        false);
  }

  private static Path ensureRuntime(
      String name, List<Path> dependencyDirectories, List<String> roots, boolean includeApp)
      throws Exception {
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(dependencyDirectories, "dependencyDirectories");
    Objects.requireNonNull(roots, "roots");
    requireCollectionSize(dependencyDirectories, "dependency directories");
    requireCollectionSize(roots, "runtime roots");
    long started = System.nanoTime();
    Path image = RUNTIMES.resolve(name);
    List<String> modulePath = new ArrayList<>();
    modulePath.add(Path.of(System.getProperty("java.home"), "jmods").toString());
    modulePath.addAll(jarPaths(dependencyDirectories).stream().map(Path::toString).toList());
    if (includeApp) modulePath.add(APP_MODULE.toString());
    Path argFile =
        writeArgFile(
            "link-toktrak-" + runtimeName(name) + "-runtime",
            List.of(
                "--module-path",
                String.join(java.io.File.pathSeparator, modulePath),
                "--add-modules",
                String.join(",", roots),
                "--output",
                image.toString(),
                "--strip-debug",
                "--no-header-files",
                "--no-man-pages"));
    String fingerprint = fingerprint(name, dependencyDirectories, roots, includeApp);
    Path stamp = image.resolve(".fingerprint");
    if (Files.isDirectory(image) && Files.exists(stamp) && readStamp(stamp).equals(fingerprint)) {
      printCached(jlinkExecutable(), argFile, started);
      return image;
    }

    for (Path directory : dependencyDirectories) verifyModules(directory);
    deleteTree(image);
    Files.createDirectories(RUNTIMES);
    runArgFile(jlinkExecutable(), argFile);
    Files.writeString(
        stamp, fingerprint, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    return image;
  }

  private static String fingerprint(
      String name, List<Path> dependencyDirectories, List<String> roots, boolean includeApp)
      throws Exception {
    var digest = MessageDigest.getInstance("SHA-256");
    update(digest, "runtime\n" + name + "\n" + System.getProperty("java.runtime.version") + "\n");
    update(digest, String.join("\n", roots) + "\n" + includeApp + "\n");
    for (Path jar : jarPaths(dependencyDirectories)) {
      update(digest, jar.getFileName().toString() + "\n");
      updateDigestFromFile(digest, jar);
    }
    if (includeApp) updateTree(digest, APP_MODULE, ".class");
    return HexFormat.of().formatHex(digest.digest());
  }

  private static void updateTree(MessageDigest digest, Path directory, String suffix)
      throws IOException {
    assert digest != null;
    assert directory != null;
    assert suffix != null;
    for (Path path :
        treePaths(directory, TREE_ENTRIES_MAX).stream()
            .filter(candidate -> candidate.toString().endsWith(suffix))
            .sorted()
            .toList()) {
      update(digest, directory.relativize(path).toString() + "\n");
      updateDigestFromFile(digest, path);
    }
  }

  private static void update(MessageDigest digest, String value) {
    assert digest != null;
    assert value != null;
    digest.update(value.getBytes(StandardCharsets.UTF_8));
  }

  private static void addExports(List<String> command) {
    Objects.requireNonNull(command, "command");
    requireCollectionSize(TEST_EXPORTS, "test exports");
    for (String export : TEST_EXPORTS) {
      command.add("--add-exports");
      command.add(export);
    }
  }

  private static void verifyModules(Path directory) throws IOException {
    if (!Files.isDirectory(directory)) fail("dependency directory missing: " + directory);
    for (Path jar : jarPaths(List.of(directory))) moduleName(jar);
  }

  private static List<String> moduleNames(List<Path> directories, List<String> additional)
      throws IOException {
    Objects.requireNonNull(additional, "additional");
    requireCollectionSize(additional, "additional modules");
    var names = new ArrayList<String>();
    for (Path jar : jarPaths(directories)) {
      if (names.size() >= COLLECTION_ENTRIES_MAX) {
        fail("module names exceed " + COLLECTION_ENTRIES_MAX + " entries");
      }
      names.add(moduleName(jar));
    }
    if (additional.size() > COLLECTION_ENTRIES_MAX - names.size()) {
      fail("module names exceed " + COLLECTION_ENTRIES_MAX + " entries");
    }
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
    Objects.requireNonNull(directories, "directories");
    if (directories.size() > TREE_ENTRIES_MAX)
      fail("directories exceed " + TREE_ENTRIES_MAX + " entries");
    var result = new ArrayList<Path>();
    for (Path directory : directories) {
      for (Path path : directoryEntries(directory, TREE_ENTRIES_MAX)) {
        if (!isJar(path)) continue;
        if (result.size() >= TREE_ENTRIES_MAX)
          fail("JAR paths exceed " + TREE_ENTRIES_MAX + " entries");
        result.add(path);
      }
    }
    result.sort(Comparator.naturalOrder());
    return List.copyOf(result);
  }

  private static void addModuleSourcePaths(List<String> command) {
    command.add("--module-source-path");
    command.add("toktrak=" + ROOT.resolve("sources/toktrak"));
    command.add("--module-source-path");
    command.add("toktrak.tests=" + ROOT.resolve("tests/toktrak.tests"));
  }

  private static String modulePath(List<Path> entries) throws IOException {
    Objects.requireNonNull(entries, "entries");
    requireCollectionSize(entries, "module path entries");
    var paths = new ArrayList<String>();
    for (Path entry : entries) {
      if (Files.isDirectory(entry) && !entry.equals(MODULES)) {
        List<String> jars = jarPaths(List.of(entry)).stream().map(Path::toString).toList();
        if (jars.size() > COLLECTION_ENTRIES_MAX - paths.size()) {
          fail("module path exceeds " + COLLECTION_ENTRIES_MAX + " entries");
        }
        paths.addAll(jars);
      } else if (Files.isDirectory(entry)) {
        paths.add(entry.toString());
      } else {
        paths.add(entry.toString());
      }
      if (paths.size() > COLLECTION_ENTRIES_MAX) {
        fail("module path exceeds " + COLLECTION_ENTRIES_MAX + " entries");
      }
    }
    return String.join(java.io.File.pathSeparator, paths);
  }

  private static void requireCollectionSize(List<?> values, String name) {
    Objects.requireNonNull(values, name);
    assert name != null && !name.isBlank();
    if (values.size() > COLLECTION_ENTRIES_MAX) {
      fail(name + " exceed " + COLLECTION_ENTRIES_MAX + " entries");
    }
  }

  private static boolean isJar(Path path) {
    return path.getFileName().toString().endsWith(".jar");
  }

  private static void deleteTree(Path path) throws IOException {
    Objects.requireNonNull(path, "path");
    if (!Files.exists(path)) return;
    var paths = new ArrayList<>(treePaths(path, TREE_ENTRIES_MAX));
    paths.sort(Comparator.reverseOrder());
    for (Path child : paths) Files.delete(child);
  }

  private static String runtimeName(String name) {
    return switch (name) {
      case "dev" -> "development";
      case "test" -> "test";
      case "prod" -> "production";
      default -> throw new IllegalArgumentException("unknown runtime: " + name);
    };
  }

  private static void runArgFile(String executable, String name, List<String> arguments)
      throws Exception {
    runArgFile(executable, writeArgFile(name, arguments));
  }

  private static Path writeArgFile(String name, List<String> arguments) throws IOException {
    Objects.requireNonNull(name, "name");
    Files.createDirectories(ARGFILES);
    Path argFile = ARGFILES.resolve(name + ".args");
    if (!argFile.normalize().startsWith(ARGFILES)) fail("argument file escapes output directory");
    String content = argumentFileContent(arguments);
    Files.writeString(
        argFile, content, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    assert Files.size(argFile) <= ARGFILE_BYTES_MAX;
    return argFile;
  }

  private static void runArgFile(String executable, Path argFile) throws Exception {
    Objects.requireNonNull(executable, "executable");
    Objects.requireNonNull(argFile, "argFile");
    printInvocation(executable, argFile);
    long started = System.nanoTime();
    Process process = new ProcessBuilder(executable, "@" + argFile).inheritIO().start();
    int code;
    try {
      code = waitForProcess(process, PROCESS_TIMEOUT, PROCESS_KILL_TIMEOUT);
    } catch (InterruptedException ex) {
      terminate(process, PROCESS_KILL_TIMEOUT);
      Thread.currentThread().interrupt();
      throw ex;
    }
    String cpu =
        process
            .info()
            .totalCpuDuration()
            .map(duration -> formatDuration(duration.toNanos()))
            .orElse(null);
    printCompletion(code == 0 ? "done" : "failed", System.nanoTime() - started, cpu);
    if (code != 0) fail("command failed with exit code " + code);
  }

  static String readStampForTest(Path path) throws IOException {
    return readStamp(path);
  }

  private static String readStamp(Path path) throws IOException {
    Objects.requireNonNull(path, "path");
    byte[] bytes;
    try (var input = Files.newInputStream(path)) {
      bytes = input.readNBytes(STAMP_BYTES_MAX + 1);
      if (bytes.length > STAMP_BYTES_MAX || input.read() >= 0) {
        fail("stamp exceeds " + STAMP_BYTES_MAX + " UTF-8 bytes");
      }
    }
    try {
      return StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(bytes))
          .toString();
    } catch (CharacterCodingException exception) {
      throw new IllegalStateException("stamp is not valid UTF-8: " + path, exception);
    }
  }

  static void updateDigestFromFileForTest(MessageDigest digest, Path path) throws IOException {
    updateDigestFromFile(digest, path);
  }

  private static void updateDigestFromFile(MessageDigest digest, Path path) throws IOException {
    Objects.requireNonNull(digest, "digest");
    Objects.requireNonNull(path, "path");
    long fileBytes = Files.size(path);
    if (fileBytes > FILE_BYTES_MAX) fail("file exceeds " + FILE_BYTES_MAX + " bytes: " + path);
    byte[] buffer = new byte[COPY_BUFFER_BYTES];
    long fileBytesRead = 0;
    long readOperations = 0;
    long readOperationsMax = Math.addExact(fileBytes, 1);
    try (var input = Files.newInputStream(path)) {
      while (fileBytesRead < fileBytes && readOperations < readOperationsMax) {
        int requestedBytes = (int) Math.min(buffer.length, fileBytes - fileBytesRead);
        int readBytes = input.read(buffer, 0, requestedBytes);
        readOperations = Math.addExact(readOperations, 1);
        if (readBytes <= 0) fail("file changed while hashing: " + path);
        digest.update(buffer, 0, readBytes);
        fileBytesRead = Math.addExact(fileBytesRead, readBytes);
      }
    }
    if (fileBytesRead != fileBytes || Files.size(path) != fileBytes) {
      fail("file changed while hashing: " + path);
    }
    assert fileBytesRead <= FILE_BYTES_MAX;
  }

  static List<Path> treePathsForTest(Path root, int entriesMax) throws IOException {
    return treePaths(root, entriesMax);
  }

  private static List<Path> treePaths(Path root, int entriesMax) throws IOException {
    Objects.requireNonNull(root, "root");
    if (entriesMax <= 0 || entriesMax > TREE_ENTRIES_MAX) {
      throw new IllegalArgumentException("entriesMax must be 1.." + TREE_ENTRIES_MAX);
    }
    var result = new ArrayList<Path>();
    try (Stream<Path> paths = Files.walk(root)) {
      Iterator<Path> iterator = paths.iterator();
      while (iterator.hasNext()) {
        if (result.size() >= entriesMax) fail("tree exceeds " + entriesMax + " entries: " + root);
        result.add(iterator.next());
      }
    }
    assert result.size() <= entriesMax;
    return List.copyOf(result);
  }

  private static List<Path> directoryEntries(Path directory, int entriesMax) throws IOException {
    Objects.requireNonNull(directory, "directory");
    assert entriesMax > 0 && entriesMax <= TREE_ENTRIES_MAX;
    var result = new ArrayList<Path>();
    try (Stream<Path> paths = Files.list(directory)) {
      Iterator<Path> iterator = paths.iterator();
      while (iterator.hasNext()) {
        if (result.size() >= entriesMax) {
          fail("directory exceeds " + entriesMax + " entries: " + directory);
        }
        result.add(iterator.next());
      }
    }
    assert result.size() <= entriesMax;
    return List.copyOf(result);
  }

  static String argumentFileContentForTest(List<String> arguments) {
    return argumentFileContent(arguments);
  }

  private static String argumentFileContent(List<String> arguments) {
    Objects.requireNonNull(arguments, "arguments");
    if (arguments.size() > ARGUMENTS_MAX) fail("arguments exceed " + ARGUMENTS_MAX + " entries");
    var content =
        new StringBuilder(Math.min(ARGFILE_BYTES_MAX, Math.multiplyExact(arguments.size(), 32)));
    int contentBytes = 0;
    for (String argument : arguments) {
      Objects.requireNonNull(argument, "argument");
      int argumentBytes = argument.getBytes(StandardCharsets.UTF_8).length;
      if (argumentBytes > ARGUMENT_BYTES_MAX) {
        fail("argument exceeds " + ARGUMENT_BYTES_MAX + " UTF-8 bytes");
      }
      String line = quoteArg(argument) + "\n";
      contentBytes = Math.addExact(contentBytes, line.getBytes(StandardCharsets.UTF_8).length);
      if (contentBytes > ARGFILE_BYTES_MAX) {
        fail("argument file exceeds " + ARGFILE_BYTES_MAX + " UTF-8 bytes");
      }
      content.append(line);
    }
    assert content.toString().getBytes(StandardCharsets.UTF_8).length == contentBytes;
    return content.toString();
  }

  static int waitForProcessForTest(Process process, Duration timeout, Duration killTimeout)
      throws InterruptedException {
    return waitForProcess(process, timeout, killTimeout);
  }

  private static int waitForProcess(Process process, Duration timeout, Duration killTimeout)
      throws InterruptedException {
    Objects.requireNonNull(process, "process");
    requirePositiveDuration(timeout, "timeout");
    requirePositiveDuration(killTimeout, "killTimeout");
    if (process.waitFor(timeout.toNanos(), TimeUnit.NANOSECONDS)) return process.exitValue();
    terminate(process, killTimeout);
    fail("process timed out after " + timeout);
    throw new AssertionError("unreachable");
  }

  private static void terminate(Process process, Duration killTimeout) throws InterruptedException {
    assert process != null;
    assert killTimeout != null && !killTimeout.isNegative() && !killTimeout.isZero();
    process.destroy();
    if (process.waitFor(killTimeout.toNanos(), TimeUnit.NANOSECONDS)) return;
    process.destroyForcibly();
    if (!process.waitFor(killTimeout.toNanos(), TimeUnit.NANOSECONDS)) {
      fail("process did not terminate");
    }
    assert !process.isAlive();
  }

  private static void requirePositiveDuration(Duration duration, String name) {
    Objects.requireNonNull(duration, name);
    if (duration.isNegative() || duration.isZero() || duration.compareTo(PROCESS_TIMEOUT) > 0) {
      throw new IllegalArgumentException(name + " must be positive and at most " + PROCESS_TIMEOUT);
    }
  }

  private static void requireAssertions() {
    boolean enabled = false;
    assert enabled = true;
    if (!enabled) throw new IllegalStateException("Java assertions must be enabled with -ea");
  }

  private static void printCached(String executable, Path argFile, long started) {
    printInvocation(executable, argFile);
    printCompletion("cached", System.nanoTime() - started, null);
  }

  private static void printInvocation(String executable, Path argFile) {
    String tool = Path.of(executable).getFileName().toString().replaceFirst("(?i)\\.exe$", "");
    String directory =
        "@" + ROOT.relativize(argFile.getParent()).toString().replace('\\', '/') + "/";
    System.out.println(
        emphasize(tool) + "  " + dim(directory) + emphasize(argFile.getFileName().toString()));
  }

  private static void printCompletion(String state, long elapsedNanos, String cpu) {
    String statePadded = String.format(Locale.ROOT, "%-7s", state);
    String styledState = state.equals("failed") ? emphasize(statePadded) : dim(statePadded);
    String cpuSuffix = cpu == null ? "" : dim("  cpu ") + emphasize(cpu);
    System.out.println(
        "       " + styledState + " " + emphasize(formatDuration(elapsedNanos)) + cpuSuffix);
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
    return Path.of(System.getProperty("java.home"), "bin", isWindows() ? "java.exe" : "java")
        .toString();
  }

  private static String javacExecutable() {
    return Path.of(System.getProperty("java.home"), "bin", isWindows() ? "javac.exe" : "javac")
        .toString();
  }

  private static String jlinkExecutable() {
    return Path.of(System.getProperty("java.home"), "bin", isWindows() ? "jlink.exe" : "jlink")
        .toString();
  }

  private static boolean isWindows() {
    return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
  }

  private static void fail(String message) {
    throw new IllegalStateException(message);
  }
}
