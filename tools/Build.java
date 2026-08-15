package tools;

import com.sun.source.tree.AnnotationTree;
import com.sun.source.tree.AssignmentTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreeScanner;
import java.io.ByteArrayInputStream;
import java.io.Console;
import java.io.IOException;
import java.lang.module.ModuleFinder;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.jar.JarFile;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import javax.tools.ToolProvider;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;

public final class Build {
  private static final Path ROOT = Path.of("").toAbsolutePath().normalize();
  private static final Path OUTPUT = ROOT.resolve("output");
  private static final Path BUILD_LOCK = OUTPUT.resolve(".build.lock");
  private static final Path DEV_DATA_DIRECTORY = OUTPUT.resolve("toktrak-dev/data");
  private static final Path MODULES = OUTPUT.resolve("modules");
  private static final Path MODULE_CLASSES = MODULES.resolve("target/classes");
  private static final Path GENERATED_SOURCES =
      MODULES.resolve("target/generated-sources/annotations");
  private static final Path TEMPLATE_INPUT = MODULES.resolve("target/template-input");
  private static final Path APP_MODULE = MODULE_CLASSES.resolve("toktrak");
  private static final Path TEST_MODULE = MODULE_CLASSES.resolve("toktrak.tests");
  private static final Path MAIN_DEPS = OUTPUT.resolve("deps/main");
  private static final Path TEST_DEPS = OUTPUT.resolve("deps/test");
  private static final Path SNAPSHOT_DEPS = OUTPUT.resolve("deps/snapshot");
  private static final Path BUILD_DEPS = OUTPUT.resolve("deps/build");
  private static final Path REFASTER_DEPS = OUTPUT.resolve("deps/refaster");
  private static final Path PIT_DEPS = OUTPUT.resolve("deps/pit");
  private static final Path COVERAGE_DEPS = OUTPUT.resolve("deps/coverage");
  private static final Path COVERAGE = OUTPUT.resolve("coverage");
  private static final Path MUTATIONS = OUTPUT.resolve("mutations");
  private static final Path PIT_HISTORY = OUTPUT.resolve("pit.history");
  private static final Path RUNTIMES = OUTPUT.resolve("runtimes");
  private static final Path IDE = OUTPUT.resolve("ide");
  private static final Path CONTAINERFILE = ROOT.resolve("Containerfile");
  private static final Path CHANGELOG = ROOT.resolve("CHANGELOG.md");
  private static final Path CONTAINER_VERIFY = OUTPUT.resolve("container-verify");
  private static final Path ECLIPSE_IDE = IDE.resolve("eclipse");
  private static final Path INTELLIJ_METADATA = IDE.resolve("intellij");
  private static final Path INTELLIJ_IDEA = ROOT.resolve(".idea");
  private static final Path ARGFILES = OUTPUT.resolve("args");
  private static final Path BUILD_TESTS = OUTPUT.resolve("build-tests");
  private static final Path BUILD_TEST_CLASSES = BUILD_TESTS.resolve("classes");
  private static final Path BUILD_TEST_RESULTS = BUILD_TESTS.resolve("results");
  private static final Path REFASTER_OUTPUT = OUTPUT.resolve("refaster");
  private static final Path REFASTER_COMPILER = REFASTER_OUTPUT.resolve("compiler");
  private static final Path REFASTER_CLASSES = REFASTER_COMPILER.resolve("classes");
  private static final Path REFASTER_APPLY_MODULES = REFASTER_OUTPUT.resolve("apply-modules");
  private static final Path REFASTER_APPLY_BUILD = REFASTER_OUTPUT.resolve("apply-build");
  private static final Path REFASTER_RULE = REFASTER_COMPILER.resolve("toktrak.refaster");
  private static final Path REFASTER_SOURCE = ROOT.resolve("tools/refaster/Rules.java");
  private static final Path APP_SOURCES = ROOT.resolve("sources/toktrak");
  private static final Path ASSET_SOURCES = APP_SOURCES.resolve("assets");
  private static final Path TEMPLATE_SOURCES = APP_SOURCES.resolve("templates");
  private static final int TEMPLATE_COUNT_MAX = 64;
  private static final int TEMPLATE_BYTES_MAX = 256 * 1024;
  private static final int TEMPLATE_BYTES_TOTAL_MAX = 1024 * 1024;
  private static final Pattern TEMPLATE_PATH =
      Pattern.compile("[a-z0-9][a-z0-9-]*(?:/[a-z0-9][a-z0-9-]*)*\\.mustache");
  private static final Path JUNIT_TEST_SOURCES = ROOT.resolve("tests/toktrak.tests");
  private static final Path BUILD_TEST_SOURCE = ROOT.resolve("tests/tools/BuildTest.java");
  private static final Path ERROR_PRONE_CONFIG = ROOT.resolve("sources/error-prone.cfg");
  private static final List<Path> REPOSITORY_SKILLS =
      List.of(
          ROOT.resolve(".claude/skills/mustache"), ROOT.resolve(".claude/skills/toktrak-jstachio"));
  private static final long FILE_BYTES_MAX = 512L * 1024 * 1024;
  private static final int TREE_ENTRIES_MAX = 100_000;
  private static final int ASSET_COUNT_MAX = 64;
  private static final int ASSET_BYTES_MAX = 4 * 1024 * 1024;
  private static final int ASSET_BYTES_TOTAL_MAX = 16 * 1024 * 1024;
  private static final int ASSET_INDEX_BYTES_MAX = 64 * 1024;
  private static final Pattern ASSET_PATH =
      Pattern.compile("[a-z0-9][a-z0-9._-]*(?:/[a-z0-9][a-z0-9._-]*)*");
  private static final int COLLECTION_ENTRIES_MAX = 10_000;
  private static final int ARGUMENTS_MAX = 10_000;
  private static final int ARGUMENT_BYTES_MAX = 32 * 1024;
  private static final int ARGFILE_BYTES_MAX = 8 * 1024 * 1024;
  // OS command-line limits vary. Keep formatter commands below 24 KiB and 128 source files;
  // split larger source sets into bounded batches so repository growth cannot break fmt/check.
  private static final int COMMAND_BYTES_MAX = 24 * 1024;
  private static final int FORMAT_BATCH_FILES_MAX = 128;
  private static final int COPY_BUFFER_BYTES = 64 * 1024;
  private static final String ARTIFACT_MARKER = ".toktrak-artifact";
  private static final int ARTIFACT_MARKER_BYTES_MAX = 8 * 1024 * 1024;
  private static final int ARTIFACT_FINGERPRINT_BYTES_MAX = 1024;
  private static final AtomicLong STAGING_SEQUENCE = new AtomicLong();
  private static final int IDE_FILE_BYTES_MAX = 1024 * 1024;
  private static final int TOOL_OUTPUT_BYTES_MAX = 1024 * 1024;
  private static final Duration PROCESS_TIMEOUT = Duration.ofMinutes(10);
  private static final Duration PROCESS_TIMEOUT_MAX = Duration.ofMinutes(20);
  private static final Duration CONTAINER_BUILD_TIMEOUT = PROCESS_TIMEOUT_MAX;
  private static final Duration CONTAINER_START_TIMEOUT = Duration.ofMinutes(1);
  private static final Duration UNIT_TEST_TIMEOUT = Duration.ofSeconds(30);
  private static final Duration PROCESS_KILL_TIMEOUT = Duration.ofSeconds(5);
  private static final int PIT_THREADS = 4;
  private static final int PIT_TIMEOUT_MILLIS = 10_000;
  private static final long COVERAGE_INSTRUCTION_PERCENT_MIN = 80;
  private static final long COVERAGE_BRANCH_PERCENT_MIN = 65;
  private static final Map<String, CoverageMinimum> COVERAGE_PACKAGE_MINIMUMS =
      Map.of(
          "toktrak.health", new CoverageMinimum(80, 75),
          "toktrak.http", new CoverageMinimum(80, 65),
          "toktrak.json", new CoverageMinimum(70, 50),
          "toktrak.log", new CoverageMinimum(90, 75),
          "toktrak.projection", new CoverageMinimum(80, 70),
          "toktrak.store", new CoverageMinimum(75, 60));
  private static final String PIT_JVM_ARGS =
      "-ea,-Djunit.jupiter.execution.timeout.default=5s,"
          + "-Djunit.platform.execution.listeners.deactivate=com.diffplug.selfie.*";
  private static final String PIT_MAIN =
      "org.pitest." + "mutation" + "test.commandline." + "Mutation" + "CoverageReport";
  private static final Set<String> DEV_EXCLUSIVE_COMMANDS =
      Set.of(
          "check",
          "ci",
          "clean",
          "container-verify",
          "coverage",
          "dev",
          "ide",
          "image",
          "pit",
          "prod",
          "refactor",
          "release",
          "test",
          "verify");
  private static final String IMAGE_REPOSITORY_ENV = "TOKTRAK_IMAGE_REPOSITORY";
  private static final String IMAGE_VERSION_LABEL = "org.opencontainers.image.version";
  private static final String IMAGE_REVISION_LABEL = "org.opencontainers.image.revision";
  private static final Set<String> BUILD_OWNED_PIT_OPTIONS =
      Set.of(
          "--classPath",
          "--includeLaunchClasspath",
          "--targetClasses",
          "--targetTests",
          "--excludedTestClasses",
          "--mutableCodePaths",
          "--sourceDirs",
          "--reportDir",
          "--outputFormats",
          "--timestampedReports",
          "--threads",
          "--timeoutConst",
          "--jvmArgs",
          "--failWhenNoMutations",
          "--historyInputLocation",
          "--historyOutputLocation");
  private static final boolean ANSI =
      Optional.ofNullable(System.console()).filter(Console::isTerminal).isPresent()
          && System.getenv("NO_COLOR") == null;
  private static final List<String> APP_JDK_MODULES =
      List.of("java.logging", "java.net.http", "jdk.httpserver");
  private static final List<String> PROD_RUNTIME_ROOTS =
      List.of("toktrak", "jdk.jcmd", "jdk.management.agent", "jdk.management.jfr");
  private static final List<String> TEST_JDK_MODULES =
      List.of("java.logging", "java.net.http", "jdk.httpserver");
  private static final List<String> TEST_EXPORTS =
      List.of(
          "toktrak/toktrak.dev=toktrak.tests",
          "toktrak/toktrak.http=toktrak.tests",
          "toktrak/toktrak.json=toktrak.tests",
          "toktrak/toktrak.log=toktrak.tests");

  private Build() {}

  record AssetSource(
      String scope, String logicalPath, String mediaType, String sha256, ImmutableBytes content) {
    AssetSource(String scope, String logicalPath, String mediaType, String sha256, byte[] bytes) {
      this(scope, logicalPath, mediaType, sha256, new ImmutableBytes(bytes));
    }

    AssetSource {
      assert scope.equals("public") || scope.equals("private");
      assert logicalPath != null && !logicalPath.isBlank();
      assert mediaType != null && !mediaType.isBlank();
      assert sha256.matches("[0-9a-f]{64}");
      assert content != null;
    }

    byte[] bytes() {
      return content.copy();
    }
  }

  record AssetBundle(List<AssetSource> assets, ImmutableBytes indexBytes, String fingerprint) {
    AssetBundle(List<AssetSource> assets, byte[] index, String fingerprint) {
      this(assets, new ImmutableBytes(index), fingerprint);
    }

    AssetBundle {
      assets = List.copyOf(assets);
      assert indexBytes != null;
      assert assets.size() <= ASSET_COUNT_MAX;
      assert fingerprint.matches("[0-9a-f]{64}");
    }

    byte[] index() {
      return indexBytes.copy();
    }
  }

  static final class ImmutableBytes {
    private final byte[] value;

    private ImmutableBytes(byte[] value) {
      assert value != null;
      this.value = value.clone();
    }

    private byte[] copy() {
      return value.clone();
    }
  }

  public static void main(String[] args) throws Exception {
    long started = System.nanoTime();
    try {
      requireAssertions();
      assert args != null;
      if (args.length == 0) {
        throw new IllegalStateException(
            "command required: clean, fmt, check, test, pit, refactor, ci, verify, ide, dev, prod,"
                + " coverage, image, container-verify, or release");
      }
      if (args.length > 256)
        throw new IllegalStateException("command arguments exceed 256 entries");
      List<String> commandArguments = List.of(args).subList(1, args.length);
      if (args[0].equals("fmt")) {
        formatCommand(commandArguments);
        return;
      }
      if (args[0].equals("pit") && commandArguments.equals(List.of("--help"))) {
        pitCommand(commandArguments);
        return;
      }
      Files.createDirectories(OUTPUT);
      try (var channel =
              FileChannel.open(BUILD_LOCK, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
          FileLock lock = acquireBuildLock(channel)) {
        assert lock.isValid();
        requireDevelopmentServerStopped(args[0], DEV_DATA_DIRECTORY);
        if (args[0].equals("pit")) {
          pitCommand(commandArguments);
          return;
        }
        deleteTree(ARGFILES);
        switch (args[0]) {
          case "clean" -> clean();
          case "check" -> check();
          case "test" -> testCommand(commandArguments);
          case "refactor" -> refactor();
          case "ci" -> ci();
          case "verify" -> verify();
          case "ide" -> ideCommand(commandArguments);
          case "dev" -> dev(commandArguments);
          case "prod" -> jlinkProd();
          case "coverage" -> coverage();
          case "image" -> imageCommand(commandArguments);
          case "container-verify" -> containerVerifyCommand(commandArguments);
          case "release" -> releaseCommand(commandArguments);
          default -> throw new IllegalStateException("unknown command: " + args[0]);
        }
      }
    } finally {
      printTotal(System.nanoTime() - started);
    }
  }

  static FileLock acquireBuildLockForTest(FileChannel channel) throws IOException {
    return acquireBuildLock(channel);
  }

  private static FileLock acquireBuildLock(FileChannel channel) throws IOException {
    assert channel != null;
    try {
      FileLock lock = channel.tryLock();
      if (lock != null) return lock;
    } catch (OverlappingFileLockException exception) {
      throw new IllegalStateException("another TokTrak build command is running", exception);
    }
    throw new IllegalStateException("another TokTrak build command is running");
  }

  static void requireDevelopmentServerStoppedForTest(String command, Path dataDirectory) {
    requireDevelopmentServerStopped(command, dataDirectory);
  }

  private static void requireDevelopmentServerStopped(String command, Path dataDirectory) {
    assert command != null;
    assert dataDirectory != null;
    if (!DEV_EXCLUSIVE_COMMANDS.contains(command) || !developmentServerRunning(dataDirectory)) {
      return;
    }
    throw new IllegalStateException(
        "cannot run " + command + " while mise run dev is running; stop it with Ctrl+C");
  }

  private static boolean developmentServerRunning(Path dataDirectory) {
    assert dataDirectory != null;
    Path lockPath = dataDirectory.resolve("toktrak.lock");
    if (!Files.isRegularFile(lockPath)) return false;
    try (var channel = FileChannel.open(lockPath, StandardOpenOption.WRITE)) {
      try {
        FileLock lock = channel.tryLock();
        if (lock == null) return true;
        lock.release();
        return false;
      } catch (OverlappingFileLockException exception) {
        return true;
      }
    } catch (IOException exception) {
      throw new IllegalStateException("cannot inspect development server lock", exception);
    }
  }

  private static void clean() throws IOException {
    cleanGeneratedTree(MODULES);
    cleanGeneratedTree(MAIN_DEPS);
    cleanGeneratedTree(TEST_DEPS);
    cleanGeneratedTree(SNAPSHOT_DEPS);
    cleanGeneratedTree(BUILD_DEPS);
    cleanGeneratedTree(REFASTER_DEPS);
    cleanGeneratedTree(PIT_DEPS);
    cleanGeneratedTree(COVERAGE_DEPS);
    cleanGeneratedTree(COVERAGE);
    cleanGeneratedTree(MUTATIONS);
    Files.deleteIfExists(PIT_HISTORY);
    cleanGeneratedTree(RUNTIMES);
    cleanGeneratedTree(CONTAINER_VERIFY);
    cleanGeneratedTree(IDE);
    cleanIntellijMetadata(INTELLIJ_IDEA);
    deleteTree(ARGFILES);
    cleanGeneratedTree(BUILD_TESTS);
    cleanGeneratedTree(REFASTER_OUTPUT);
  }

  private static void deps() throws Exception {
    ensureDependency(
        "sources/main-deps.txt", MAIN_DEPS, "resolve-toktrak-production-dependencies", true);
    ensureDependency("sources/test-deps.txt", TEST_DEPS, "resolve-toktrak-test-dependencies", true);
    ensureDependency(
        "sources/snapshot-deps.txt", SNAPSHOT_DEPS, "resolve-snapshot-dependencies", false);
    ensureBuildDependencies();
    ensureRefasterDependencies();
    verifyModules(MAIN_DEPS);
    verifyModules(TEST_DEPS);
  }

  private static void ensureBuildDependencies() throws Exception {
    ensureDependency("sources/build-deps.txt", BUILD_DEPS, "resolve-build-dependencies", true);
  }

  private static void ensureRefasterDependencies() throws Exception {
    ensureDependency(
        "sources/refaster-deps.txt", REFASTER_DEPS, "resolve-refaster-dependencies", false);
  }

  private static void ensureDependency(
      String dependencyFile, Path output, String argFileName, boolean useModuleNames)
      throws Exception {
    long started = System.nanoTime();
    Path source = ROOT.resolve(dependencyFile);
    var arguments =
        new ArrayList<>(
            List.of(
                "-ea",
                "-jar",
                ROOT.resolve("vendored/jresolve.jar").toString(),
                "--output-directory=" + output,
                "--dependency-file=" + source));
    if (useModuleNames) arguments.add("--use-module-names");
    Path argFile = writeArgFile(argFileName, arguments);
    String fingerprint = dependencyFingerprint(source);
    if (artifactMatches(output, fingerprint)) {
      printCached(javaExecutable(), argFile, started);
      return;
    }

    rebuildArtifact(
        output,
        fingerprint,
        staging -> {
          List<String> stagingArguments = remapArguments(arguments, output, staging);
          runArgFile(javaExecutable(), argFileName, stagingArguments);
          List<Path> jars = jarPaths(List.of(staging));
          if (jars.isEmpty() || jars.stream().anyMatch(path -> emptyFile(path))) {
            throw new IllegalStateException("resolved dependency artifacts are missing or empty");
          }
        });
  }

  private static void pitCommand(List<String> arguments) throws Exception {
    assert arguments != null;
    if (arguments.equals(List.of("--help"))) {
      System.out.println(
          "usage: mise run pit [PIT options...] [-- production source files/directories...]");
      return;
    }
    PitSelection selection = pitSelection(arguments);
    deleteTree(ARGFILES);
    compile();
    ensurePitDependencies();
    String classpath = pitClasspath();
    if (selection.pitHelp()) {
      List<String> launchArguments =
          new ArrayList<>(
              List.of(
                  "-ea",
                  "-Djunit.jupiter.execution.timeout.default=5s",
                  "-cp",
                  classpath,
                  PIT_MAIN));
      launchArguments.addAll(pitArguments(selection, MUTATIONS));
      runArgFile(javaExecutable(), "pit", launchArguments, PROCESS_TIMEOUT, false);
      return;
    }
    rebuildArtifact(
        MUTATIONS,
        mutationFingerprint(selection),
        staging -> {
          List<String> launchArguments =
              new ArrayList<>(
                  List.of(
                      "-ea",
                      "-Djunit.jupiter.execution.timeout.default=5s",
                      "-cp",
                      classpath,
                      PIT_MAIN));
          launchArguments.addAll(pitArguments(selection, staging));
          runArgFile(javaExecutable(), "pit", launchArguments, PROCESS_TIMEOUT, false);
          validatePitReport(staging, selection.dryRun());
        });
  }

  private static String mutationFingerprint(PitSelection selection) throws Exception {
    var digest = MessageDigest.getInstance("SHA-256");
    update(digest, "pit\n" + platformFingerprint() + "\n");
    update(digest, argumentFileContent(pitArguments(selection, MUTATIONS)));
    return HexFormat.of().formatHex(digest.digest());
  }

  private static void ensureCoverageDependencies() throws Exception {
    ensureDependency(
        "sources/coverage-deps.txt", COVERAGE_DEPS, "resolve-coverage-dependencies", false);
  }

  private static void ensurePitDependencies() throws Exception {
    ensureDependency("sources/pit-deps.txt", PIT_DEPS, "resolve-pit-dependencies", false);
    if (!pitArtifactsValid(PIT_DEPS)) {
      throw new IllegalStateException("PIT dependency artifacts are missing or empty");
    }
  }

  private static boolean emptyFile(Path path) {
    try {
      return Files.size(path) == 0;
    } catch (IOException exception) {
      throw new IllegalStateException("cannot inspect generated file: " + path, exception);
    }
  }

  static boolean pitArtifactsValidForTest(Path directory) throws IOException {
    return pitArtifactsValid(directory);
  }

  private static boolean pitArtifactsValid(Path directory) throws IOException {
    assert directory != null;
    if (!Files.isDirectory(directory)) return false;
    List<Path> entries = directoryEntries(directory, TREE_ENTRIES_MAX);
    List<Path> commandLine =
        entries.stream()
            .filter(path -> path.getFileName().toString().startsWith("pitest-command-line-"))
            .filter(Files::isRegularFile)
            .filter(Build::isJar)
            .toList();
    List<Path> junitPlugin =
        entries.stream()
            .filter(path -> path.getFileName().toString().startsWith("pitest-junit5-plugin-"))
            .filter(Files::isRegularFile)
            .filter(Build::isJar)
            .toList();
    List<Path> historyPlugin =
        entries.stream()
            .filter(path -> path.getFileName().toString().startsWith("pitest-history-plugin-"))
            .filter(Files::isRegularFile)
            .filter(Build::isJar)
            .toList();
    return commandLine.size() == 1
        && junitPlugin.size() == 1
        && historyPlugin.size() == 1
        && Files.size(commandLine.getFirst()) > 0
        && Files.size(junitPlugin.getFirst()) > 0
        && Files.size(historyPlugin.getFirst()) > 0;
  }

  private static String pitClasspath() throws IOException {
    var paths = new ArrayList<Path>();
    paths.addAll(jarPaths(List.of(PIT_DEPS)));
    paths.add(APP_MODULE);
    paths.add(TEST_MODULE);
    paths.addAll(jarPaths(List.of(MAIN_DEPS)));
    paths.addAll(jarPaths(List.of(TEST_DEPS)));
    paths.addAll(jarPaths(List.of(SNAPSHOT_DEPS)));
    if (paths.size() > COLLECTION_ENTRIES_MAX) {
      throw new IllegalStateException(
          "PIT classpath exceeds " + COLLECTION_ENTRIES_MAX + " entries");
    }
    return paths.stream()
        .map(Path::toString)
        .collect(java.util.stream.Collectors.joining(System.getProperty("path.separator")));
  }

  static PitSelection pitSelectionForTest(List<String> arguments) throws IOException {
    return pitSelection(arguments);
  }

  private static PitSelection pitSelection(List<String> arguments) throws IOException {
    assert arguments != null;
    if (arguments.size() > 256) throw new IllegalStateException("PIT arguments exceed 256 entries");
    var forwarded = new ArrayList<String>();
    var sources = new ArrayList<String>();
    boolean sourceSection = false;
    boolean history = false;
    for (String argument : arguments) {
      if (argument.equals("--")) {
        if (sourceSection) throw new IllegalStateException("duplicate PIT source separator");
        sourceSection = true;
      } else if (sourceSection) {
        Path path = resolveProjectPath(argument);
        if (!path.startsWith(APP_SOURCES)) {
          throw new IllegalStateException("path is outside production sources: " + argument);
        }
        sources.add(argument);
      } else if (argument.equals("--history")) {
        if (history) throw new IllegalStateException("duplicate --history");
        history = true;
      } else {
        String option =
            argument.contains("=") ? argument.substring(0, argument.indexOf('=')) : argument;
        if (BUILD_OWNED_PIT_OPTIONS.contains(option)) {
          throw new IllegalStateException("Build owns PIT option: " + option);
        }
        forwarded.add(argument);
      }
    }
    if (sourceSection && sources.isEmpty()) {
      throw new IllegalStateException("no mutable production source files selected");
    }
    boolean dryRun = dryRun(forwarded);
    boolean pitHelp = forwarded.contains("-h") || forwarded.contains("-?");
    List<String> targets =
        sources.isEmpty() ? pitTargets(List.of(APP_SOURCES.toString())) : pitTargets(sources);
    return new PitSelection(List.copyOf(forwarded), targets, dryRun, history, pitHelp);
  }

  private static boolean dryRun(List<String> arguments) {
    assert arguments != null;
    int matches = 0;
    boolean enabled = false;
    for (int index = 0; index < arguments.size(); index++) {
      String argument = arguments.get(index);
      if (argument.equals("--dryRun")) {
        matches = Math.addExact(matches, 1);
        enabled =
            index + 1 >= arguments.size()
                || (!arguments.get(index + 1).equalsIgnoreCase("false")
                    && !arguments.get(index + 1).equalsIgnoreCase("true"))
                || Boolean.parseBoolean(arguments.get(index + 1));
      } else if (argument.startsWith("--dryRun=")) {
        matches = Math.addExact(matches, 1);
        enabled = Boolean.parseBoolean(argument.substring("--dryRun=".length()));
      }
    }
    if (matches > 1) throw new IllegalStateException("duplicate --dryRun");
    return enabled;
  }

  private static List<String> pitTargets(List<String> sources) throws IOException {
    assert sources != null && !sources.isEmpty();
    List<Path> files =
        selectedFiles(
            sources,
            List.of(),
            candidate -> {
              String name = candidate.getFileName().toString();
              return name.endsWith(".java")
                  && !name.equals("module-info.java")
                  && !name.equals("package-info.java");
            },
            "mutable production source");
    var targets = new TreeSet<String>();
    for (Path file : files) {
      if (!file.startsWith(APP_SOURCES)) {
        throw new IllegalStateException("path is outside production sources: " + file);
      }
      String relative = APP_SOURCES.relativize(file).toString();
      String className =
          relative
              .substring(0, relative.length() - ".java".length())
              .replace('\\', '.')
              .replace('/', '.');
      targets.add(className);
      targets.add(className + "$*");
    }
    return List.copyOf(targets);
  }

  static List<String> pitArgumentsForTest(PitSelection selection, Path reportDirectory) {
    return pitArguments(selection, reportDirectory);
  }

  private static List<String> pitArguments(PitSelection selection, Path reportDirectory) {
    assert selection != null;
    assert reportDirectory != null;
    var arguments =
        new ArrayList<>(
            List.of(
                "--targetClasses",
                String.join(",", selection.targetClasses()),
                "--targetTests",
                "toktrak.tests.*",
                "--excludedTestClasses",
                "toktrak.tests.SnapshotTest",
                "--mutableCodePaths",
                APP_MODULE.toString(),
                "--sourceDirs",
                APP_SOURCES.toString(),
                "--reportDir",
                reportDirectory.toAbsolutePath().normalize().toString(),
                "--outputFormats",
                "HTML,XML",
                "--timestampedReports",
                "false",
                "--threads",
                Integer.toString(PIT_THREADS),
                "--timeoutConst",
                Integer.toString(PIT_TIMEOUT_MILLIS),
                "--jvmArgs",
                PIT_JVM_ARGS,
                "--failWhenNoMutations",
                "true"));
    if (selection.history()) {
      arguments.add("--historyInputLocation");
      arguments.add(PIT_HISTORY.toString());
      arguments.add("--historyOutputLocation");
      arguments.add(PIT_HISTORY.toString());
    }
    if (!hasExplicitVerbosity(selection.forwardedArguments())) {
      arguments.add("--verbosity");
      arguments.add("NO_SPINNER");
    }
    arguments.addAll(selection.forwardedArguments());
    return List.copyOf(arguments);
  }

  private static boolean hasExplicitVerbosity(List<String> arguments) {
    assert arguments != null;
    return arguments.stream()
        .anyMatch(
            argument ->
                argument.equals("--verbosity")
                    || argument.startsWith("--verbosity=")
                    || argument.equals("--verbose")
                    || argument.startsWith("--verbose="));
  }

  static void validatePitReportForTest(Path reportDirectory) throws Exception {
    validatePitReport(reportDirectory, false);
  }

  static void validatePitReportForTest(Path reportDirectory, boolean dryRun) throws Exception {
    validatePitReport(reportDirectory, dryRun);
  }

  private static void validatePitReport(Path reportDirectory, boolean dryRun) throws Exception {
    assert reportDirectory != null;
    if (!Files.isDirectory(reportDirectory)) {
      throw new IllegalStateException("PIT report directory is missing: " + reportDirectory);
    }
    for (Path path : treePaths(reportDirectory, TREE_ENTRIES_MAX)) {
      if (Files.isSymbolicLink(path)) {
        throw new IllegalStateException("PIT report contains symbolic path: " + path);
      }
      if (Files.isRegularFile(path) && Files.size(path) > FILE_BYTES_MAX) {
        throw new IllegalStateException(
            "PIT report file exceeds " + FILE_BYTES_MAX + " bytes: " + path);
      }
    }
    Path html = reportDirectory.resolve("index.html");
    Path xml = reportDirectory.resolve("mutations.xml");
    if (!Files.isRegularFile(html) || Files.size(html) == 0) {
      throw new IllegalStateException("PIT HTML report is missing or empty");
    }
    if (!Files.isRegularFile(xml) || Files.size(xml) == 0) {
      throw new IllegalStateException("PIT XML report is missing or empty");
    }
    var factory = DocumentBuilderFactory.newInstance();
    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
    factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
    factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
    factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
    factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
    factory.setXIncludeAware(false);
    factory.setExpandEntityReferences(false);
    var document = factory.newDocumentBuilder().parse(xml.toFile());
    var mutations = document.getElementsByTagName("mutation");
    if (mutations.getLength() == 0) throw new IllegalStateException("PIT reported no mutations");
    for (int index = 0; index < mutations.getLength(); index++) {
      var mutation = (Element) mutations.item(index);
      String status = mutation.getAttribute("status");
      if (dryRun) {
        if (!status.equals("NOT_STARTED")) {
          throw new IllegalStateException("PIT dry-run mutation failed: " + status);
        }
      } else if (!status.equals("KILLED")
          && !status.equals("SURVIVED")
          && !status.equals("NO_COVERAGE")) {
        throw new IllegalStateException("PIT mutation failed: " + status);
      }
    }
  }

  static record PitSelection(
      List<String> forwardedArguments,
      List<String> targetClasses,
      boolean dryRun,
      boolean history,
      boolean pitHelp) {
    PitSelection {
      assert forwardedArguments != null;
      assert targetClasses != null && !targetClasses.isEmpty();
      assert forwardedArguments.size() <= ARGUMENTS_MAX;
      assert targetClasses.size() <= COLLECTION_ENTRIES_MAX;
    }
  }

  private static void ideCommand(List<String> arguments) throws Exception {
    assert arguments != null;
    if (arguments.size() > 1) {
      throw new IllegalArgumentException("ide requires zero or one target");
    }
    if (arguments.isEmpty()) {
      generateIdeMetadata(true, true);
      return;
    }
    switch (arguments.getFirst()) {
      case "--help" -> System.out.println("usage: mise run ide [eclipse|intellij]");
      case "eclipse" -> generateIdeMetadata(true, false);
      case "intellij" -> generateIdeMetadata(false, true);
      default -> throw new IllegalArgumentException("unknown IDE target: " + arguments.getFirst());
    }
  }

  private static void generateIdeMetadata(boolean eclipse, boolean intellij) throws Exception {
    assert eclipse || intellij;
    if (Runtime.version().feature() != 26) {
      throw new IllegalStateException("IDE metadata requires Java 26");
    }
    ensureDependency(
        "sources/main-deps.txt", MAIN_DEPS, "resolve-toktrak-production-dependencies", true);
    ensureDependency("sources/test-deps.txt", TEST_DEPS, "resolve-toktrak-test-dependencies", true);
    ensureRefasterDependencies();
    verifyModules(MAIN_DEPS);
    verifyModules(TEST_DEPS);
    List<Path> mainJars = jarPaths(List.of(MAIN_DEPS));
    List<Path> testJars = jarPaths(List.of(TEST_DEPS));
    Path refaster = refasterJar();
    String fingerprint = ideFingerprint(mainJars, testJars, refaster);
    if (eclipse) {
      rebuildArtifact(
          ECLIPSE_IDE,
          fingerprint,
          staging -> generateEclipseProjects(ROOT, staging, mainJars, testJars, refaster));
    }
    if (intellij) {
      rebuildArtifact(
          INTELLIJ_METADATA,
          fingerprint,
          staging -> generateIntellijProjects(ROOT, staging, mainJars, testJars, refaster));
      installIntellijMetadata(INTELLIJ_METADATA, INTELLIJ_IDEA);
    }
  }

  private static String ideFingerprint(List<Path> mainJars, List<Path> testJars, Path refaster)
      throws Exception {
    var digest = MessageDigest.getInstance("SHA-256");
    update(digest, "ide\n" + platformFingerprint() + "\n");
    for (Path path : Stream.concat(mainJars.stream(), testJars.stream()).toList()) {
      update(digest, path.getFileName() + "\n");
      updateDigestFromFile(digest, path);
    }
    updateDigestFromFile(digest, refaster);
    updateDigestFromFile(digest, ROOT.resolve("tools/Build.java"));
    for (Path path : jarPaths(List.of(BUILD_DEPS))) updateDigestFromFile(digest, path);
    updateTree(digest, TEMPLATE_SOURCES, ".mustache");
    return HexFormat.of().formatHex(digest.digest());
  }

  private static void installIntellijMetadata(Path generated, Path idea) throws IOException {
    Files.createDirectories(idea);
    Files.deleteIfExists(idea.resolve(ARTIFACT_MARKER));
    for (String relative : intellijFiles()) {
      writeIdeBytes(idea, idea.resolve(relative), Files.readAllBytes(generated.resolve(relative)));
    }
    writeIdeBytes(
        idea,
        idea.resolve(ARTIFACT_MARKER),
        Files.readAllBytes(generated.resolve(ARTIFACT_MARKER)));
  }

  private static List<String> intellijFiles() {
    return List.of(
        "modules.xml",
        "misc.xml",
        "compiler.xml",
        "modules/toktrak.iml",
        "modules/toktrak.tests.iml",
        "modules/toktrak.build.iml");
  }

  static void generateEclipseProjectsForTest(
      Path root, Path output, List<Path> mainJars, List<Path> testJars, Path refasterJar)
      throws IOException {
    generateEclipseProjects(root, output, mainJars, testJars, refasterJar);
  }

  private static void generateEclipseProjects(
      Path root, Path output, List<Path> mainJars, List<Path> testJars, Path refasterJar)
      throws IOException {
    assert root != null;
    assert output != null;
    assert mainJars != null;
    assert testJars != null;
    assert refasterJar != null;
    root = root.toAbsolutePath().normalize();
    output = output.toAbsolutePath().normalize();
    if (!output.getParent().equals(root.resolve("output/ide"))) {
      throw new IllegalArgumentException("Eclipse metadata must be staged under output/ide");
    }
    requireDirectory(root.resolve("sources/toktrak"));
    requireDirectory(root.resolve("tests/toktrak.tests"));
    requireDirectory(root.resolve("tools"));
    requireDirectory(root.resolve("tests/tools"));
    requireCollectionSize(mainJars, "Eclipse main JARs");
    requireCollectionSize(testJars, "Eclipse test JARs");
    requireFile(refasterJar);
    for (Path jar : mainJars) requireFile(jar);
    for (Path jar : testJars) requireFile(jar);

    deleteTree(output);
    Path app = output.resolve("toktrak");
    Path tests = output.resolve("toktrak.tests");
    Path build = output.resolve("toktrak.build");
    writeEclipseProject(
        output,
        app,
        eclipseProject("toktrak", "", eclipseLink("src", "sources/toktrak")),
        eclipseAppClasspath(mainJars));
    writeIdeFile(
        output,
        app.resolve(".settings/org.eclipse.jdt.apt.core.prefs"),
        "eclipse.preferences.version=1\n"
            + "org.eclipse.jdt.apt.aptEnabled=true\n"
            + "org.eclipse.jdt.apt.genSrcDir=.apt_generated\n"
            + "org.eclipse.jdt.apt.processorOptions/jstache.resourcesPath="
            + root.resolve("sources/toktrak/templates").toString().replace('\\', '/')
            + "\norg.eclipse.jdt.apt.processorOptions/jstache.incremental=true\n"
            + "org.eclipse.jdt.apt.processorOptions/jstache.claim_annotations=true\n");
    writeIdeFile(
        output, app.resolve(".factorypath"), eclipseFactoryPath(jarPaths(List.of(BUILD_DEPS))));
    writeEclipseProject(
        output,
        tests,
        eclipseProject(
            "toktrak.tests",
            "    <project>toktrak</project>\n",
            eclipseLink("test", "tests/toktrak.tests")),
        eclipseTestClasspath(mainJars, testJars));
    Files.createDirectories(build.resolve("src"));
    Files.createDirectories(build.resolve("test"));
    writeEclipseProject(
        output,
        build,
        eclipseProject(
            "toktrak.build",
            "",
            eclipseLink("src/tools", "tools") + eclipseLink("test/tools", "tests/tools")),
        eclipseBuildClasspath(refasterJar));
    assert Files.isRegularFile(app.resolve(".project"));
    assert Files.isRegularFile(tests.resolve(".project"));
    assert Files.isRegularFile(build.resolve(".project"));
  }

  static void generateIntellijProjectsForTest(
      Path root, Path idea, List<Path> mainJars, List<Path> testJars, Path refasterJar)
      throws IOException {
    generateIntellijProjects(root, idea, mainJars, testJars, refasterJar);
  }

  private static void generateIntellijProjects(
      Path root, Path idea, List<Path> mainJars, List<Path> testJars, Path refasterJar)
      throws IOException {
    assert root != null;
    assert idea != null;
    assert mainJars != null;
    assert testJars != null;
    assert refasterJar != null;
    root = root.toAbsolutePath().normalize();
    idea = idea.toAbsolutePath().normalize();
    if (!idea.equals(root.resolve(".idea"))
        && !idea.getParent().equals(root.resolve("output/ide"))) {
      throw new IllegalArgumentException("IntelliJ metadata must be staged under output/ide");
    }
    requireDirectory(root.resolve("sources/toktrak"));
    requireDirectory(root.resolve("tests/toktrak.tests"));
    requireDirectory(root.resolve("tools"));
    requireDirectory(root.resolve("tests/tools"));
    requireCollectionSize(mainJars, "IntelliJ main JARs");
    requireCollectionSize(testJars, "IntelliJ test JARs");
    requireFile(refasterJar);
    for (Path jar : mainJars) requireFile(jar);
    for (Path jar : testJars) requireFile(jar);

    List<Path> allTestJars = new ArrayList<>(mainJars);
    if (testJars.size() > COLLECTION_ENTRIES_MAX - allTestJars.size()) {
      throw new IllegalStateException("IntelliJ test JARs exceed collection limit");
    }
    allTestJars.addAll(testJars);
    writeIdeFile(idea, idea.resolve("modules.xml"), intellijModules());
    writeIdeFile(idea, idea.resolve("misc.xml"), intellijMisc());
    writeIdeFile(
        idea, idea.resolve("compiler.xml"), intellijCompiler(root, jarPaths(List.of(BUILD_DEPS))));
    writeIdeFile(
        idea,
        idea.resolve("modules/toktrak.iml"),
        intellijModule("toktrak", intellijAppContent(), intellijLibrary(root, mainJars, false)));
    writeIdeFile(
        idea,
        idea.resolve("modules/toktrak.tests.iml"),
        intellijModule(
            "toktrak.tests",
            intellijContent("tests/toktrak.tests", true, null),
            "    <orderEntry type=\"module\" module-name=\"toktrak\" scope=\"TEST\"/>\n"
                + intellijLibrary(root, allTestJars, true)));
    writeIdeFile(
        idea,
        idea.resolve("modules/toktrak.build.iml"),
        intellijModule(
            "toktrak.build",
            intellijContent("tools", false, "tools")
                + intellijContent("tests/tools", true, "tools"),
            intellijLibrary(root, List.of(refasterJar), false)));
    assert Files.isRegularFile(idea.resolve("modules.xml"));
    assert Files.isRegularFile(idea.resolve("modules/toktrak.iml"));
    assert Files.isRegularFile(idea.resolve("modules/toktrak.tests.iml"));
    assert Files.isRegularFile(idea.resolve("modules/toktrak.build.iml"));
  }

  private static String intellijModules() {
    var modules = new StringBuilder();
    for (String name : List.of("toktrak", "toktrak.tests", "toktrak.build")) {
      modules
          .append("      <module fileurl=\"file://$PROJECT_DIR$/.idea/modules/")
          .append(xml(name))
          .append(".iml\" filepath=\"$PROJECT_DIR$/.idea/modules/")
          .append(xml(name))
          .append(".iml\"/>\n");
    }
    return """
    <?xml version="1.0" encoding="UTF-8"?>
    <project version="4">
      <component name="ProjectModuleManager">
        <modules>
    %s    </modules>
      </component>
    </project>
    """
        .formatted(modules);
  }

  private static String intellijMisc() {
    return """
    <?xml version="1.0" encoding="UTF-8"?>
    <project version="4">
      <component name="ProjectRootManager" version="2" languageLevel="JDK_26" project-jdk-name="26" project-jdk-type="JavaSDK">
        <output url="file://$PROJECT_DIR$/output/ide/intellij"/>
      </component>
    </project>
    """;
  }

  private static String intellijCompiler(Path root, List<Path> processorJars) {
    var options = new StringBuilder();
    for (String export : TEST_EXPORTS) {
      if (!options.isEmpty()) options.append(' ');
      options.append("--add-exports=").append(export);
    }
    var processorPath = new StringBuilder();
    for (Path jar : processorJars) {
      processorPath
          .append("            <entry name=\"")
          .append(xml(jar.toAbsolutePath().normalize().toString().replace('\\', '/')))
          .append("\"/>\n");
    }
    String templates =
        root.resolve("sources/toktrak/templates")
            .toAbsolutePath()
            .normalize()
            .toString()
            .replace('\\', '/');
    return """
    <?xml version="1.0" encoding="UTF-8"?>
    <project version="4">
      <component name="CompilerConfiguration">
        <annotationProcessing>
          <profile default="false" name="TokTrak JStachio" enabled="true">
            <sourceOutputDir name="output/ide/intellij/generated"/>
            <sourceTestOutputDir name="output/ide/intellij/generated-test"/>
            <processorPath useClasspath="false">
    %s        </processorPath>
            <processor name="io.jstach.apt.GenerateRendererProcessor"/>
            <option name="jstache.resourcesPath" value="%s"/>
            <option name="jstache.incremental" value="true"/>
            <option name="jstache.claim_annotations" value="true"/>
            <module name="toktrak"/>
          </profile>
        </annotationProcessing>
        <bytecodeTargetLevel target="26"/>
      </component>
      <component name="JavacSettings">
        <option name="ADDITIONAL_OPTIONS_OVERRIDE">
          <module name="toktrak.tests" options="%s"/>
        </option>
      </component>
    </project>
    """
        .formatted(processorPath, xml(templates), xml(options.toString()));
  }

  private static String intellijModule(String name, String content, String dependencies) {
    assert name != null && !name.isBlank();
    assert content != null && !content.isBlank();
    assert dependencies != null;
    return """
    <?xml version="1.0" encoding="UTF-8"?>
    <module type="JAVA_MODULE" version="4">
      <component name="NewModuleRootManager" LANGUAGE_LEVEL="JDK_26" inherit-compiler-output="false">
        <output url="file://$PROJECT_DIR$/output/ide/intellij/classes/%s"/>
        <output-test url="file://$PROJECT_DIR$/output/ide/intellij/test-classes/%s"/>
        <exclude-output/>
    %s    <orderEntry type="inheritedJdk"/>
        <orderEntry type="sourceFolder" forTests="false"/>
    %s  </component>
    </module>
    """
        .formatted(xml(name), xml(name), content, dependencies);
  }

  private static String intellijAppContent() {
    return """
        <content url="file://$PROJECT_DIR$/sources/toktrak">
          <sourceFolder url="file://$PROJECT_DIR$/sources/toktrak" isTestSource="false"/>
          <excludeFolder url="file://$PROJECT_DIR$/sources/toktrak/templates"/>
        </content>
    """;
  }

  private static String intellijContent(String path, boolean test, String packagePrefix) {
    assert path != null && !path.isBlank();
    String prefix = packagePrefix == null ? "" : " packagePrefix=\"" + xml(packagePrefix) + "\"";
    return "    <content url=\"file://$PROJECT_DIR$/"
        + xml(path)
        + "\">\n"
        + "      <sourceFolder url=\"file://$PROJECT_DIR$/"
        + xml(path)
        + "\" isTestSource=\""
        + test
        + "\""
        + prefix
        + "/>\n"
        + "    </content>\n";
  }

  private static String intellijLibrary(Path root, List<Path> jars, boolean test) {
    assert root != null;
    assert jars != null;
    requireCollectionSize(jars, "IntelliJ library JARs");
    var roots = new StringBuilder();
    for (Path jar : jars) {
      Path normalized = jar.toAbsolutePath().normalize();
      if (!normalized.startsWith(root)) {
        throw new IllegalArgumentException("IntelliJ library escapes project root");
      }
      String relative = root.relativize(normalized).toString().replace('\\', '/');
      roots
          .append("          <root url=\"jar://$PROJECT_DIR$/")
          .append(xml(relative))
          .append("!/\"/>\n");
    }
    String scope = test ? " scope=\"TEST\"" : "";
    return """
        <orderEntry type="module-library"%s>
          <library>
            <CLASSES>
    %s        </CLASSES>
            <JAVADOC/>
            <SOURCES/>
          </library>
        </orderEntry>
    """
        .formatted(scope, roots);
  }

  private static void requireDirectory(Path directory) {
    assert directory != null;
    if (!Files.isDirectory(directory)) {
      throw new IllegalStateException("required IDE source directory missing: " + directory);
    }
  }

  private static void requireFile(Path file) {
    assert file != null;
    if (!Files.isRegularFile(file)) {
      throw new IllegalStateException("required IDE file missing: " + file);
    }
  }

  private static String eclipseProject(String name, String projects, String links) {
    assert name != null && !name.isBlank();
    assert projects != null;
    assert links != null;
    return """
    <?xml version="1.0" encoding="UTF-8"?>
    <projectDescription>
      <name>%s</name>
      <comment></comment>
      <projects>
    %s  </projects>
      <buildSpec>
        <buildCommand>
          <name>org.eclipse.jdt.core.javabuilder</name>
          <arguments></arguments>
        </buildCommand>
      </buildSpec>
      <natures>
        <nature>org.eclipse.jdt.core.javanature</nature>
      </natures>
      <linkedResources>
    %s  </linkedResources>
    </projectDescription>
    """
        .formatted(xml(name), projects, links);
  }

  private static String eclipseLink(String name, String target) {
    assert name != null && !name.isBlank();
    assert target != null && !target.isBlank();
    return """
        <link>
          <name>%s</name>
          <type>2</type>
          <locationURI>PARENT-4-PROJECT_LOC/%s</locationURI>
        </link>
    """
        .formatted(xml(name), xml(target));
  }

  private static String eclipseFactoryPath(List<Path> jars) {
    var entries = new StringBuilder("<factorypath>\n");
    for (Path jar : jars) {
      entries
          .append("  <factorypathentry kind=\"EXTJAR\" id=\"")
          .append(xml(jar.toAbsolutePath().normalize().toString().replace('\\', '/')))
          .append("\" enabled=\"true\" runInBatchMode=\"false\"/>\n");
    }
    return entries.append("</factorypath>\n").toString();
  }

  private static String eclipseAppClasspath(List<Path> jars) {
    var entries = new StringBuilder();
    entries.append(
        "  <classpathentry excluding=\"templates/**\" kind=\"src\" path=\"src\""
            + " output=\"bin/main\"/>\n");
    entries.append(eclipseJre(true));
    for (Path jar : jars) entries.append(eclipseLibrary(jar, true, false));
    return eclipseClasspath(entries);
  }

  private static String eclipseTestClasspath(List<Path> mainJars, List<Path> testJars) {
    var entries = new StringBuilder();
    entries.append(eclipseSource("test", "bin/test", null, true));
    entries.append(eclipseJre(true));
    entries.append(
        eclipseEntry(
            "  <classpathentry combineaccessrules=\"false\" kind=\"src\" path=\"/toktrak\">\n",
            true,
            true,
            String.join(":", TEST_EXPORTS)));
    for (Path jar : mainJars) entries.append(eclipseLibrary(jar, true, true));
    for (Path jar : testJars) entries.append(eclipseLibrary(jar, true, true));
    return eclipseClasspath(entries);
  }

  private static String eclipseBuildClasspath(Path refasterJar) {
    var entries = new StringBuilder();
    entries.append(eclipseSource("src", "bin/main", "tools/**", false));
    entries.append(eclipseSource("test", "bin/test", "tools/**", true));
    entries.append(eclipseJre(false));
    entries.append(eclipseLibrary(refasterJar, false, false));
    return eclipseClasspath(entries);
  }

  private static String eclipseClasspath(StringBuilder entries) {
    assert entries != null;
    return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<classpath>\n"
        + entries
        + "  <classpathentry kind=\"output\" path=\"bin\"/>\n</classpath>\n";
  }

  private static String eclipseSource(String path, String output, String including, boolean test) {
    assert path != null && !path.isBlank();
    assert output != null && !output.isBlank();
    String inclusion = including == null ? "" : " including=\"" + xml(including) + "\"";
    String start =
        "  <classpathentry"
            + inclusion
            + " kind=\"src\" path=\""
            + xml(path)
            + "\" output=\""
            + xml(output)
            + "\">\n";
    return eclipseEntry(start, false, test, null);
  }

  private static String eclipseJre(boolean module) {
    String start =
        "  <classpathentry kind=\"con\" path=\"org.eclipse.jdt.launching.JRE_CONTAINER/"
            + "org.eclipse.jdt.internal.debug.ui.launcher.StandardVMType/JavaSE-26\">\n";
    return eclipseEntry(start, module, false, null);
  }

  private static String eclipseLibrary(Path jar, boolean module, boolean test) {
    assert jar != null;
    String path = jar.toAbsolutePath().normalize().toString().replace('\\', '/');
    return eclipseEntry(
        "  <classpathentry kind=\"lib\" path=\"" + xml(path) + "\">\n", module, test, null);
  }

  private static String eclipseEntry(
      String start, boolean module, boolean test, String addExports) {
    assert start != null && !start.isBlank();
    if (!module && !test && addExports == null) {
      return start.stripTrailing().replace(">", "/>") + "\n";
    }
    var entry = new StringBuilder(start).append("    <attributes>\n");
    if (module) entry.append("      <attribute name=\"module\" value=\"true\"/>\n");
    if (test) entry.append("      <attribute name=\"test\" value=\"true\"/>\n");
    if (addExports != null) {
      entry
          .append("      <attribute name=\"add-exports\" value=\"")
          .append(xml(addExports))
          .append("\"/>\n");
    }
    return entry.append("    </attributes>\n  </classpathentry>\n").toString();
  }

  private static void writeEclipseProject(
      Path owner, Path project, String projectXml, String classpathXml) throws IOException {
    assert owner != null;
    assert project != null;
    assert projectXml != null;
    assert classpathXml != null;
    writeIdeFile(owner, project.resolve(".project"), projectXml);
    writeIdeFile(owner, project.resolve(".classpath"), classpathXml);
    writeIdeFile(
        owner,
        project.resolve(".settings/org.eclipse.core.resources.prefs"),
        "eclipse.preferences.version=1\nencoding/<project>=UTF-8\n");
    writeIdeFile(
        owner,
        project.resolve(".settings/org.eclipse.jdt.core.prefs"),
        """
        eclipse.preferences.version=1
        org.eclipse.jdt.core.compiler.codegen.targetPlatform=26
        org.eclipse.jdt.core.compiler.compliance=26
        org.eclipse.jdt.core.compiler.problem.enablePreviewFeatures=disabled
        org.eclipse.jdt.core.compiler.problem.forbiddenReference=warning
        org.eclipse.jdt.core.compiler.problem.reportPreviewFeatures=ignore
        org.eclipse.jdt.core.compiler.release=disabled
        org.eclipse.jdt.core.compiler.source=26
        """);
  }

  private static void writeIdeFile(Path owner, Path file, String content) throws IOException {
    assert content != null;
    writeIdeBytes(owner, file, content.getBytes(StandardCharsets.UTF_8));
  }

  private static void writeIdeBytes(Path owner, Path file, byte[] content) throws IOException {
    assert owner != null;
    assert file != null;
    assert content != null;
    Path normalizedOwner = owner.toAbsolutePath().normalize();
    Path normalizedFile = file.toAbsolutePath().normalize();
    if (!normalizedFile.startsWith(normalizedOwner)) {
      throw new IllegalArgumentException("IDE file escapes output directory");
    }
    if (content.length > IDE_FILE_BYTES_MAX) {
      throw new IllegalStateException("IDE file exceeds " + IDE_FILE_BYTES_MAX + " bytes");
    }
    Files.createDirectories(normalizedFile.getParent());
    Path temporary = normalizedFile.resolveSibling("." + normalizedFile.getFileName() + ".tmp");
    Files.deleteIfExists(temporary);
    try {
      Files.write(temporary, content, StandardOpenOption.CREATE_NEW);
      Files.move(
          temporary,
          normalizedFile,
          StandardCopyOption.ATOMIC_MOVE,
          StandardCopyOption.REPLACE_EXISTING);
    } finally {
      Files.deleteIfExists(temporary);
    }
    assert Files.size(normalizedFile) == content.length;
  }

  private static String xml(String value) {
    assert value != null;
    return value
        .replace("&", "&amp;")
        .replace("\"", "&quot;")
        .replace("<", "&lt;")
        .replace(">", "&gt;");
  }

  static AssetBundle assetBundleForTest(Path root) throws Exception {
    return assetBundle(root);
  }

  private static AssetBundle assetBundle(Path root) throws Exception {
    assert root != null;
    Path publicDirectory = root.resolve("public");
    if (Files.isSymbolicLink(publicDirectory)) throw symbolicAsset(publicDirectory);
    if (!Files.isDirectory(publicDirectory)) {
      throw new IllegalStateException(
          "runtime asset directory is missing: "
              + publicDirectory
              + "; create a regular public directory");
    }
    Path privateDirectory = root.resolve("private");
    if (Files.isSymbolicLink(privateDirectory)) throw symbolicAsset(privateDirectory);
    if (Files.exists(privateDirectory) && !Files.isDirectory(privateDirectory)) {
      throw new IllegalStateException(
          "invalid runtime asset directory: "
              + privateDirectory
              + "; replace it with a regular directory or remove it");
    }

    List<Path> assetPaths;
    try {
      assetPaths = treePaths(root, 100);
    } catch (IllegalStateException exception) {
      throw new IllegalStateException(
          "runtime asset tree exceeds 100 entries: "
              + root
              + "; remove empty directories or combine assets",
          exception);
    }
    var sourcePaths = new ArrayList<Path>();
    for (Path path : assetPaths) {
      if (Files.isSymbolicLink(path)) throw symbolicAsset(path);
      if (path.equals(root)) continue;
      Path relative = root.relativize(path);
      String scope = relative.getName(0).toString();
      if (!scope.equals("public") && !scope.equals("private")) {
        throw new IllegalStateException(
            "unknown top-level runtime asset entry: " + path + "; move it under public or private");
      }
      if (Files.isRegularFile(path)) {
        sourcePaths.add(path);
        if (sourcePaths.size() > ASSET_COUNT_MAX) {
          throw new IllegalStateException(
              "runtime assets exceed 64 files; remove or combine assets");
        }
      } else if (!Files.isDirectory(path)) {
        throw new IllegalStateException(
            "runtime asset is not a regular file or directory: "
                + path
                + "; replace it with a regular file or directory");
      }
    }
    sourcePaths.sort(Comparator.comparing(path -> assetKey(root, path)));

    long totalBytes = 0;
    var sourceSizes = new ArrayList<Long>();
    for (Path path : sourcePaths) {
      long size = Files.size(path);
      if (size > ASSET_BYTES_MAX) {
        throw new IllegalStateException(
            "runtime asset exceeds "
                + ASSET_BYTES_MAX
                + " bytes: "
                + path
                + "; optimize the asset below "
                + ASSET_BYTES_MAX
                + " bytes");
      }
      totalBytes = Math.addExact(totalBytes, size);
      if (totalBytes > ASSET_BYTES_TOTAL_MAX) {
        throw new IllegalStateException(
            "runtime assets exceed "
                + ASSET_BYTES_TOTAL_MAX
                + " total bytes; optimize or remove assets");
      }
      sourceSizes.add(size);
    }

    var assets = new ArrayList<AssetSource>();
    String previousKey = null;
    for (int sourceIndex = 0; sourceIndex < sourcePaths.size(); sourceIndex++) {
      Path path = sourcePaths.get(sourceIndex);
      Path relative = root.relativize(path);
      String scope = relative.getName(0).toString();
      String logicalPath =
          relative.subpath(1, relative.getNameCount()).toString().replace('\\', '/');
      if (!ASSET_PATH.matcher(logicalPath).matches()) {
        throw new IllegalStateException(
            "invalid runtime asset path: "
                + path
                + "; rename runtime assets using lowercase ASCII");
      }
      String key = scope + "\t" + logicalPath;
      if (key.equals(previousKey)) {
        throw new IllegalStateException(
            "duplicate runtime asset: " + path + "; remove the duplicate asset path");
      }
      previousKey = key;
      byte[] bytes = readAsset(path, sourceSizes.get(sourceIndex));
      String mediaType = validateAsset(path, bytes);
      String sha256 = sha256(bytes);
      assets.add(new AssetSource(scope, logicalPath, mediaType, sha256, bytes));
    }

    var indexText = new StringBuilder("toktrak-assets-v1\n");
    for (AssetSource asset : assets) {
      indexText
          .append(asset.scope())
          .append('\t')
          .append(asset.logicalPath())
          .append('\t')
          .append(asset.bytes().length)
          .append('\t')
          .append(asset.mediaType())
          .append('\t')
          .append(asset.sha256())
          .append('\n');
    }
    byte[] index = indexText.toString().getBytes(StandardCharsets.UTF_8);
    if (index.length > ASSET_INDEX_BYTES_MAX) {
      throw new IllegalStateException(
          "runtime asset index exceeds "
              + ASSET_INDEX_BYTES_MAX
              + " bytes; shorten or combine asset paths");
    }
    var digest = MessageDigest.getInstance("SHA-256");
    digest.update(index);
    for (AssetSource asset : assets) {
      update(digest, asset.scope() + "\n" + asset.logicalPath() + "\n");
      digest.update(asset.bytes());
    }
    return new AssetBundle(assets, index, HexFormat.of().formatHex(digest.digest()));
  }

  private static byte[] readAsset(Path path, long expectedBytes) throws IOException {
    assert path != null;
    assert expectedBytes >= 0 && expectedBytes <= ASSET_BYTES_MAX;
    int expectedLength = Math.toIntExact(expectedBytes);
    byte[] bytes;
    try (var input = Files.newInputStream(path)) {
      bytes = input.readNBytes(Math.addExact(expectedLength, 1));
    }
    if (bytes.length != expectedLength || Files.size(path) != expectedBytes) {
      throw new IllegalStateException(
          "runtime asset changed while reading: " + path + "; retry the build");
    }
    return bytes;
  }

  private static String assetKey(Path root, Path path) {
    assert root != null;
    assert path != null;
    return root.relativize(path).toString().replace('\\', '/');
  }

  private static IllegalStateException symbolicAsset(Path path) {
    assert path != null;
    return new IllegalStateException(
        "symbolic runtime asset: "
            + path
            + "; replace the symbolic runtime asset with a regular file or directory");
  }

  private static String validateAsset(Path path, byte[] bytes) throws Exception {
    assert path != null;
    assert bytes != null && bytes.length <= ASSET_BYTES_MAX;
    String name = path.getFileName().toString();
    if (name.endsWith(".css")) {
      validateUtf8(path, bytes);
      return "text/css; charset=utf-8";
    }
    if (name.endsWith(".js") || name.endsWith(".mjs")) {
      validateUtf8(path, bytes);
      return "text/javascript; charset=utf-8";
    }
    if (name.endsWith(".svg")) {
      String svg = validateUtf8(path, bytes);
      validateSvg(path, svg);
      return "image/svg+xml";
    }
    if (name.endsWith(".webp")) {
      if (bytes.length < 12 || !matches(bytes, 0, "RIFF") || !matches(bytes, 8, "WEBP")) {
        throw new IllegalStateException(
            "invalid WebP runtime asset: " + path + "; regenerate it as a valid WebP file");
      }
      return "image/webp";
    }
    if (name.endsWith(".avif")) {
      boolean brand = false;
      for (int offset = 8; offset + 4 <= Math.min(bytes.length, 32); offset++) {
        if (matches(bytes, offset, "avif") || matches(bytes, offset, "avis")) {
          brand = true;
          break;
        }
      }
      if (bytes.length < 12 || !matches(bytes, 4, "ftyp") || !brand) {
        throw new IllegalStateException(
            "invalid AVIF runtime asset: " + path + "; regenerate it as a valid AVIF file");
      }
      return "image/avif";
    }
    if (name.endsWith(".woff2")) {
      if (bytes.length < 4 || !matches(bytes, 0, "wOF2")) {
        throw new IllegalStateException(
            "invalid WOFF2 runtime asset: " + path + "; regenerate it as a valid WOFF2 file");
      }
      return "font/woff2";
    }
    if (name.matches(".*\\.(?:png|jpe?g|gif|bmp|tiff?)")) {
      throw new IllegalStateException(
          "unsupported runtime raster asset: "
              + path
              + "; convert it to optimized WebP or AVIF, or use SVG for vector artwork");
    }
    throw new IllegalStateException(
        "unsupported runtime asset: " + path + "; use CSS, JavaScript, SVG, WebP, AVIF, or WOFF2");
  }

  private static String validateUtf8(Path path, byte[] bytes) {
    assert path != null;
    assert bytes != null;
    try {
      return StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(bytes))
          .toString();
    } catch (CharacterCodingException exception) {
      throw new IllegalStateException(
          "invalid UTF-8 runtime asset: " + path + "; save the asset as valid UTF-8", exception);
    }
  }

  private static boolean matches(byte[] bytes, int offset, String signature) {
    assert bytes != null;
    assert offset >= 0;
    assert signature != null && signature.length() == 4;
    if (offset + signature.length() > bytes.length) return false;
    for (int index = 0; index < signature.length(); index++) {
      if (bytes[offset + index] != (byte) signature.charAt(index)) return false;
    }
    return true;
  }

  private static void validateSvg(Path path, String svg) {
    assert path != null;
    assert svg != null;
    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    try {
      factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
      factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
      factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
      factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
      factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
      factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
      factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
      factory.setXIncludeAware(false);
      factory.setExpandEntityReferences(false);
      factory.setNamespaceAware(true);
    } catch (Exception exception) {
      throw new IllegalStateException(
          "cannot secure SVG parser for runtime asset: "
              + path
              + "; use a supported JDK 26 XML parser",
          exception);
    }

    org.w3c.dom.Document document;
    try {
      var builder = factory.newDocumentBuilder();
      builder.setErrorHandler(new org.xml.sax.helpers.DefaultHandler());
      document = builder.parse(new ByteArrayInputStream(svg.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception exception) {
      throw unsafeSvg(path, exception);
    }
    Element root = document.getDocumentElement();
    if (root == null
        || !"svg".equals(root.getLocalName())
        || !"http://www.w3.org/2000/svg".equals(root.getNamespaceURI())) {
      throw unsafeSvg(path, null);
    }

    Set<String> deniedElements =
        Set.of(
            "script",
            "style",
            "foreignobject",
            "iframe",
            "object",
            "embed",
            "audio",
            "video",
            "image",
            "use");
    var pending = new ArrayDeque<Node>();
    pending.add(document);
    int inspected = 0;
    while (!pending.isEmpty()) {
      Node node = pending.removeFirst();
      inspected = Math.addExact(inspected, 1);
      if (inspected > 100_000) throw unsafeSvg(path, null);
      if (node.getNodeType() == Node.PROCESSING_INSTRUCTION_NODE
          || node.getNodeType() == Node.DOCUMENT_TYPE_NODE) {
        throw unsafeSvg(path, null);
      }
      if (node.getNodeType() == Node.ELEMENT_NODE) {
        String namespace = node.getNamespaceURI();
        String localName = node.getLocalName();
        if (!"http://www.w3.org/2000/svg".equals(namespace)
            || localName == null
            || deniedElements.contains(localName.toLowerCase(Locale.ROOT))) {
          throw unsafeSvg(path, null);
        }
        NamedNodeMap attributes = node.getAttributes();
        for (int index = 0; index < attributes.getLength(); index++) {
          inspected = Math.addExact(inspected, 1);
          if (inspected > 100_000) throw unsafeSvg(path, null);
          Node attribute = attributes.item(index);
          String attributeName = attribute.getNodeName();
          String lowerName = attributeName.toLowerCase(Locale.ROOT);
          String value = attribute.getNodeValue();
          boolean rootNamespace =
              node.equals(root)
                  && attributeName.equals("xmlns")
                  && value.equals("http://www.w3.org/2000/svg");
          if (attributeName.equals("xmlns") || attributeName.startsWith("xmlns:")) {
            if (!rootNamespace) throw unsafeSvg(path, null);
            continue;
          }
          if (lowerName.startsWith("on")
              || lowerName.equals("style")
              || lowerName.equals("href")
              || lowerName.equals("xlink:href")
              || lowerName.equals("src")) {
            throw unsafeSvg(path, null);
          }
          String lowerValue = value.toLowerCase(Locale.ROOT);
          if (lowerValue.contains("url(")
              || lowerValue.contains("@import")
              || lowerValue.contains("javascript:")
              || lowerValue.contains("data:")
              || lowerValue.contains("://")
              || lowerValue.contains("//")) {
            throw unsafeSvg(path, null);
          }
        }
      }
      for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling()) {
        pending.addLast(child);
        if (pending.size() > 100_000) throw unsafeSvg(path, null);
      }
    }
  }

  private static IllegalStateException unsafeSvg(Path path, Exception cause) {
    assert path != null;
    String message =
        "unsafe SVG runtime asset: "
            + path
            + "; remove scripts, external references, and unsupported SVG features";
    return cause == null
        ? new IllegalStateException(message)
        : new IllegalStateException(message, cause);
  }

  private static String sha256(byte[] bytes) throws Exception {
    assert bytes != null;
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
  }

  static void writeAssetsForTest(AssetBundle bundle, Path moduleRoot) throws Exception {
    writeAssets(bundle, moduleRoot);
  }

  private static void writeAssets(AssetBundle bundle, Path moduleRoot) throws Exception {
    assert bundle != null;
    assert moduleRoot != null;
    Path output = moduleRoot.resolve("assets");
    deleteTree(output);
    Files.createDirectories(output);
    for (AssetSource asset : bundle.assets()) {
      Path target = output.resolve(asset.scope()).resolve(asset.logicalPath()).normalize();
      if (!target.startsWith(output))
        throw new IllegalStateException("runtime asset escapes output");
      Files.createDirectories(target.getParent());
      Files.write(target, asset.bytes(), StandardOpenOption.CREATE_NEW);
    }
    Files.write(output.resolve("index.tsv"), bundle.index(), StandardOpenOption.CREATE_NEW);
    if (!assetsMatch(bundle, moduleRoot)) {
      throw new IllegalStateException(
          "copied runtime assets failed verification; run mise run clean and retry");
    }
  }

  static boolean assetsMatchForTest(AssetBundle bundle, Path moduleRoot) throws Exception {
    return assetsMatch(bundle, moduleRoot);
  }

  private static boolean assetsMatch(AssetBundle bundle, Path moduleRoot) throws Exception {
    assert bundle != null;
    assert moduleRoot != null;
    Path output = moduleRoot.resolve("assets");
    if (Files.isSymbolicLink(output) || !Files.isDirectory(output)) return false;
    var expectedPaths = new TreeSet<String>();
    expectedPaths.add("");
    expectedPaths.add("index.tsv");
    for (AssetSource asset : bundle.assets()) {
      Path relative = Path.of(asset.scope()).resolve(asset.logicalPath());
      expectedPaths.add(relative.toString().replace('\\', '/'));
      for (Path parent = relative.getParent(); parent != null; parent = parent.getParent()) {
        expectedPaths.add(parent.toString().replace('\\', '/'));
      }
    }
    List<Path> outputPaths;
    try {
      outputPaths = treePaths(output, Math.addExact(expectedPaths.size(), 1));
    } catch (IllegalStateException exception) {
      return false;
    }
    var actualPaths = new TreeSet<String>();
    for (Path path : outputPaths) {
      if (Files.isSymbolicLink(path) || (!Files.isRegularFile(path) && !Files.isDirectory(path))) {
        return false;
      }
      actualPaths.add(output.relativize(path).toString().replace('\\', '/'));
    }
    if (!actualPaths.equals(expectedPaths)) return false;
    byte[] expectedIndex = bundle.index();
    Path index = output.resolve("index.tsv");
    if (Files.size(index) != expectedIndex.length
        || !Arrays.equals(expectedIndex, Files.readAllBytes(index))) return false;
    for (AssetSource asset : bundle.assets()) {
      Path path = output.resolve(asset.scope()).resolve(asset.logicalPath());
      if (Files.size(path) != asset.bytes().length
          || !sha256(Files.readAllBytes(path)).equals(asset.sha256())) {
        return false;
      }
    }
    return true;
  }

  private static String dependencyFingerprint(Path dependencyFile) throws Exception {
    var digest = MessageDigest.getInstance("SHA-256");
    updateDigestFromFile(digest, dependencyFile);
    updateDigestFromFile(digest, ROOT.resolve("vendored/jresolve.jar"));
    return HexFormat.of().formatHex(digest.digest());
  }

  private static String compilationFingerprint(
      String name, List<Path> sources, List<Path> dependencyDirectories, List<String> arguments)
      throws Exception {
    assert name != null;
    assert sources != null;
    assert dependencyDirectories != null;
    assert arguments != null;
    requireCollectionSize(sources, "compilation sources");
    requireCollectionSize(dependencyDirectories, "compilation dependency directories");
    var digest = MessageDigest.getInstance("SHA-256");
    update(digest, "compile\n" + name + "\n" + platformFingerprint() + "\n");
    update(digest, argumentFileContent(arguments));
    for (Path source : sources) {
      update(digest, ROOT.relativize(source).toString() + "\n");
      if (Files.isDirectory(source)) {
        updateTree(digest, source, ".java");
      } else {
        updateDigestFromFile(digest, source);
      }
    }
    for (Path jar : jarPaths(dependencyDirectories)) {
      update(digest, jar.getFileName().toString() + "\n");
      updateDigestFromFile(digest, jar);
    }
    return HexFormat.of().formatHex(digest.digest());
  }

  static String applicationCompilationFingerprintForTest(
      List<Path> sources, List<Path> dependencyDirectories, List<String> arguments, Path assetRoot)
      throws Exception {
    return applicationCompilationFingerprint(
        sources, dependencyDirectories, arguments, assetBundle(assetRoot));
  }

  private static String applicationCompilationFingerprint(
      List<Path> sources,
      List<Path> dependencyDirectories,
      List<String> arguments,
      AssetBundle assetBundle)
      throws Exception {
    assert sources != null;
    assert dependencyDirectories != null;
    assert arguments != null;
    assert assetBundle != null;
    var fingerprintArguments = new ArrayList<>(arguments);
    fingerprintArguments.add("assets=" + assetBundle.fingerprint());
    return compilationFingerprint("modules", sources, dependencyDirectories, fingerprintArguments);
  }

  private static String testResultFingerprint(String compileFingerprint, List<String> arguments)
      throws Exception {
    assert compileFingerprint != null;
    assert arguments != null;
    var digest = MessageDigest.getInstance("SHA-256");
    update(digest, "test\n" + platformFingerprint() + "\n" + compileFingerprint + "\n");
    update(digest, argumentFileContent(arguments));
    return HexFormat.of().formatHex(digest.digest());
  }

  private static String platformFingerprint() {
    return System.getProperty("java.runtime.version")
        + "\n"
        + System.getProperty("java.vendor")
        + "\n"
        + System.getProperty("os.name")
        + "\n"
        + System.getProperty("os.arch");
  }

  private static void formatCommand(List<String> paths) throws Exception {
    if (paths.equals(List.of("--help"))) {
      System.out.println("usage: mise run fmt [Java files or directories...]");
      return;
    }
    format(true, paths);
  }

  private static void format(boolean replace, List<String> paths) throws Exception {
    assert paths != null;
    List<String> sourcePaths = javaSourcePaths(paths).stream().map(Path::toString).toList();
    if (sourcePaths.isEmpty()) return;
    for (List<String> arguments : formatterArguments(replace, sourcePaths)) {
      runArguments(
          googleJavaFormatExecutable(),
          replace ? "format Java sources" : "check Java source format",
          arguments);
    }
  }

  static List<List<String>> formatterArgumentsForTest(List<String> sourcePaths) {
    return formatterArguments(false, sourcePaths);
  }

  private static List<List<String>> formatterArguments(boolean replace, List<String> sourcePaths) {
    assert sourcePaths != null;
    if (sourcePaths.isEmpty()) throw new IllegalArgumentException("sourcePaths are empty");
    List<String> options =
        replace ? List.of("--replace") : List.of("--dry-run", "--set-exit-if-changed");
    String executable = googleJavaFormatExecutable();
    int commandBytes = commandBytes(executable, options);
    int files = 0;
    var batch = new ArrayList<>(options);
    var batches = new ArrayList<List<String>>();
    for (String sourcePath : sourcePaths) {
      int sourceBytes = argumentBytes(sourcePath);
      if (files > 0
          && (files >= FORMAT_BATCH_FILES_MAX
              || commandBytes + sourceBytes + 1 > COMMAND_BYTES_MAX)) {
        batches.add(List.copyOf(batch));
        batch = new ArrayList<>(options);
        commandBytes = commandBytes(executable, options);
        files = 0;
      }
      commandBytes = Math.addExact(commandBytes, Math.addExact(sourceBytes, 1));
      if (commandBytes > COMMAND_BYTES_MAX) {
        throw new IllegalStateException(
            "formatter source path exceeds command ceiling: " + sourcePath);
      }
      batch.add(sourcePath);
      files = Math.addExact(files, 1);
    }
    batches.add(List.copyOf(batch));
    assert batches.size() <= sourcePaths.size();
    return List.copyOf(batches);
  }

  private static void check() throws Exception {
    format(false, List.of());
    validateRepositorySkills();
    compile();
  }

  private static void validateRepositorySkills() throws IOException {
    for (Path skill : REPOSITORY_SKILLS) {
      String name = skill.getFileName().toString();
      Path instructions = skill.resolve("SKILL.md");
      if (!Files.isRegularFile(instructions)) {
        throw new IllegalStateException("skill instructions are missing: " + instructions);
      }
      String source = Files.readString(instructions, StandardCharsets.UTF_8);
      if (!source.startsWith("---\nname: " + name + "\ndescription: \"")
          || !source.contains("\n---\n")
          || source.length() > 16 * 1024) {
        throw new IllegalStateException("skill frontmatter is invalid: " + instructions);
      }
      for (Path path : treePaths(skill, 32)) {
        if (Files.isSymbolicLink(path)) {
          throw new IllegalStateException("skill contains symbolic link: " + path);
        }
      }
    }
    Path mustacheReference = REPOSITORY_SKILLS.get(0).resolve("references/spec.md");
    Path jstachioReference = REPOSITORY_SKILLS.get(1).resolve("references/integration.md");
    requireFile(mustacheReference);
    requireFile(jstachioReference);
    String references =
        Files.readString(mustacheReference, StandardCharsets.UTF_8)
            + Files.readString(jstachioReference, StandardCharsets.UTF_8);
    for (String required :
        List.of(
            "https://mustache.github.io/mustache.5.html",
            "https://github.com/mustache/spec",
            "https://jstach.io/doc/jstachio/1.3.7/apidocs/")) {
      if (!references.contains(required)) {
        throw new IllegalStateException("skill official reference is missing: " + required);
      }
    }
  }

  static List<Path> templateSourcesForTest(Path root) throws IOException {
    return templateSources(root);
  }

  private static List<Path> templateSources(Path root) throws IOException {
    if (!Files.isDirectory(root) || Files.isSymbolicLink(root)) {
      throw new IllegalStateException("template root is not a regular directory: " + root);
    }
    var templates = new ArrayList<Path>();
    int totalBytes = 0;
    for (Path path : treePaths(root, TEMPLATE_COUNT_MAX * 4)) {
      if (path.equals(root) || Files.isDirectory(path)) continue;
      if (Files.isSymbolicLink(path) || !Files.isRegularFile(path)) {
        throw new IllegalStateException("template is not a regular file: " + path);
      }
      String relative = root.relativize(path).toString().replace('\\', '/');
      if (!TEMPLATE_PATH.matcher(relative).matches()) {
        throw new IllegalStateException(
            "template path must be lowercase relative .mustache: " + relative);
      }
      if (templates.size() >= TEMPLATE_COUNT_MAX) {
        throw new IllegalStateException("templates exceed " + TEMPLATE_COUNT_MAX + " files");
      }
      byte[] bytes = Files.readAllBytes(path);
      if (bytes.length > TEMPLATE_BYTES_MAX) {
        throw new IllegalStateException(
            "template exceeds " + TEMPLATE_BYTES_MAX + " bytes: " + relative);
      }
      totalBytes = Math.addExact(totalBytes, bytes.length);
      if (totalBytes > TEMPLATE_BYTES_TOTAL_MAX) {
        throw new IllegalStateException(
            "templates exceed " + TEMPLATE_BYTES_TOTAL_MAX + " total bytes");
      }
      String source;
      try {
        source =
            StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString();
      } catch (CharacterCodingException exception) {
        throw new IllegalStateException("template is not strict UTF-8: " + relative, exception);
      }
      if (source.startsWith("\uFEFF")) {
        throw new IllegalStateException("template has a UTF-8 BOM: " + relative);
      }
      if (source.indexOf('\r') >= 0) {
        throw new IllegalStateException("template must use LF line endings: " + relative);
      }
      for (String forbidden : List.of("{{{", "{{&", "{{=", "{{>")) {
        if (source.contains(forbidden)) {
          throw new IllegalStateException(
              "template contains forbidden Mustache syntax " + forbidden + ": " + relative);
        }
      }
      templates.add(path);
    }
    templates.sort(Comparator.comparing(path -> root.relativize(path).toString()));
    return List.copyOf(templates);
  }

  static void validateTemplateLayoutsForTest(Path root) throws IOException {
    validateTemplateLayouts(root, templateSources(root));
  }

  private static void validateTemplateLayouts(Path root, List<Path> templates) throws IOException {
    var sources = new java.util.TreeMap<String, String>();
    for (Path template : templates) {
      sources.put(
          root.relativize(template).toString().replace('\\', '/'),
          Files.readString(template, StandardCharsets.UTF_8));
    }
    String base = sources.get("base.mustache");
    if (base == null) throw new IllegalStateException("base.mustache is required");
    requireLayoutTokens(base, "base.mustache", false);
    for (var entry : sources.entrySet()) {
      if (!entry.getKey().equals("base.mustache")) {
        requireLayoutTokens(entry.getValue(), entry.getKey(), true);
      }
    }
  }

  private static void requireLayoutTokens(String source, String path, boolean child) {
    String remaining = source;
    for (String token : List.of("{{$content}}", "{{/content}}")) {
      if (tokenCount(remaining, token) != 1) {
        throw new IllegalStateException("template requires one " + token + ": " + path);
      }
      remaining = remaining.replace(token, "");
    }
    if (child) {
      for (String token : List.of("{{<base.mustache}}", "{{/base.mustache}}")) {
        if (tokenCount(remaining, token) != 1) {
          throw new IllegalStateException("template requires one " + token + ": " + path);
        }
        remaining = remaining.replace(token, "");
      }
    }
    if (remaining.contains("{{<")
        || remaining.contains("{{$")
        || (!child && remaining.contains("{{/base.mustache}}"))) {
      throw new IllegalStateException("template contains unsupported layout syntax: " + path);
    }
  }

  private static int tokenCount(String source, String token) {
    int count = 0;
    for (int offset = 0; (offset = source.indexOf(token, offset)) >= 0; offset += token.length()) {
      count = Math.addExact(count, 1);
    }
    return count;
  }

  private static void validateTemplateModels(List<Path> templates) throws IOException {
    var expected = new TreeSet<String>();
    for (Path template : templates) {
      expected.add(TEMPLATE_SOURCES.relativize(template).toString().replace('\\', '/'));
    }
    if (!expected.remove("base.mustache")) {
      throw new IllegalStateException("base.mustache is required");
    }
    List<Path> sources =
        treePaths(APP_SOURCES, TREE_ENTRIES_MAX).stream()
            .filter(Files::isRegularFile)
            .filter(path -> path.getFileName().toString().endsWith(".java"))
            .toList();
    var compiler = ToolProvider.getSystemJavaCompiler();
    if (compiler == null) throw new IllegalStateException("system Java compiler is unavailable");
    var actual = new TreeSet<String>();
    try (var files = compiler.getStandardFileManager(null, Locale.ROOT, StandardCharsets.UTF_8)) {
      var units = files.getJavaFileObjectsFromPaths(sources);
      var task =
          (JavacTask) compiler.getTask(null, files, null, List.of("-proc:none"), null, units);
      for (var unit : task.parse()) {
        new TreeScanner<Void, Void>() {
          @Override
          public Void visitAnnotation(AnnotationTree annotation, Void unused) {
            String type = annotation.getAnnotationType().toString();
            if (!type.equals("JStache") && !type.endsWith(".JStache")) {
              return super.visitAnnotation(annotation, null);
            }
            if (annotation.getArguments().size() != 1
                || !(annotation.getArguments().getFirst() instanceof AssignmentTree assignment)
                || !assignment.getVariable().toString().equals("path")
                || !(assignment.getExpression() instanceof LiteralTree literal)
                || !(literal.getValue() instanceof String path)
                || !TEMPLATE_PATH.matcher(path).matches()) {
              throw new IllegalStateException("@JStache requires one canonical literal path");
            }
            if (!actual.add(path)) {
              throw new IllegalStateException("duplicate @JStache template path: " + path);
            }
            return super.visitAnnotation(annotation, null);
          }
        }.scan(unit, null);
      }
    }
    if (!actual.equals(expected)) {
      throw new IllegalStateException("@JStache paths do not match templates: " + actual);
    }
  }

  private static void copyTemplates(Path root, List<Path> templates, Path destination)
      throws IOException {
    Files.createDirectories(destination);
    for (Path template : templates) {
      Path relative = root.relativize(template);
      Path target = destination.resolve(relative);
      Files.createDirectories(target.getParent());
      Files.copy(template, target);
    }
  }

  private static void validateJstachioProcessor() throws IOException {
    List<Path> processors =
        jarPaths(List.of(BUILD_DEPS)).stream()
            .filter(path -> path.getFileName().toString().equals("io.jstach.apt.jar"))
            .toList();
    if (processors.size() != 1) {
      throw new IllegalStateException("expected one JStachio processor JAR: " + processors);
    }
    Path processor = processors.getFirst();
    try (var jar = new JarFile(processor.toFile())) {
      String release = jar.getManifest().getMainAttributes().getValue("Implementation-Version");
      if (!"1.3.7".equals(release)) {
        throw new IllegalStateException("JStachio processor release must be 1.3.7: " + release);
      }
    }
    String moduleVersion =
        ModuleFinder.of(processor)
            .find("io.jstach.apt")
            .flatMap(reference -> reference.descriptor().rawVersion())
            .orElse("");
    if (!moduleVersion.equals("1.4.0-SNAPSHOT")) {
      throw new IllegalStateException(
          "JStachio 1.3.7 processor module version changed: " + moduleVersion);
    }
  }

  private static void validateGeneratedRendering(Path staging) throws IOException {
    Path generated = staging.resolve("target/generated-sources/annotations/toktrak/toktrak/http");
    Path classes = staging.resolve("target/classes/toktrak/toktrak/http");
    for (String renderer :
        List.of("HomeViewRenderer", "TokenListViewRenderer", "CreatedTokenViewRenderer")) {
      Path source = generated.resolve(renderer + ".java");
      requireFile(source);
      requireFile(classes.resolve(renderer + ".class"));
      String generatedSource = Files.readString(source, StandardCharsets.UTF_8);
      if (!generatedSource.contains("Template.EncodedTemplate<")
          || !generatedSource.contains("StandardCharsets.UTF_8")) {
        throw new IllegalStateException("renderer is not pre-encoded UTF-8: " + renderer);
      }
    }
    Path app = staging.resolve("target/classes/toktrak");
    for (Path path : treePaths(app, TREE_ENTRIES_MAX)) {
      String relative = app.relativize(path).toString().replace('\\', '/');
      if (relative.endsWith(".mustache")
          || relative.endsWith(".java")
          || relative.equals("META-INF/services/io.jstach.jstachio.spi.TemplateProvider")) {
        throw new IllegalStateException("forbidden runtime template output: " + relative);
      }
    }
  }

  private static void compile() throws Exception {
    deps();
    compileRefaster();
    AssetBundle assetBundle = assetBundle(ASSET_SOURCES);
    List<Path> templates = templateSources(TEMPLATE_SOURCES);
    validateTemplateLayouts(TEMPLATE_SOURCES, templates);
    validateTemplateModels(templates);
    validateJstachioProcessor();

    List<String> appArguments = new ArrayList<>();
    appArguments.add("-Xlint:all");
    appArguments.add("-Werror");
    appArguments.add("-g");
    addErrorProne(appArguments);
    appArguments.add("-s");
    appArguments.add(GENERATED_SOURCES.toString());
    appArguments.add("-Ajstache.resourcesPath=" + TEMPLATE_INPUT);
    appArguments.add("-Ajstache.incremental=true");
    appArguments.add("-Ajstache.claim_annotations=true");
    appArguments.add("--module-source-path");
    appArguments.add("toktrak=" + APP_SOURCES);
    appArguments.add("--module-path");
    appArguments.add(modulePath(List.of(MAIN_DEPS)));
    appArguments.add("-d");
    appArguments.add(MODULE_CLASSES.toString());
    appArguments.add("--module");
    appArguments.add("toktrak");

    List<String> testArguments = new ArrayList<>();
    testArguments.add("-Xlint:all");
    testArguments.add("-Werror");
    testArguments.add("-g");
    addErrorProne(testArguments);
    testArguments.add("-proc:none");
    testArguments.add("--module-source-path");
    testArguments.add("toktrak.tests=" + JUNIT_TEST_SOURCES);
    testArguments.add("--module-path");
    testArguments.add(modulePath(List.of(MAIN_DEPS, TEST_DEPS, MODULE_CLASSES)));
    testArguments.add("--class-path");
    testArguments.add(modulePath(List.of(SNAPSHOT_DEPS)));
    testArguments.add("--add-reads");
    testArguments.add("toktrak.tests=ALL-UNNAMED");
    addExports(testArguments);
    testArguments.add("-d");
    testArguments.add(MODULE_CLASSES.toString());
    testArguments.add("--module");
    testArguments.add("toktrak.tests");

    Path argFile = writeArgFile("compile-toktrak-module", appArguments);
    List<String> fingerprintArguments = new ArrayList<>(appArguments);
    fingerprintArguments.addAll(testArguments);
    fingerprintArguments.addAll(errorProneJvmArguments());
    String fingerprint =
        applicationCompilationFingerprint(
            List.of(APP_SOURCES, JUNIT_TEST_SOURCES, ERROR_PRONE_CONFIG),
            List.of(MAIN_DEPS, TEST_DEPS, SNAPSHOT_DEPS, BUILD_DEPS),
            fingerprintArguments,
            assetBundle);
    long started = System.nanoTime();
    if (artifactMatches(MODULES, fingerprint) && assetsMatch(assetBundle, APP_MODULE)) {
      printCached(javacExecutable(), argFile, started);
      return;
    }
    rebuildArtifact(
        MODULES,
        fingerprint,
        staging -> {
          copyTemplates(TEMPLATE_SOURCES, templates, staging.resolve("target/template-input"));
          Path stagingClasses = staging.resolve("target/classes");
          Path stagingApp = stagingClasses.resolve("toktrak");
          copyTemplates(TEMPLATE_SOURCES, templates, stagingApp);
          runJavacArgFile(
              writeArgFile(
                  "compile-toktrak-module", remapArguments(appArguments, MODULES, staging)));
          for (Path template : templates) {
            Files.delete(stagingApp.resolve(TEMPLATE_SOURCES.relativize(template)));
          }
          runJavacArgFile(
              writeArgFile(
                  "compile-toktrak-test-module", remapArguments(testArguments, MODULES, staging)));
          Path stagingTests = stagingClasses.resolve("toktrak.tests");
          writeAssets(assetBundle, stagingApp);
          requireFile(stagingApp.resolve("module-info.class"));
          requireFile(stagingTests.resolve("module-info.class"));
          if (!assetsMatch(assetBundle, stagingApp)) {
            throw new IllegalStateException("compiled runtime assets are incomplete");
          }
          validateGeneratedRendering(staging);
        });
  }

  private static void compileRefaster() throws Exception {
    var arguments = new ArrayList<String>();
    arguments.add("-Xlint:all");
    arguments.add("-Werror");
    arguments.add("-cp");
    arguments.add(refasterJar().toString());
    arguments.add("-d");
    arguments.add(REFASTER_CLASSES.toString());
    arguments.add("-Xplugin:RefasterRuleCompiler --out " + REFASTER_RULE);
    arguments.add(REFASTER_SOURCE.toString());
    Path argFile = writeArgFile("compile-refaster-rules", arguments);
    List<String> fingerprintArguments = new ArrayList<>(arguments);
    fingerprintArguments.addAll(errorProneJvmArguments());
    String fingerprint =
        compilationFingerprint(
            "refaster", List.of(REFASTER_SOURCE), List.of(REFASTER_DEPS), fingerprintArguments);
    long started = System.nanoTime();
    if (artifactMatches(REFASTER_COMPILER, fingerprint)) {
      printCached(javacExecutable(), argFile, started);
      return;
    }
    rebuildArtifact(
        REFASTER_COMPILER,
        fingerprint,
        staging -> {
          Path stagingArgFile =
              writeArgFile(
                  "compile-refaster-rules", remapArguments(arguments, REFASTER_COMPILER, staging));
          runJavacArgFile(stagingArgFile);
          Path rule = staging.resolve("toktrak.refaster");
          if (!Files.isRegularFile(rule) || Files.size(rule) == 0) {
            throw new IllegalStateException("Refaster rule was not generated");
          }
          requireFile(staging.resolve("classes/tools/refaster/Rules.class"));
        });
  }

  private static Path refasterJar() throws IOException {
    List<Path> matches =
        jarPaths(List.of(REFASTER_DEPS)).stream()
            .filter(path -> path.getFileName().toString().startsWith("error_prone_refaster-"))
            .toList();
    if (matches.size() != 1) {
      throw new IllegalStateException("expected one Refaster compiler JAR: " + matches);
    }
    return matches.getFirst();
  }

  private static void testCommand(List<String> paths) throws Exception {
    if (paths.equals(List.of("--help"))) {
      System.out.println("usage: mise run test [test files or directories...]");
      return;
    }
    test(paths);
  }

  private static void test(List<String> paths) throws Exception {
    assert paths != null;
    TestSelection selection = testSelection(paths);
    if (!selection.classNames().isEmpty()) compile();
    runTests(selection);
  }

  private static void refactor() throws Exception {
    compile();
    applyRefasterToModules();
    applyRefasterToBuildTool();
    format(true, List.of());
  }

  private static void applyRefasterToModules() throws Exception {
    var arguments = new ArrayList<String>();
    arguments.addAll(refasterPatchArguments());
    arguments.add("-proc:none");
    addModuleSourcePaths(arguments);
    arguments.add("--module-path");
    arguments.add(modulePath(List.of(MAIN_DEPS, TEST_DEPS, MODULE_CLASSES)));
    arguments.add("--patch-module");
    arguments.add("toktrak=" + APP_MODULE);
    arguments.add("--class-path");
    arguments.add(modulePath(List.of(SNAPSHOT_DEPS)));
    arguments.add("--add-reads");
    arguments.add("toktrak.tests=ALL-UNNAMED");
    addExports(arguments);
    arguments.add("-d");
    arguments.add(REFASTER_APPLY_MODULES.toString());
    arguments.add("--module");
    arguments.add("toktrak,toktrak.tests");
    rebuildArtifact(
        REFASTER_APPLY_MODULES,
        "refaster-application",
        staging ->
            runJavacArgFile(
                writeArgFile(
                    "apply-refaster-to-modules",
                    remapArguments(arguments, REFASTER_APPLY_MODULES, staging))));
  }

  private static void applyRefasterToBuildTool() throws Exception {
    var arguments = new ArrayList<String>();
    arguments.addAll(refasterPatchArguments());
    arguments.add("-d");
    arguments.add(REFASTER_APPLY_BUILD.toString());
    arguments.add(ROOT.resolve("tools/Build.java").toString());
    arguments.add(BUILD_TEST_SOURCE.toString());
    rebuildArtifact(
        REFASTER_APPLY_BUILD,
        "refaster-application",
        staging ->
            runJavacArgFile(
                writeArgFile(
                    "apply-refaster-to-build-tool",
                    remapArguments(arguments, REFASTER_APPLY_BUILD, staging))));
  }

  private static void ci() throws Exception {
    refactor();
    if (isGitDirty(ROOT)) {
      throw new IllegalStateException("working tree is dirty after refactor");
    }
    verify();
  }

  static boolean isGitDirtyForTest(Path repository) throws Exception {
    return isGitDirty(repository);
  }

  private static boolean isGitDirty(Path repository) throws Exception {
    assert repository != null;
    return !runTool(
            "git",
            "inspect working tree",
            List.of("status", "--porcelain=v1", "--untracked-files=all"),
            repository,
            PROCESS_TIMEOUT)
        .output()
        .isEmpty();
  }

  private static void terminateFromReader(
      Process process, AtomicReference<IOException> readFailure) {
    assert process != null;
    assert readFailure != null;
    try {
      terminateForcibly(process, PROCESS_KILL_TIMEOUT);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      readFailure.compareAndSet(
          null, new IOException("interrupted while terminating process output", exception));
    } catch (RuntimeException exception) {
      readFailure.compareAndSet(
          null, new IOException("cannot terminate process output", exception));
    }
  }

  private static void verify() throws Exception {
    check();
    runTests(testSelection(List.of()));
  }

  private static void coverage() throws Exception {
    compile();
    ensureCoverageDependencies();
    TestSelection allTests = testSelection(List.of());
    rebuildArtifact(
        COVERAGE,
        "coverage",
        staging -> {
          runTests(new TestSelection(false, allTests.classNames()), jacocoAgentArgument(staging));
          Path executionData = staging.resolve("jacoco.exec");
          if (!Files.isRegularFile(executionData) || Files.size(executionData) == 0) {
            throw new IllegalStateException("JaCoCo execution data is missing or empty");
          }
          Path authoredClasses = staging.resolve("authored-classes");
          copyAuthoredClasses(authoredClasses);
          runArgFile(
              javaExecutable(),
              "generate-jacoco-coverage-report",
              List.of(
                  "-ea",
                  "-jar",
                  coverageJar("org.jacoco.cli-").toString(),
                  "report",
                  executionData.toString(),
                  "--classfiles",
                  authoredClasses.toString(),
                  "--sourcefiles",
                  APP_SOURCES.toString(),
                  "--html",
                  staging.resolve("report").toString(),
                  "--xml",
                  staging.resolve("jacoco.xml").toString(),
                  "--csv",
                  staging.resolve("jacoco.csv").toString(),
                  "--name",
                  "TokTrak"));
          requireFile(staging.resolve("report/index.html"));
          enforceCoverage(staging.resolve("jacoco.csv"));
        });
  }

  private static void copyAuthoredClasses(Path destination) throws IOException {
    Files.createDirectories(destination);
    for (Path source : treePaths(APP_SOURCES, TREE_ENTRIES_MAX)) {
      String name = source.getFileName().toString();
      if (!Files.isRegularFile(source)
          || !name.endsWith(".java")
          || name.equals("module-info.java")
          || name.equals("package-info.java")) continue;
      Path relative = APP_SOURCES.relativize(source);
      Path classDirectory = APP_MODULE.resolve(relative).getParent();
      if (!Files.isDirectory(classDirectory)) {
        throw new IllegalStateException("authored class directory is missing: " + source);
      }
      String base = name.substring(0, name.length() - ".java".length());
      for (Path compiled : directoryEntries(classDirectory, TREE_ENTRIES_MAX)) {
        String compiledName = compiled.getFileName().toString();
        if (!compiledName.equals(base + ".class")
            && !(compiledName.startsWith(base + "$") && compiledName.endsWith(".class"))) continue;
        Path targetDirectory = destination.resolve(APP_MODULE.relativize(classDirectory));
        Files.createDirectories(targetDirectory);
        Files.copy(compiled, targetDirectory.resolve(compiledName));
      }
    }
  }

  private static void enforceCoverage(Path csv) throws IOException {
    List<String> lines = Files.readAllLines(csv, StandardCharsets.UTF_8);
    if (lines.size() < 2 || lines.size() > TREE_ENTRIES_MAX) {
      throw new IllegalStateException(
          "JaCoCo CSV must contain 1.." + (TREE_ENTRIES_MAX - 1) + " classes");
    }
    Map<String, Integer> columns = csvColumns(lines.getFirst());
    var total = new CoverageCounts();
    var packages = new java.util.HashMap<String, CoverageCounts>();
    for (String line : lines.subList(1, lines.size())) {
      String[] values = line.split(",", -1);
      var counts =
          new CoverageCounts(
              csvLong(values, columns, "INSTRUCTION_MISSED"),
              csvLong(values, columns, "INSTRUCTION_COVERED"),
              csvLong(values, columns, "BRANCH_MISSED"),
              csvLong(values, columns, "BRANCH_COVERED"));
      total.add(counts);
      Integer packageIndex = columns.get("PACKAGE");
      if (packageIndex == null || packageIndex >= values.length || values[packageIndex].isBlank()) {
        throw new IllegalStateException("JaCoCo CSV column missing: PACKAGE");
      }
      packages.computeIfAbsent(values[packageIndex], _ -> new CoverageCounts()).add(counts);
    }
    requireCoverage(
        "instruction",
        total.instructionsCovered,
        total.instructionsMissed,
        COVERAGE_INSTRUCTION_PERCENT_MIN);
    requireCoverage(
        "branch", total.branchesCovered, total.branchesMissed, COVERAGE_BRANCH_PERCENT_MIN);
    for (Map.Entry<String, CoverageMinimum> entry : COVERAGE_PACKAGE_MINIMUMS.entrySet()) {
      CoverageCounts counts = packages.get(entry.getKey());
      if (counts == null)
        throw new IllegalStateException("JaCoCo package missing: " + entry.getKey());
      CoverageMinimum minimum = entry.getValue();
      requireCoverage(
          entry.getKey() + " instruction",
          counts.instructionsCovered,
          counts.instructionsMissed,
          minimum.instructionPercent);
      requireCoverage(
          entry.getKey() + " branch",
          counts.branchesCovered,
          counts.branchesMissed,
          minimum.branchPercent);
    }
    long instructionsCovered = total.instructionsCovered;
    long instructionsMissed = total.instructionsMissed;
    long branchesCovered = total.branchesCovered;
    long branchesMissed = total.branchesMissed;
    appendGitHubSummary(
        "## JaCoCo coverage\n\n| Counter | Covered | Percentage |\n| --- | ---: | ---: |\n"
            + coverageTableRow("Instructions", instructionsCovered, instructionsMissed)
            + coverageTableRow("Branches", branchesCovered, branchesMissed)
            + "\nDownload the `coverage-report` artifact and open `report/index.html`.\n");
  }

  private static Map<String, Integer> csvColumns(String header) {
    String[] names = header.split(",", -1);
    var columns = new java.util.HashMap<String, Integer>();
    for (int index = 0; index < names.length; index++) columns.put(names[index], index);
    return Map.copyOf(columns);
  }

  private static long csvLong(String[] values, Map<String, Integer> columns, String name) {
    Integer index = columns.get(name);
    if (index == null || index >= values.length) {
      throw new IllegalStateException("JaCoCo CSV column missing: " + name);
    }
    try {
      return Long.parseLong(values[index]);
    } catch (NumberFormatException exception) {
      throw new IllegalStateException("JaCoCo CSV value is not an integer: " + name, exception);
    }
  }

  static void requireCoverageForTest(String name, long covered, long missed, long percentMin) {
    requireCoverage(name, covered, missed, percentMin);
  }

  private static String coverageTableRow(String name, long covered, long missed) {
    long total = Math.addExact(covered, missed);
    long permille = Math.floorDiv(Math.multiplyExact(covered, 1_000), total);
    return "| "
        + name
        + " | "
        + covered
        + "/"
        + total
        + " | "
        + (permille / 10)
        + "."
        + (permille % 10)
        + "% |\n";
  }

  private static void appendGitHubSummary(String markdown) throws IOException {
    assert markdown != null && !markdown.isBlank();
    String configured = System.getenv("GITHUB_STEP_SUMMARY");
    if (configured == null) return;
    Path summary = Path.of(configured).toAbsolutePath().normalize();
    if (Files.isSymbolicLink(summary) || !Files.isRegularFile(summary)) {
      throw new IllegalStateException("GITHUB_STEP_SUMMARY is not a regular file: " + summary);
    }
    if (Files.size(summary) > 1024 * 1024) {
      throw new IllegalStateException("GITHUB_STEP_SUMMARY exceeds 1048576 bytes: " + summary);
    }
    Files.writeString(summary, markdown, StandardCharsets.UTF_8, StandardOpenOption.APPEND);
  }

  private static void requireCoverage(String name, long covered, long missed, long percentMin) {
    assert name != null && !name.isBlank();
    if (covered < 0 || missed < 0 || percentMin < 0 || percentMin > 100) {
      throw new IllegalArgumentException("invalid coverage count or minimum");
    }
    long total = Math.addExact(covered, missed);
    if (total == 0 || Math.multiplyExact(covered, 100) < Math.multiplyExact(total, percentMin)) {
      throw new IllegalStateException(
          name + " coverage is below " + percentMin + "%: " + covered + "/" + total);
    }
    System.out.println(
        name + " coverage: " + covered + "/" + total + " (minimum " + percentMin + "%)");
  }

  private record CoverageMinimum(long instructionPercent, long branchPercent) {}

  private static final class CoverageCounts {
    private long instructionsMissed;
    private long instructionsCovered;
    private long branchesMissed;
    private long branchesCovered;

    private CoverageCounts() {}

    private CoverageCounts(
        long instructionsMissed,
        long instructionsCovered,
        long branchesMissed,
        long branchesCovered) {
      this.instructionsMissed = instructionsMissed;
      this.instructionsCovered = instructionsCovered;
      this.branchesMissed = branchesMissed;
      this.branchesCovered = branchesCovered;
    }

    private void add(CoverageCounts counts) {
      instructionsMissed = Math.addExact(instructionsMissed, counts.instructionsMissed);
      instructionsCovered = Math.addExact(instructionsCovered, counts.instructionsCovered);
      branchesMissed = Math.addExact(branchesMissed, counts.branchesMissed);
      branchesCovered = Math.addExact(branchesCovered, counts.branchesCovered);
    }
  }

  private static String jacocoAgentArgument(Path output) throws IOException {
    assert output != null;
    return "-javaagent:"
        + coverageJar("org.jacoco.agent-")
        + "=destfile="
        + output.resolve("jacoco.exec")
        + ",append=true,includes=toktrak.*";
  }

  private static Path coverageJar(String prefix) throws IOException {
    List<Path> matches =
        jarPaths(List.of(COVERAGE_DEPS)).stream()
            .filter(path -> path.getFileName().toString().startsWith(prefix))
            .filter(
                path ->
                    path.getFileName().toString().contains("nodeps") || prefix.contains("agent"))
            .toList();
    if (matches.size() != 1) {
      throw new IllegalStateException("expected one coverage JAR with prefix " + prefix);
    }
    return matches.getFirst();
  }

  private static void runTests(TestSelection selection) throws Exception {
    runTests(selection, null);
  }

  private static void runTests(TestSelection selection, String javaAgent) throws Exception {
    assert selection != null;
    if (selection.buildTool()) testBuildTool();
    if (selection.classNames().isEmpty()) return;
    Path runtime = ensureTestRuntime();
    List<String> arguments = new ArrayList<>();
    arguments.add("-ea");
    if (javaAgent != null) arguments.add(javaAgent);
    arguments.add("--module-path");
    arguments.add(MODULE_CLASSES.toString());
    arguments.add("--add-modules");
    arguments.add("toktrak.tests,toktrak," + String.join(",", moduleNames(TEST_DEPS)));
    arguments.add("--class-path");
    arguments.add(modulePath(List.of(SNAPSHOT_DEPS)));
    arguments.add("--add-reads");
    arguments.add("toktrak.tests=ALL-UNNAMED");
    addExports(arguments);
    arguments.add("-m");
    arguments.add("toktrak.tests/toktrak.tests.TestLauncher");
    runTestGroup(runtime, arguments, selection.classNames(), "--unit", javaAgent != null);
    runTestGroup(runtime, arguments, selection.classNames(), "--tagged", javaAgent != null);
  }

  private static void runTestGroup(
      Path runtime,
      List<String> baseArguments,
      List<String> classNames,
      String group,
      boolean coverage)
      throws Exception {
    Duration timeout = testTimeout(group);
    var arguments = new ArrayList<>(baseArguments);
    arguments.add(group);
    arguments.addAll(classNames);
    runArgFile(
        runtimeJava(runtime),
        "run-toktrak-" + group.substring(2) + "-test-suite" + (coverage ? "-with-coverage" : ""),
        arguments,
        timeout,
        group.equals("--unit"));
  }

  static Duration testTimeout(String group) {
    return switch (group) {
      case "--unit" -> UNIT_TEST_TIMEOUT;
      case "--tagged" -> PROCESS_TIMEOUT;
      default -> throw new IllegalArgumentException("unknown test group: " + group);
    };
  }

  private static void testBuildTool() throws Exception {
    ensureBuildDependencies();
    ensureRefasterDependencies();
    compileRefaster();
    List<Path> sources =
        List.of(ROOT.resolve("tools/Build.java"), ROOT.resolve("tests/tools/BuildTest.java"));
    var compileArguments = new ArrayList<String>();
    compileArguments.add("-Xlint:all");
    compileArguments.add("-Werror");
    addErrorProne(compileArguments);
    compileArguments.add("-d");
    compileArguments.add(BUILD_TEST_CLASSES.toString());
    compileArguments.add(sources.get(0).toString());
    compileArguments.add(sources.get(1).toString());
    Path compileArgFile = writeArgFile("compile-build-tool-tests", compileArguments);
    List<Path> fingerprintSources = new ArrayList<>(sources);
    fingerprintSources.add(ERROR_PRONE_CONFIG);
    fingerprintSources.add(REFASTER_SOURCE);
    fingerprintSources.add(REFASTER_RULE);
    fingerprintSources.addAll(REPOSITORY_SKILLS);
    String compileFingerprint =
        compilationFingerprint(
            "build-tool-tests",
            fingerprintSources,
            List.of(BUILD_DEPS, REFASTER_DEPS),
            compileArguments);
    long compileStarted = System.nanoTime();
    if (artifactMatches(BUILD_TEST_CLASSES, compileFingerprint)) {
      printCached(javacExecutable(), compileArgFile, compileStarted);
    } else {
      rebuildArtifact(
          BUILD_TEST_CLASSES,
          compileFingerprint,
          staging -> {
            Path stagingArgFile =
                writeArgFile(
                    "compile-build-tool-tests",
                    remapArguments(compileArguments, BUILD_TEST_CLASSES, staging));
            runJavacArgFile(stagingArgFile);
            requireFile(staging.resolve("tools/Build.class"));
            requireFile(staging.resolve("tools/BuildTest.class"));
          });
    }

    List<String> testArguments =
        List.of("-ea", "-cp", BUILD_TEST_CLASSES.toString(), "tools.BuildTest");
    Path testArgFile = writeArgFile("run-build-tool-tests", testArguments);
    String testFingerprint = testResultFingerprint(compileFingerprint, testArguments);
    long testStarted = System.nanoTime();
    if (artifactMatches(BUILD_TEST_RESULTS, testFingerprint)) {
      printCached(javaExecutable(), testArgFile, testStarted);
    } else {
      rebuildArtifact(
          BUILD_TEST_RESULTS,
          testFingerprint,
          staging -> {
            runArgFile(javaExecutable(), testArgFile);
            Files.writeString(
                staging.resolve("passed"), testFingerprint, StandardOpenOption.CREATE_NEW);
          });
    }
  }

  private static void imageCommand(List<String> arguments) throws Exception {
    requireNoArguments(arguments, "image");
    int version = nextVersion(remoteReleaseTags());
    imageBuild(version, gitHead());
  }

  private static void containerVerifyCommand(List<String> arguments) throws Exception {
    requireNoArguments(arguments, "container-verify");
    int version = nextVersion(remoteReleaseTags());
    String revision = gitHead();
    imageBuild(version, revision);
    containerVerify(imageReference(version), version, revision);
  }

  private static void releaseCommand(List<String> arguments) throws Exception {
    boolean dryRun;
    if (arguments.equals(List.of("--dry-run"))) {
      dryRun = true;
    } else if (arguments.isEmpty()) {
      dryRun = false;
    } else {
      throw new IllegalArgumentException("usage: mise run release [--dry-run]");
    }

    ReleaseState release = releasePreflight(dryRun);
    verify();
    jlinkProd();
    String image = imageReference(release.version());
    if (release.recovery()) {
      inspectImage(image, release.version(), release.revision());
      String expectedImageId =
          gitOutput(List.of("tag", "--list", "v" + release.version(), "--format=%(contents)"))
              .lines()
              .filter(line -> line.startsWith("Image-ID: "))
              .map(line -> line.substring("Image-ID: ".length()))
              .findFirst()
              .orElseThrow(() -> new IllegalStateException("recovery tag has no image ID"));
      if (!imageId(image).equals(expectedImageId)) {
        throw new IllegalStateException("recovery image differs from the tagged artifact");
      }
    } else {
      imageBuild(release.version(), release.revision());
    }
    containerVerify(image, release.version(), release.revision());
    if (dryRun) {
      System.out.println(
          "Release v" + release.version() + " dry run complete; no tag or push performed");
      return;
    }

    String tag = "v" + release.version();
    if (!release.recovery()) {
      runTool(
          "git",
          "create release tag",
          List.of(
              "tag",
              "--annotate",
              tag,
              "--message",
              "TokTrak " + tag + "\n\nImage-ID: " + imageId(image)),
          ROOT,
          PROCESS_TIMEOUT);
    }
    runTool("podman", "push immutable image", List.of("push", image), ROOT, PROCESS_TIMEOUT);
    runTool(
        "git",
        "push release tag",
        List.of("push", "origin", "refs/tags/" + tag),
        ROOT,
        PROCESS_TIMEOUT);
  }

  private static void requireNoArguments(List<String> arguments, String command) {
    assert arguments != null;
    assert command != null && !command.isBlank();
    if (!arguments.isEmpty()) throw new IllegalArgumentException(command + " takes no arguments");
  }

  private static ReleaseState releasePreflight(boolean dryRun) throws Exception {
    String branch = gitOutput(List.of("branch", "--show-current"));
    String revision = gitHead();
    String status = gitOutput(List.of("status", "--porcelain=v1", "--untracked-files=all"));
    ToolResult remoteBranch =
        runTool(
            "git",
            "inspect remote trunk",
            List.of("ls-remote", "origin", "refs/heads/trunk"),
            ROOT,
            PROCESS_TIMEOUT);
    String remoteRevision = firstField(remoteBranch.output(), "origin/trunk");
    requireReleaseTree(branch, status, revision, remoteRevision);

    int version = nextVersion(remoteReleaseTags());
    requireChangelog(version, Files.readString(CHANGELOG, StandardCharsets.UTF_8));
    String tag = "v" + version;
    ToolResult localTag =
        runToolAllowFailure(
            "git",
            "inspect candidate release tag",
            List.of("rev-parse", "--verify", "--quiet", "refs/tags/" + tag + "^{commit}"),
            ROOT,
            PROCESS_TIMEOUT);
    boolean recovery = localTag.exitCode() == 0;
    if (recovery && (dryRun || !localTag.output().strip().equals(revision))) {
      throw new IllegalStateException("release tag " + tag + " must be absent");
    }
    if (localTag.exitCode() != 0 && localTag.exitCode() != 1) {
      throw new IllegalStateException("cannot inspect release tag " + tag);
    }
    return new ReleaseState(version, revision, recovery);
  }

  static void requireReleaseTreeForTest(
      String branch, String status, String revision, String remoteRevision) {
    requireReleaseTree(branch, status, revision, remoteRevision);
  }

  private static void requireReleaseTree(
      String branch, String status, String revision, String remoteRevision) {
    assert branch != null;
    assert status != null;
    assert revision != null;
    assert remoteRevision != null;
    if (!branch.equals("trunk")) throw new IllegalStateException("release requires branch trunk");
    if (!status.isEmpty()) throw new IllegalStateException("release requires a clean working tree");
    if (!remoteRevision.equals(revision)) {
      throw new IllegalStateException("trunk must be synchronized with origin/trunk");
    }
  }

  private static String firstField(String output, String source) {
    assert output != null;
    assert source != null && !source.isBlank();
    String line =
        output
            .lines()
            .findFirst()
            .orElseThrow(() -> new IllegalStateException(source + " is missing"));
    String[] fields = line.split("\\s+", 2);
    if (fields.length != 2 || !fields[0].matches("[0-9a-f]{40}|[0-9a-f]{64}")) {
      throw new IllegalStateException(source + " returned invalid revision");
    }
    return fields[0];
  }

  private static String gitHead() throws Exception {
    String revision = gitOutput(List.of("rev-parse", "HEAD"));
    if (!revision.matches("[0-9a-f]{40}|[0-9a-f]{64}")) {
      throw new IllegalStateException("Git HEAD is not a canonical revision");
    }
    return revision;
  }

  private static String gitOutput(List<String> arguments) throws Exception {
    return runTool("git", "inspect repository", arguments, ROOT, PROCESS_TIMEOUT).output().strip();
  }

  private static List<Integer> remoteReleaseTags() throws Exception {
    return releaseVersions(
        runTool(
                "git",
                "inspect remote release tags",
                List.of("ls-remote", "--tags", "origin", "refs/tags/v*"),
                ROOT,
                PROCESS_TIMEOUT)
            .output());
  }

  static List<Integer> releaseVersionsForTest(String tags) {
    return releaseVersions(tags);
  }

  private static List<Integer> releaseVersions(String tags) {
    assert tags != null;
    var versions = new TreeSet<Integer>();
    for (String line : tags.lines().map(String::strip).filter(value -> !value.isEmpty()).toList()) {
      String reference =
          line.substring(Math.max(line.lastIndexOf(' '), line.lastIndexOf('\t')) + 1);
      if (reference.endsWith("^{}")) continue;
      int slash = reference.lastIndexOf('/');
      String tag = reference.substring(slash + 1);
      if (!tag.matches("v(?:0|[1-9][0-9]*)")) {
        throw new IllegalStateException("invalid release tag: " + tag);
      }
      try {
        versions.add(Integer.parseInt(tag.substring(1)));
      } catch (NumberFormatException exception) {
        throw new IllegalStateException("release version exceeds integer range: " + tag, exception);
      }
    }
    int expected = 0;
    for (int version : versions) {
      if (version != expected)
        throw new IllegalStateException("release version gap before v" + version);
      expected = Math.addExact(expected, 1);
    }
    return List.copyOf(versions);
  }

  static int nextVersionForTest(String tags) {
    return nextVersion(releaseVersions(tags));
  }

  private static int nextVersion(List<Integer> versions) {
    assert versions != null;
    return versions.isEmpty() ? 0 : Math.addExact(versions.getLast(), 1);
  }

  static void requireChangelogForTest(int version, String changelog) {
    requireChangelog(version, changelog);
  }

  private static void requireChangelog(int version, String changelog) {
    assert version >= 0;
    assert changelog != null;
    String heading = "## v" + version;
    List<String> lines = changelog.lines().toList();
    List<Integer> matches =
        java.util.stream.IntStream.range(0, lines.size())
            .filter(index -> lines.get(index).equals(heading))
            .boxed()
            .toList();
    if (matches.size() != 1) {
      throw new IllegalStateException("CHANGELOG.md requires exactly one " + heading + " section");
    }
    int start = Math.addExact(matches.getFirst(), 1);
    int end = start;
    while (end < lines.size() && !lines.get(end).startsWith("## ")) end = Math.addExact(end, 1);
    if (lines.subList(start, end).stream().allMatch(String::isBlank)) {
      throw new IllegalStateException(heading + " section is empty");
    }
  }

  private static void imageBuild(int version, String revision) throws Exception {
    assert version >= 0;
    assert revision != null && revision.matches("[0-9a-f]{40}|[0-9a-f]{64}");
    requireFile(CONTAINERFILE);
    String image = imageReference(version);
    runTool(
        "podman",
        "build TokTrak image",
        List.of(
            "build",
            "--no-cache",
            "--timestamp",
            "0",
            "--file",
            CONTAINERFILE.toString(),
            "--tag",
            image,
            "--build-arg",
            "VERSION=" + version,
            "--build-arg",
            "REVISION=" + revision,
            ROOT.toString()),
        ROOT,
        CONTAINER_BUILD_TIMEOUT);
    inspectImage(image, version, revision);
    runTool(
        "podman",
        "verify image assets",
        List.of(
            "run",
            "--rm",
            "--entrypoint",
            "/opt/toktrak/bin/java",
            image,
            "-ea",
            "-m",
            "toktrak/toktrak.Main",
            "--check-assets"),
        ROOT,
        PROCESS_TIMEOUT);
    runTool(
        "podman",
        "verify image jcmd launcher",
        List.of("run", "--rm", "--entrypoint", "/opt/toktrak/bin/jcmd", image, "-h"),
        ROOT,
        PROCESS_TIMEOUT);
    runTool(
        "podman",
        "verify image JFR launcher",
        List.of("run", "--rm", "--entrypoint", "/opt/toktrak/bin/jfr", image, "help"),
        ROOT,
        PROCESS_TIMEOUT);
  }

  private static void inspectImage(String image, int version, String revision) throws Exception {
    String format =
        "{{.Os}}|{{.Config.User}}|{{.Config.ExposedPorts}}|{{.Config.Volumes}}|{{.Config.Labels}}";
    String output =
        runTool(
                "podman",
                "inspect TokTrak image",
                List.of("image", "inspect", "--format", format, image),
                ROOT,
                PROCESS_TIMEOUT)
            .output()
            .strip();
    String[] fields = output.split("\\|", -1);
    if (fields.length != 5
        || !fields[0].equals("linux")
        || !fields[1].equals("0")
        || !fields[2].contains("8080/tcp")
        || !fields[3].contains("/data")
        || !fields[4].contains(IMAGE_VERSION_LABEL + ":" + version)
        || !fields[4].contains(IMAGE_REVISION_LABEL + ":" + revision)) {
      throw new IllegalStateException("TokTrak image metadata is invalid");
    }
  }

  private static String imageId(String image) throws Exception {
    String id =
        runTool(
                "podman",
                "inspect immutable image ID",
                List.of("image", "inspect", "--format", "{{.Id}}", image),
                ROOT,
                PROCESS_TIMEOUT)
            .output()
            .strip();
    return canonicalImageId(id);
  }

  static String canonicalImageIdForTest(String id) {
    return canonicalImageId(id);
  }

  private static String canonicalImageId(String id) {
    assert id != null;
    if (id.matches("[0-9a-f]{64}")) return "sha256:" + id;
    if (id.matches("sha256:[0-9a-f]{64}")) return id;
    throw new IllegalStateException("Podman returned invalid image ID");
  }

  static String imageRepositoryForTest(String value) {
    return requireImageRepository(value);
  }

  private static String imageRepository() {
    return requireImageRepository(System.getenv(IMAGE_REPOSITORY_ENV));
  }

  private static String requireImageRepository(String value) {
    if (value == null || value.isBlank()) {
      throw new IllegalStateException(
          IMAGE_REPOSITORY_ENV + " is required; define it in mise.local.toml");
    }
    if (value.length() > 512) {
      throw new IllegalStateException(IMAGE_REPOSITORY_ENV + " exceeds 512 characters");
    }
    String[] segments = value.split("/", -1);
    if (segments.length < 2
        || !segments[0].matches("[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?(?::[1-9][0-9]{0,4})?")) {
      throw new IllegalStateException(IMAGE_REPOSITORY_ENV + " is not a canonical OCI repository");
    }
    int colon = segments[0].lastIndexOf(':');
    if (colon >= 0 && Integer.parseInt(segments[0].substring(colon + 1)) > 65_535) {
      throw new IllegalStateException(IMAGE_REPOSITORY_ENV + " has an invalid registry port");
    }
    for (int index = 1; index < segments.length; index++) {
      if (!segments[index].matches("[a-z0-9]+(?:[._-][a-z0-9]+)*")) {
        throw new IllegalStateException(
            IMAGE_REPOSITORY_ENV + " is not a canonical OCI repository");
      }
    }
    return value;
  }

  private static String imageReference(int version) {
    assert version >= 0;
    return imageRepository() + ":v" + version;
  }

  private static void containerVerify(String image, int version, String revision) throws Exception {
    inspectImage(image, version, revision);
    String rootless =
        runTool(
                "podman",
                "verify rootless Podman",
                List.of("info", "--format", "{{.Host.Security.Rootless}}"),
                ROOT,
                PROCESS_TIMEOUT)
            .output()
            .strip();
    if (!rootless.equals("true")) {
      throw new IllegalStateException("container verification requires rootless Podman");
    }

    String suffix = UUID.randomUUID().toString().replace("-", "");
    String container = "toktrak-verify-" + suffix;
    String volume = "toktrak-verify-" + suffix;
    Path environment = writeContainerEnvironment();
    Exception failure = null;
    try {
      runTool(
          "podman",
          "create verification volume",
          List.of("volume", "create", volume),
          ROOT,
          PROCESS_TIMEOUT);
      int firstPort = startVerificationContainer(image, container, volume, environment);
      awaitHealthy(firstPort);
      runTool(
          "podman",
          "write persistence marker",
          List.of(
              "exec",
              container,
              "/bin/sh",
              "-c",
              "printf toktrak-container-verify > /data/.container-verify"),
          ROOT,
          PROCESS_TIMEOUT);
      stopVerificationContainer(container);
      int secondPort = startVerificationContainer(image, container, volume, environment);
      awaitHealthy(secondPort);
      String marker =
          runTool(
                  "podman",
                  "read persistence marker",
                  List.of("exec", container, "cat", "/data/.container-verify"),
                  ROOT,
                  PROCESS_TIMEOUT)
              .output();
      if (!marker.equals("toktrak-container-verify")) {
        throw new IllegalStateException("container volume did not preserve data across restart");
      }
    } catch (Exception exception) {
      failure = exception;
    }
    failure = cleanupVerificationContainer(container, failure);
    failure = cleanupVerificationVolume(volume, failure);
    try {
      Files.deleteIfExists(environment);
      Files.deleteIfExists(CONTAINER_VERIFY);
    } catch (IOException exception) {
      if (failure == null) failure = exception;
      else failure.addSuppressed(exception);
    }
    if (failure != null) throw failure;
  }

  private static Exception cleanupVerificationContainer(String container, Exception failure) {
    try {
      ToolResult result =
          runToolAllowFailure(
              "podman",
              "remove verification container",
              List.of("rm", "--force", container),
              ROOT,
              PROCESS_TIMEOUT);
      if (result.exitCode() != 0) {
        throw new IllegalStateException("cannot remove verification container");
      }
    } catch (Exception exception) {
      if (failure == null) return exception;
      failure.addSuppressed(exception);
    }
    return failure;
  }

  private static Exception cleanupVerificationVolume(String volume, Exception failure) {
    try {
      ToolResult result =
          runToolAllowFailure(
              "podman",
              "remove verification volume",
              List.of("volume", "rm", "--force", volume),
              ROOT,
              PROCESS_TIMEOUT);
      if (result.exitCode() != 0) {
        throw new IllegalStateException("cannot remove verification volume");
      }
    } catch (Exception exception) {
      if (failure == null) return exception;
      failure.addSuppressed(exception);
    }
    return failure;
  }

  private static Path writeContainerEnvironment() throws IOException {
    byte[] secret = new byte[32];
    new SecureRandom().nextBytes(secret);
    String encoded = Base64.getEncoder().encodeToString(secret);
    deleteTree(CONTAINER_VERIFY);
    Files.createDirectories(CONTAINER_VERIFY);
    Path file = Files.createTempFile(CONTAINER_VERIFY, "environment-", ".env");
    Files.writeString(
        file,
        "TOKTRAK_BASE_URL=https://toktrak.test\n"
            + "TOKTRAK_PORT=8080\n"
            + "TOKTRAK_DATA_DIR=/data\n"
            + "TOKTRAK_OIDC_DISCOVERY_URL=https://accounts.example/.well-known/openid-configuration\n"
            + "TOKTRAK_OIDC_CLIENT_ID=container-verify\n"
            + "TOKTRAK_OIDC_CLIENT_SECRET=container-verify\n"
            + "TOKTRAK_ALLOWED_DOMAIN=example.com\n"
            + "TOKTRAK_SESSION_SECRET="
            + encoded
            + "\nTOKTRAK_TOKEN_PEPPER="
            + encoded
            + "\n",
        StandardCharsets.UTF_8,
        StandardOpenOption.TRUNCATE_EXISTING);
    if (Files.getFileStore(file).supportsFileAttributeView("posix")) {
      Files.setPosixFilePermissions(file, Set.of(PosixFilePermission.OWNER_READ));
    }
    return file;
  }

  private static int startVerificationContainer(
      String image, String container, String volume, Path environment) throws Exception {
    runTool(
        "podman",
        "start verification container",
        List.of(
            "run",
            "--detach",
            "--name",
            container,
            "--env-file",
            environment.toString(),
            "--volume",
            volume + ":/data",
            "--publish",
            "127.0.0.1::8080",
            image),
        ROOT,
        PROCESS_TIMEOUT);
    String mapping =
        runTool(
                "podman",
                "inspect published port",
                List.of("port", container, "8080/tcp"),
                ROOT,
                PROCESS_TIMEOUT)
            .output()
            .strip();
    int colon = mapping.lastIndexOf(':');
    if (colon < 0) throw new IllegalStateException("Podman returned invalid published port");
    try {
      int port = Integer.parseInt(mapping.substring(colon + 1));
      if (port < 1 || port > 65_535) throw new NumberFormatException();
      return port;
    } catch (NumberFormatException exception) {
      throw new IllegalStateException("Podman returned invalid published port", exception);
    }
  }

  private static void stopVerificationContainer(String container) throws Exception {
    runTool(
        "podman", "stop verification container", List.of("stop", container), ROOT, PROCESS_TIMEOUT);
    runTool(
        "podman", "remove verification container", List.of("rm", container), ROOT, PROCESS_TIMEOUT);
  }

  private static void awaitHealthy(int port) throws Exception {
    var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    URI uri = URI.create("http://127.0.0.1:" + port + "/health");
    long deadline = System.nanoTime() + CONTAINER_START_TIMEOUT.toNanos();
    Exception lastFailure = null;
    while (System.nanoTime() < deadline) {
      try {
        var request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(2)).GET().build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 200 && response.body().equals("{\"status\":\"ok\"}")) return;
      } catch (IOException exception) {
        lastFailure = exception;
      }
      try {
        Thread.sleep(Duration.ofMillis(250));
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
        throw exception;
      }
    }
    throw new IllegalStateException("container health check timed out", lastFailure);
  }

  private record ReleaseState(int version, String revision, boolean recovery) {
    private ReleaseState {
      assert version >= 0;
      assert revision != null && revision.matches("[0-9a-f]{40}|[0-9a-f]{64}");
    }
  }

  private static void jlinkProd() throws Exception {
    compile();
    verifyModules(MAIN_DEPS);
    Path runtime = ensureRuntime("prod", List.of(MAIN_DEPS), PROD_RUNTIME_ROOTS, true);
    verifyProductionRuntime(runtime);
    runArgFile(
        runtimeJava(runtime),
        "verify-toktrak-production-runtime",
        List.of("-ea", "-m", "toktrak/toktrak.Main", "--check-assets"));
  }

  static List<String> productionRuntimeRootsForTest() {
    return PROD_RUNTIME_ROOTS;
  }

  private static void verifyProductionRuntime(Path runtime) throws Exception {
    String modules =
        runTool(
                runtimeJava(runtime),
                "verify production management modules",
                List.of("--list-modules"),
                ROOT,
                PROCESS_TIMEOUT)
            .output();
    for (String module : PROD_RUNTIME_ROOTS.subList(1, PROD_RUNTIME_ROOTS.size())) {
      if (modules.lines().noneMatch(line -> line.startsWith(module + "@"))) {
        throw new IllegalStateException("production runtime module is missing: " + module);
      }
    }
    requireFile(runtimeExecutable(runtime, "jcmd"));
    requireFile(runtimeExecutable(runtime, "jfr"));
  }

  private static Path runtimeExecutable(Path runtime, String name) {
    assert runtime != null;
    assert name != null && !name.isBlank();
    return runtime.resolve("bin").resolve(isWindows() ? name + ".exe" : name);
  }

  private static void dev(List<String> args) throws Exception {
    compile();
    Path runtime = ensureDevRuntime();
    List<String> arguments = new ArrayList<>();
    arguments.add("-ea");
    arguments.add("--module-path");
    arguments.add(MODULE_CLASSES.toString());
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
    roots.add("java.instrument");
    return ensureRuntime(
        "test",
        List.of(MAIN_DEPS, TEST_DEPS),
        moduleNames(List.of(MAIN_DEPS, TEST_DEPS), roots),
        false);
  }

  private static Path ensureRuntime(
      String name, List<Path> dependencyDirectories, List<String> roots, boolean includeApp)
      throws Exception {
    assert name != null;
    assert dependencyDirectories != null;
    assert roots != null;
    requireCollectionSize(dependencyDirectories, "dependency directories");
    requireCollectionSize(roots, "runtime roots");
    long started = System.nanoTime();
    Path image = RUNTIMES.resolve(name);
    List<String> modulePath = new ArrayList<>();
    modulePath.add(Path.of(System.getProperty("java.home"), "jmods").toString());
    modulePath.addAll(runtimeJarPaths(dependencyDirectories).stream().map(Path::toString).toList());
    if (includeApp) modulePath.add(APP_MODULE.toString());
    List<String> arguments =
        List.of(
            "--module-path",
            String.join(java.io.File.pathSeparator, modulePath),
            "--add-modules",
            String.join(",", roots),
            "--output",
            image.toString(),
            "--strip-debug",
            "--no-header-files",
            "--no-man-pages");
    Path argFile = writeArgFile("link-toktrak-" + runtimeName(name) + "-runtime", arguments);
    String fingerprint = fingerprint(name, dependencyDirectories, roots, includeApp);
    if (artifactMatches(image, fingerprint)) {
      printCached(jlinkExecutable(), argFile, started);
      return image;
    }

    for (Path directory : dependencyDirectories) verifyModules(directory);
    rebuildArtifact(
        image,
        fingerprint,
        staging -> {
          Path stagingArgFile =
              writeArgFile(
                  "link-toktrak-" + runtimeName(name) + "-runtime",
                  remapArguments(arguments, image, staging));
          Files.delete(staging);
          runArgFile(jlinkExecutable(), stagingArgFile);
          requireFile(Path.of(runtimeJava(staging)));
          requireFile(staging.resolve("lib/tzdb.dat"));
        });
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
    if (includeApp) updateApplicationRuntimeFingerprint(digest, APP_MODULE);
    return HexFormat.of().formatHex(digest.digest());
  }

  static String applicationRuntimeFingerprintForTest(Path appModule) throws Exception {
    var digest = MessageDigest.getInstance("SHA-256");
    updateApplicationRuntimeFingerprint(digest, appModule);
    return HexFormat.of().formatHex(digest.digest());
  }

  private static void updateApplicationRuntimeFingerprint(MessageDigest digest, Path appModule)
      throws IOException {
    assert digest != null;
    assert appModule != null;
    updateTree(digest, appModule, "");
  }

  private static void updateTree(MessageDigest digest, Path directory, String suffix)
      throws IOException {
    assert digest != null;
    assert directory != null;
    assert suffix != null;
    for (Path path :
        treePaths(directory, TREE_ENTRIES_MAX).stream()
            .filter(Files::isRegularFile)
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
    assert command != null;
    requireCollectionSize(TEST_EXPORTS, "test exports");
    for (String export : TEST_EXPORTS) {
      command.add("--add-exports");
      command.add(export);
    }
  }

  private static void verifyModules(Path directory) throws IOException {
    if (!Files.isDirectory(directory)) {
      throw new IllegalStateException("dependency directory missing: " + directory);
    }
    for (Path jar : jarPaths(List.of(directory))) {
      if (!compileOnlyJdtAnnotations(jar)) moduleName(jar);
    }
  }

  private static List<String> moduleNames(List<Path> directories, List<String> additional)
      throws IOException {
    assert additional != null;
    requireCollectionSize(additional, "additional modules");
    var names = new ArrayList<String>();
    for (Path jar : jarPaths(directories)) {
      if (compileOnlyJdtAnnotations(jar)) continue;
      if (names.size() >= COLLECTION_ENTRIES_MAX) {
        throw new IllegalStateException(
            "module names exceed " + COLLECTION_ENTRIES_MAX + " entries");
      }
      names.add(moduleName(jar));
    }
    if (additional.size() > COLLECTION_ENTRIES_MAX - names.size()) {
      throw new IllegalStateException("module names exceed " + COLLECTION_ENTRIES_MAX + " entries");
    }
    names.addAll(additional);
    return names.stream().distinct().sorted().toList();
  }

  private static List<String> moduleNames(Path directory) throws IOException {
    return jarPaths(List.of(directory)).stream()
        .filter(path -> !compileOnlyJdtAnnotations(path))
        .map(Build::moduleName)
        .toList();
  }

  private static boolean compileOnlyJdtAnnotations(Path jar) {
    return jar.getFileName().toString().equals("org.eclipse.jdt.annotation.jar");
  }

  private static List<Path> runtimeJarPaths(List<Path> directories) throws IOException {
    return jarPaths(directories).stream().filter(path -> !compileOnlyJdtAnnotations(path)).toList();
  }

  private static String moduleName(Path jar) {
    var modules = ModuleFinder.of(jar).findAll();
    if (modules.size() != 1) throw new IllegalStateException("module descriptor missing: " + jar);
    var descriptor = modules.iterator().next().descriptor();
    if (descriptor.isAutomatic())
      throw new IllegalStateException("automatic module rejected: " + jar);
    return descriptor.name();
  }

  private static List<Path> jarPaths(List<Path> directories) throws IOException {
    assert directories != null;
    if (directories.size() > TREE_ENTRIES_MAX) {
      throw new IllegalStateException("directories exceed " + TREE_ENTRIES_MAX + " entries");
    }
    var result = new ArrayList<Path>();
    for (Path directory : directories) {
      for (Path path : directoryEntries(directory, TREE_ENTRIES_MAX)) {
        if (!isJar(path)) continue;
        if (result.size() >= TREE_ENTRIES_MAX) {
          throw new IllegalStateException("JAR paths exceed " + TREE_ENTRIES_MAX + " entries");
        }
        result.add(path);
      }
    }
    result.sort(Comparator.naturalOrder());
    return List.copyOf(result);
  }

  private static void addErrorProne(List<String> arguments) throws IOException {
    assert arguments != null;
    arguments.addAll(errorProneArguments());
  }

  static List<String> refasterArgumentsForTest() throws IOException {
    var arguments = new ArrayList<>(errorProneJvmArguments());
    arguments.addAll(refasterPatchArguments());
    return List.copyOf(arguments);
  }

  private static List<String> errorProneJvmArguments() {
    return List.of(
        "-J--add-exports=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED",
        "-J--add-exports=jdk.compiler/com.sun.tools.javac.file=ALL-UNNAMED",
        "-J--add-exports=jdk.compiler/com.sun.tools.javac.main=ALL-UNNAMED",
        "-J--add-exports=jdk.compiler/com.sun.tools.javac.model=ALL-UNNAMED",
        "-J--add-exports=jdk.compiler/com.sun.tools.javac.parser=ALL-UNNAMED",
        "-J--add-exports=jdk.compiler/com.sun.tools.javac.processing=ALL-UNNAMED",
        "-J--add-exports=jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED",
        "-J--add-exports=jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED",
        "-J--add-opens=jdk.compiler/com.sun.tools.javac.code=ALL-UNNAMED",
        "-J--add-opens=jdk.compiler/com.sun.tools.javac.comp=ALL-UNNAMED");
  }

  private static List<String> errorProneArguments() throws IOException {
    return errorProneArguments("");
  }

  private static List<String> refasterPatchArguments() throws IOException {
    return errorProneArguments(
        " -XepPatchChecks:refaster:" + REFASTER_RULE + " -XepPatchLocation:IN_PLACE");
  }

  private static List<String> errorProneArguments(String pluginArguments) throws IOException {
    assert pluginArguments != null;
    return List.of(
        "-XDcompilePolicy=simple",
        "--should-stop=ifError=FLOW",
        "-processorpath",
        modulePath(List.of(BUILD_DEPS)),
        "-Xplugin:ErrorProne @"
            + ERROR_PRONE_CONFIG
            + " -XepExcludedPaths:.+[\\\\/]generated-sources[\\\\/].+"
            + pluginArguments);
  }

  private static void addModuleSourcePaths(List<String> command) {
    command.add("--module-source-path");
    command.add("toktrak=" + ROOT.resolve("sources/toktrak"));
    command.add("--module-source-path");
    command.add("toktrak.tests=" + ROOT.resolve("tests/toktrak.tests"));
  }

  private static String modulePath(List<Path> entries) throws IOException {
    assert entries != null;
    requireCollectionSize(entries, "module path entries");
    var paths = new ArrayList<String>();
    for (Path entry : entries) {
      if (Files.isDirectory(entry) && !entry.equals(MODULE_CLASSES)) {
        List<String> jars = jarPaths(List.of(entry)).stream().map(Path::toString).toList();
        if (jars.size() > COLLECTION_ENTRIES_MAX - paths.size()) {
          throw new IllegalStateException(
              "module path exceeds " + COLLECTION_ENTRIES_MAX + " entries");
        }
        paths.addAll(jars);
      } else {
        paths.add(entry.toString());
      }
      if (paths.size() > COLLECTION_ENTRIES_MAX) {
        throw new IllegalStateException(
            "module path exceeds " + COLLECTION_ENTRIES_MAX + " entries");
      }
    }
    return String.join(java.io.File.pathSeparator, paths);
  }

  private static void requireCollectionSize(List<?> values, String name) {
    assert values != null;
    assert name != null && !name.isBlank();
    if (values.size() > COLLECTION_ENTRIES_MAX) {
      throw new IllegalStateException(name + " exceed " + COLLECTION_ENTRIES_MAX + " entries");
    }
  }

  private static boolean isJar(Path path) {
    return path.getFileName().toString().endsWith(".jar");
  }

  private static void cleanGeneratedTree(Path path) throws IOException {
    assert path != null;
    deleteStaleStaging(path);
    deleteStaleBackups(path);
    deleteGeneratedTree(path);
  }

  private static void deleteStaleBackups(Path output) throws IOException {
    Path normalized = output.toAbsolutePath().normalize();
    Path parent = normalized.getParent();
    if (!Files.isDirectory(parent)) return;
    String prefix = "." + normalized.getFileName() + ".backup-";
    for (Path path : directoryEntries(parent, TREE_ENTRIES_MAX)) {
      if (path.getFileName().toString().startsWith(prefix)) deleteGeneratedTree(path);
    }
  }

  private static void deleteGeneratedTree(Path path) throws IOException {
    assert path != null;
    if (!Files.exists(path)) return;
    for (Path candidate : treePaths(path, TREE_ENTRIES_MAX)) {
      if (candidate.getFileName().toString().equals(ARTIFACT_MARKER)) {
        Files.deleteIfExists(candidate);
      }
    }
    deleteTree(path);
  }

  private static void deleteTree(Path path) throws IOException {
    assert path != null;
    if (!Files.exists(path)) return;
    var paths = new ArrayList<>(treePaths(path, TREE_ENTRIES_MAX));
    paths.sort(Comparator.reverseOrder());
    for (Path child : paths) Files.delete(child);
  }

  private static void cleanIntellijMetadata(Path idea) throws IOException {
    assert idea != null;
    Files.deleteIfExists(idea.resolve(ARTIFACT_MARKER));
    for (String file : intellijFiles()) Files.deleteIfExists(idea.resolve(file));
    deleteDirectoryIfEmpty(idea.resolve("modules"));
    deleteDirectoryIfEmpty(idea);
  }

  private static void deleteDirectoryIfEmpty(Path directory) throws IOException {
    assert directory != null;
    if (!Files.isDirectory(directory)) return;
    try (var entries = Files.newDirectoryStream(directory)) {
      if (entries.iterator().hasNext()) return;
    }
    Files.delete(directory);
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

  private static void runJavacArgFile(Path argFile) throws Exception {
    var arguments = new ArrayList<>(errorProneJvmArguments());
    arguments.add("@" + argFile);
    printInvocation(javacExecutable(), argFile);
    runProcess(new ProcessBuilder(command(javacExecutable(), arguments)));
  }

  private static void runArgFile(
      String executable,
      String name,
      List<String> arguments,
      Duration timeout,
      boolean forceAtTimeout)
      throws Exception {
    runArgFile(executable, writeArgFile(name, arguments), timeout, forceAtTimeout);
  }

  private static Path writeArgFile(String name, List<String> arguments) throws IOException {
    assert name != null;
    Files.createDirectories(ARGFILES);
    Path argFile = ARGFILES.resolve(name + ".args");
    if (!argFile.normalize().startsWith(ARGFILES)) {
      throw new IllegalStateException("argument file escapes output directory");
    }
    String content = argumentFileContent(arguments);
    Files.writeString(
        argFile, content, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    assert Files.size(argFile) <= ARGFILE_BYTES_MAX;
    return argFile;
  }

  private static void runArgFile(String executable, Path argFile) throws Exception {
    runArgFile(executable, argFile, PROCESS_TIMEOUT, false);
  }

  private static void runArgFile(
      String executable, Path argFile, Duration timeout, boolean forceAtTimeout) throws Exception {
    assert executable != null;
    assert argFile != null;
    printInvocation(executable, argFile);
    runProcess(new ProcessBuilder(executable, "@" + argFile), timeout, forceAtTimeout);
  }

  static List<String> commandForTest(String executable, List<String> arguments) {
    return command(executable, arguments);
  }

  private static void runArguments(String executable, String name, List<String> arguments)
      throws Exception {
    assert name != null;
    if (name.isBlank()) throw new IllegalArgumentException("name is blank");
    List<String> command = command(executable, arguments);
    printInvocation(executable, name);
    runProcess(new ProcessBuilder(command));
  }

  private static List<String> command(String executable, List<String> arguments) {
    int bytes = commandBytes(executable, arguments);
    if (bytes > COMMAND_BYTES_MAX) {
      throw new IllegalStateException("command exceeds " + COMMAND_BYTES_MAX + " UTF-8 bytes");
    }
    var command = new ArrayList<String>(Math.addExact(arguments.size(), 1));
    command.add(executable);
    command.addAll(arguments);
    return List.copyOf(command);
  }

  private static int commandBytes(String executable, List<String> arguments) {
    assert executable != null;
    assert arguments != null;
    if (arguments.size() > ARGUMENTS_MAX) {
      throw new IllegalStateException("arguments exceed " + ARGUMENTS_MAX + " entries");
    }
    int bytes = executable.getBytes(StandardCharsets.UTF_8).length;
    for (String argument : arguments) {
      bytes = Math.addExact(bytes, Math.addExact(argumentBytes(argument), 1));
    }
    return bytes;
  }

  private static int argumentBytes(String argument) {
    assert argument != null;
    int bytes = argument.getBytes(StandardCharsets.UTF_8).length;
    if (bytes > ARGUMENT_BYTES_MAX) {
      throw new IllegalStateException("argument exceeds " + ARGUMENT_BYTES_MAX + " UTF-8 bytes");
    }
    return bytes;
  }

  static ToolResult runToolForTest(String executable, List<String> arguments, Path directory)
      throws Exception {
    return runTool(executable, "test tool", arguments, directory, PROCESS_TIMEOUT);
  }

  private static ToolResult runTool(
      String executable, String name, List<String> arguments, Path directory, Duration timeout)
      throws Exception {
    ToolResult result = runToolAllowFailure(executable, name, arguments, directory, timeout);
    if (result.exitCode() != 0) {
      throw new IllegalStateException(name + " failed with exit code " + result.exitCode());
    }
    return result;
  }

  private static ToolResult runToolAllowFailure(
      String executable, String name, List<String> arguments, Path directory, Duration timeout)
      throws Exception {
    assert executable != null && !executable.isBlank();
    assert name != null && !name.isBlank();
    assert directory != null;
    requirePositiveDuration(timeout, "timeout");
    List<String> command = command(executable, arguments);
    printInvocation(executable, name);
    long started = System.nanoTime();
    Process process;
    try {
      process =
          new ProcessBuilder(command)
              .directory(directory.toAbsolutePath().normalize().toFile())
              .redirectErrorStream(true)
              .start();
    } catch (IOException exception) {
      String tool = toolName(executable);
      if (tool.equalsIgnoreCase("podman") || tool.equalsIgnoreCase("podman.exe")) {
        throw new IllegalStateException(
            "Podman is required; install it and start a rootless machine", exception);
      }
      throw new IllegalStateException("cannot start required tool: " + tool, exception);
    }

    var output = new AtomicReference<byte[]>();
    var readFailure = new AtomicReference<IOException>();
    Thread reader =
        Thread.ofVirtual()
            .name("toktrak-tool-output-reader")
            .start(
                () -> {
                  try {
                    byte[] bytes = process.getInputStream().readNBytes(TOOL_OUTPUT_BYTES_MAX + 1);
                    output.set(bytes);
                    if (bytes.length > TOOL_OUTPUT_BYTES_MAX) {
                      terminateFromReader(process, readFailure);
                    }
                  } catch (IOException exception) {
                    readFailure.set(exception);
                    terminateFromReader(process, readFailure);
                  }
                });
    int exitCode;
    try {
      exitCode = waitForProcess(process, timeout, PROCESS_KILL_TIMEOUT, false);
    } catch (InterruptedException exception) {
      terminate(process, PROCESS_KILL_TIMEOUT);
      reader.interrupt();
      Thread.currentThread().interrupt();
      throw exception;
    }
    if (!reader.join(PROCESS_KILL_TIMEOUT)) {
      terminateForcibly(process, PROCESS_KILL_TIMEOUT);
      throw new IllegalStateException(name + " output reader did not terminate");
    }
    if (readFailure.get() != null) {
      throw new IllegalStateException("cannot read " + name + " output", readFailure.get());
    }
    byte[] bytes = output.get();
    assert bytes != null;
    if (bytes.length > TOOL_OUTPUT_BYTES_MAX) {
      throw new IllegalStateException(name + " output exceeds " + TOOL_OUTPUT_BYTES_MAX + " bytes");
    }
    String text;
    try {
      text =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(bytes))
              .toString();
    } catch (CharacterCodingException exception) {
      throw new IllegalStateException(name + " output is not valid UTF-8", exception);
    }
    printCompletion(exitCode == 0 ? "done" : "failed", System.nanoTime() - started, null);
    return new ToolResult(exitCode, text);
  }

  static record ToolResult(int exitCode, String output) {
    ToolResult {
      assert exitCode >= 0;
      assert output != null;
    }
  }

  private static void runProcess(ProcessBuilder builder) throws Exception {
    runProcess(builder, PROCESS_TIMEOUT, false);
  }

  private static void runProcess(ProcessBuilder builder, Duration timeout, boolean forceAtTimeout)
      throws Exception {
    assert builder != null;
    requirePositiveDuration(timeout, "timeout");
    long started = System.nanoTime();
    Process process = builder.inheritIO().start();
    int code;
    try {
      code = waitForProcess(process, timeout, PROCESS_KILL_TIMEOUT, forceAtTimeout);
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
    if (code != 0) throw new IllegalStateException("command failed with exit code " + code);
  }

  @FunctionalInterface
  interface ArtifactBuilder {
    void build(Path staging) throws Exception;
  }

  static boolean artifactMatchesForTest(Path directory, String fingerprint) throws IOException {
    return artifactMatches(directory, fingerprint);
  }

  static void rebuildArtifactForTest(
      Path output, String fingerprint, ArtifactBuilder builder, String failpoint) throws Exception {
    rebuildArtifact(output, fingerprint, builder, failpoint);
  }

  private static boolean artifactMatches(Path directory, String fingerprint) throws IOException {
    assert directory != null;
    requireArtifactFingerprint(fingerprint);
    Path marker = directory.resolve(ARTIFACT_MARKER);
    if (Files.isSymbolicLink(directory)
        || !Files.isDirectory(directory)
        || Files.isSymbolicLink(marker)
        || !Files.isRegularFile(marker)) return false;
    byte[] bytes;
    try (var input = Files.newInputStream(marker)) {
      bytes = input.readNBytes(ARTIFACT_MARKER_BYTES_MAX + 1);
      if (bytes.length > ARTIFACT_MARKER_BYTES_MAX || input.read() >= 0) return false;
    }
    String content;
    try {
      content =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(bytes))
              .toString();
    } catch (CharacterCodingException exception) {
      return false;
    }
    String[] lines = content.split("\n", -1);
    if (lines.length < 3
        || !lines[0].equals("toktrak-artifact-v2")
        || !lines[1].equals("fingerprint\t" + fingerprint)
        || !lines[lines.length - 1].isEmpty()
        || lines.length > TREE_ENTRIES_MAX + 3) return false;
    var expected = new java.util.TreeMap<String, String>();
    for (int index = 2; index < lines.length - 1; index++) {
      String[] fields = lines[index].split("\t", -1);
      String path;
      String signature;
      if (fields.length == 4 && fields[0].equals("file")) {
        long length;
        try {
          length = Long.parseLong(fields[1]);
        } catch (NumberFormatException exception) {
          return false;
        }
        if (length < 0 || length > FILE_BYTES_MAX || !fields[2].matches("[0-9a-f]{64}"))
          return false;
        path = fields[3];
        signature = "file\t" + length + "\t" + fields[2];
      } else if (fields.length == 3
          && fields[0].equals("link")
          && canonicalSymbolicLinkTarget(fields[1])) {
        path = fields[2];
        signature = "link\t" + fields[1];
      } else {
        return false;
      }
      if (!canonicalArtifactPath(path) || expected.put(path, signature) != null) return false;
    }
    var actual = new java.util.TreeMap<String, String>();
    List<Path> paths;
    try {
      paths = treePaths(directory, TREE_ENTRIES_MAX);
      for (Path path : paths) {
        if (path.equals(directory)
            || path.equals(marker)
            || (!Files.isSymbolicLink(path) && Files.isDirectory(path))) continue;
        String relative = artifactPath(directory, path);
        if (actual.put(relative, artifactSignature(directory, path)) != null) return false;
      }
    } catch (IllegalStateException | IOException exception) {
      return false;
    }
    return actual.equals(expected);
  }

  private static void rebuildArtifact(Path output, String fingerprint, ArtifactBuilder builder)
      throws Exception {
    rebuildArtifact(output, fingerprint, builder, null);
  }

  private static void rebuildArtifact(
      Path output, String fingerprint, ArtifactBuilder builder, String failpoint) throws Exception {
    assert output != null;
    assert builder != null;
    requireArtifactFingerprint(fingerprint);
    Files.createDirectories(output.toAbsolutePath().normalize().getParent());
    recoverArtifactBackup(output);
    deleteStaleStaging(output);
    Path staging = stagingPath(output, "stage");
    Path backup = stagingPath(output, "backup");
    Files.createDirectory(staging);
    failArtifactBuild(failpoint, "stage");
    builder.build(staging);
    failArtifactBuild(failpoint, "validation");
    writeArtifactMarker(staging, fingerprint);
    failArtifactBuild(failpoint, "marker");
    failArtifactBuild(failpoint, "invalidation");
    boolean backedUp = Files.exists(output);
    try {
      if (backedUp) Files.move(output, backup, StandardCopyOption.ATOMIC_MOVE);
      failArtifactBuild(failpoint, "deletion");
      failArtifactBuild(failpoint, "publish");
      Files.move(staging, output, StandardCopyOption.ATOMIC_MOVE);
      failArtifactBuild(failpoint, "published");
      if (!artifactMatches(output, fingerprint)) {
        throw new IllegalStateException("published artifact failed integrity validation");
      }
      if (backedUp) deleteGeneratedTree(backup);
      failArtifactBuild(failpoint, "cleanup");
    } catch (Exception exception) {
      try {
        if (backedUp && Files.exists(backup)) {
          if (Files.exists(output)) deleteGeneratedTree(output);
          failArtifactBuild(failpoint, "restoration");
          Files.move(backup, output, StandardCopyOption.ATOMIC_MOVE);
        }
      } catch (Exception restoreException) {
        exception.addSuppressed(restoreException);
      }
      throw exception;
    }
  }

  private static void failArtifactBuild(String configured, String reached) {
    assert reached != null;
    if (reached.equals(configured)) {
      throw new IllegalStateException("artifact failpoint: " + reached);
    }
  }

  private static void writeArtifactMarker(Path directory, String fingerprint) throws IOException {
    var content = new StringBuilder("toktrak-artifact-v2\nfingerprint\t");
    content.append(fingerprint).append('\n');
    int contentBytes = content.toString().getBytes(StandardCharsets.UTF_8).length;
    for (Path path : treePaths(directory, TREE_ENTRIES_MAX).stream().sorted().toList()) {
      if (path.equals(directory) || (!Files.isSymbolicLink(path) && Files.isDirectory(path))) {
        continue;
      }
      String line =
          artifactSignature(directory, path) + "\t" + artifactPath(directory, path) + "\n";
      contentBytes = Math.addExact(contentBytes, line.getBytes(StandardCharsets.UTF_8).length);
      if (contentBytes > ARTIFACT_MARKER_BYTES_MAX) {
        throw new IllegalStateException(
            "artifact marker exceeds " + ARTIFACT_MARKER_BYTES_MAX + " UTF-8 bytes");
      }
      content.append(line);
    }
    Path marker = directory.resolve(ARTIFACT_MARKER);
    Path temporary = directory.resolve("." + ARTIFACT_MARKER + ".tmp");
    Files.writeString(temporary, content, StandardOpenOption.CREATE_NEW);
    Files.move(temporary, marker, StandardCopyOption.ATOMIC_MOVE);
  }

  private static String artifactSignature(Path root, Path path) throws IOException {
    assert root != null;
    assert path != null;
    if (Files.isSymbolicLink(path)) {
      Path target = Files.readSymbolicLink(path);
      String text = target.toString().replace('\\', '/');
      if (!canonicalSymbolicLinkTarget(text)
          || !path.getParent().resolve(target).normalize().startsWith(root.normalize())
          || !path.toRealPath().startsWith(root.toRealPath())) {
        throw new IllegalStateException("generated symbolic link escapes artifact: " + path);
      }
      if (!Files.isRegularFile(path)) {
        throw new IllegalStateException("generated symbolic link is not a regular file: " + path);
      }
      return "link\t" + text;
    }
    if (!Files.isRegularFile(path)) {
      throw new IllegalStateException("generated artifact contains unsupported path: " + path);
    }
    long length = Files.size(path);
    if (length > FILE_BYTES_MAX) {
      throw new IllegalStateException("generated artifact file exceeds limit: " + path);
    }
    try {
      var digest = MessageDigest.getInstance("SHA-256");
      updateDigestFromFile(digest, path);
      return "file\t" + length + "\t" + HexFormat.of().formatHex(digest.digest());
    } catch (java.security.NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 unavailable", exception);
    }
  }

  private static boolean canonicalSymbolicLinkTarget(String value) {
    if (value.isEmpty()
        || value.indexOf('\t') >= 0
        || value.indexOf('\r') >= 0
        || value.indexOf('\n') >= 0
        || value.indexOf('\\') >= 0) return false;
    Path target;
    try {
      target = Path.of(value);
    } catch (RuntimeException exception) {
      return false;
    }
    return !target.isAbsolute() && target.normalize().toString().replace('\\', '/').equals(value);
  }

  private static void requireArtifactFingerprint(String fingerprint) {
    assert fingerprint != null;
    int bytes = fingerprint.getBytes(StandardCharsets.UTF_8).length;
    if (fingerprint.isEmpty()
        || fingerprint.indexOf('\t') >= 0
        || fingerprint.indexOf('\r') >= 0
        || fingerprint.indexOf('\n') >= 0
        || bytes > ARTIFACT_FINGERPRINT_BYTES_MAX) {
      throw new IllegalArgumentException("invalid artifact fingerprint");
    }
  }

  private static String artifactPath(Path root, Path path) {
    String relative = root.relativize(path).toString().replace('\\', '/');
    if (!canonicalArtifactPath(relative)) {
      throw new IllegalStateException("invalid generated artifact path: " + path);
    }
    return relative;
  }

  private static boolean canonicalArtifactPath(String relative) {
    if (relative.isEmpty()
        || relative.indexOf('\t') >= 0
        || relative.indexOf('\r') >= 0
        || relative.indexOf('\n') >= 0
        || relative.startsWith("/")) return false;
    Path path;
    try {
      path = Path.of(relative);
    } catch (RuntimeException exception) {
      return false;
    }
    return !path.isAbsolute()
        && !path.startsWith("..")
        && path.normalize().toString().replace('\\', '/').equals(relative);
  }

  private static Path stagingPath(Path output, String kind) {
    Path normalized = output.toAbsolutePath().normalize();
    String name = normalized.getFileName().toString();
    long sequence = STAGING_SEQUENCE.incrementAndGet();
    return normalized.resolveSibling(
        "." + name + "." + kind + "-" + ProcessHandle.current().pid() + "-" + sequence);
  }

  private static void recoverArtifactBackup(Path output) throws IOException {
    Path normalized = output.toAbsolutePath().normalize();
    Path parent = normalized.getParent();
    if (!Files.isDirectory(parent)) return;
    String prefix = "." + normalized.getFileName() + ".backup-";
    List<Path> backups =
        directoryEntries(parent, TREE_ENTRIES_MAX).stream()
            .filter(path -> path.getFileName().toString().startsWith(prefix))
            .toList();
    if (Files.exists(output)) {
      for (Path backup : backups) deleteGeneratedTree(backup);
      return;
    }
    if (backups.size() > 1) {
      throw new IllegalStateException(
          "multiple artifact backups require manual recovery: " + output);
    }
    if (backups.size() == 1) {
      Path backup = backups.getFirst();
      if (!Files.isRegularFile(backup.resolve(ARTIFACT_MARKER))) {
        throw new IllegalStateException("artifact backup is incomplete: " + backup);
      }
      Files.move(backup, output, StandardCopyOption.ATOMIC_MOVE);
    }
  }

  private static void deleteStaleStaging(Path output) throws IOException {
    Path normalized = output.toAbsolutePath().normalize();
    Path parent = normalized.getParent();
    if (!Files.isDirectory(parent)) return;
    String prefix = "." + normalized.getFileName() + ".stage-";
    for (Path path : directoryEntries(parent, TREE_ENTRIES_MAX)) {
      if (path.getFileName().toString().startsWith(prefix)) deleteGeneratedTree(path);
    }
  }

  private static List<String> remapArguments(List<String> arguments, Path from, Path to) {
    assert arguments != null;
    assert from != null;
    assert to != null;
    String source = from.toString();
    String target = to.toString();
    return arguments.stream().map(argument -> argument.replace(source, target)).toList();
  }

  static void updateDigestFromFileForTest(MessageDigest digest, Path path) throws IOException {
    updateDigestFromFile(digest, path);
  }

  private static void updateDigestFromFile(MessageDigest digest, Path path) throws IOException {
    assert digest != null;
    assert path != null;
    long fileBytes = Files.size(path);
    if (fileBytes > FILE_BYTES_MAX) {
      throw new IllegalStateException("file exceeds " + FILE_BYTES_MAX + " bytes: " + path);
    }
    byte[] buffer = new byte[COPY_BUFFER_BYTES];
    long fileBytesRead = 0;
    long readOperations = 0;
    long readOperationsMax = Math.addExact(fileBytes, 1);
    try (var input = Files.newInputStream(path)) {
      while (fileBytesRead < fileBytes && readOperations < readOperationsMax) {
        int requestedBytes = (int) Math.min(buffer.length, fileBytes - fileBytesRead);
        int readBytes = input.read(buffer, 0, requestedBytes);
        readOperations = Math.addExact(readOperations, 1);
        if (readBytes <= 0) throw new IllegalStateException("file changed while hashing: " + path);
        digest.update(buffer, 0, readBytes);
        fileBytesRead = Math.addExact(fileBytesRead, readBytes);
      }
    }
    if (fileBytesRead != fileBytes || Files.size(path) != fileBytes) {
      throw new IllegalStateException("file changed while hashing: " + path);
    }
    assert fileBytesRead <= FILE_BYTES_MAX;
  }

  static List<Path> treePathsForTest(Path root, int entriesMax) throws IOException {
    return treePaths(root, entriesMax);
  }

  private static List<Path> treePaths(Path root, int entriesMax) throws IOException {
    assert root != null;
    if (entriesMax <= 0 || entriesMax > TREE_ENTRIES_MAX) {
      throw new IllegalArgumentException("entriesMax must be 1.." + TREE_ENTRIES_MAX);
    }
    var result = new ArrayList<Path>();
    try (Stream<Path> paths = Files.walk(root)) {
      Iterator<Path> iterator = paths.iterator();
      while (iterator.hasNext()) {
        if (result.size() >= entriesMax) {
          throw new IllegalStateException("tree exceeds " + entriesMax + " entries: " + root);
        }
        result.add(iterator.next());
      }
    }
    assert result.size() <= entriesMax;
    return List.copyOf(result);
  }

  static TestSelection testSelectionForTest(List<String> paths) throws IOException {
    return testSelection(paths);
  }

  private static TestSelection testSelection(List<String> paths) throws IOException {
    var classNames = new TreeSet<String>();
    boolean buildTool = false;
    for (Path path :
        selectedFiles(
            paths,
            List.of(ROOT.resolve("tests")),
            candidate -> candidate.getFileName().toString().endsWith("Test.java"),
            "test source")) {
      if (path.equals(BUILD_TEST_SOURCE)) {
        buildTool = true;
      } else if (path.startsWith(JUNIT_TEST_SOURCES)) {
        String relative = JUNIT_TEST_SOURCES.relativize(path).toString();
        classNames.add(
            relative
                .substring(0, relative.length() - ".java".length())
                .replace('\\', '.')
                .replace('/', '.'));
      } else {
        throw new IllegalStateException("unknown test source: " + ROOT.relativize(path));
      }
    }
    return new TestSelection(buildTool, List.copyOf(classNames));
  }

  static List<Path> javaSourcePathsForTest(List<String> paths) throws IOException {
    return javaSourcePaths(paths);
  }

  private static List<Path> javaSourcePaths(List<String> paths) throws IOException {
    return selectedFiles(
        paths,
        List.of(ROOT.resolve("sources"), ROOT.resolve("tests"), ROOT.resolve("tools")),
        candidate -> candidate.getFileName().toString().endsWith(".java"),
        "Java source",
        true);
  }

  private static List<Path> selectedFiles(
      List<String> requestedPaths,
      List<Path> defaults,
      Predicate<Path> predicate,
      String description)
      throws IOException {
    return selectedFiles(requestedPaths, defaults, predicate, description, false);
  }

  private static List<Path> selectedFiles(
      List<String> requestedPaths,
      List<Path> defaults,
      Predicate<Path> predicate,
      String description,
      boolean ignoreNonMatchingFiles)
      throws IOException {
    assert requestedPaths != null;
    assert defaults != null;
    assert predicate != null;
    assert description != null;
    if (requestedPaths.size() > 256) throw new IllegalStateException("paths exceed 256 entries");
    var result = new TreeSet<Path>();
    List<Path> paths =
        requestedPaths.isEmpty()
            ? defaults
            : requestedPaths.stream().map(Build::resolveProjectPath).toList();
    int traversed = 0;
    for (Path path : paths) {
      if (Files.isSymbolicLink(path)) {
        throw new IllegalStateException("symbolic paths are not supported: " + path);
      }
      if (Files.isRegularFile(path)) {
        traversed = Math.addExact(traversed, 1);
        if (!predicate.test(path)) {
          if (ignoreNonMatchingFiles) continue;
          throw new IllegalStateException("not a " + description + ": " + path);
        }
        result.add(path);
        if (result.size() > COLLECTION_ENTRIES_MAX) {
          throw new IllegalStateException(
              "selected files exceed " + COLLECTION_ENTRIES_MAX + " entries");
        }
        continue;
      }
      if (!Files.isDirectory(path)) throw new IllegalStateException("path does not exist: " + path);

      int remaining = COLLECTION_ENTRIES_MAX - traversed;
      if (remaining <= 0) {
        throw new IllegalStateException(
            "selected paths exceed " + COLLECTION_ENTRIES_MAX + " entries");
      }
      List<Path> children = treePaths(path, remaining);
      traversed = Math.addExact(traversed, children.size());
      for (Path child : children) {
        if (Files.isSymbolicLink(child)) {
          throw new IllegalStateException("symbolic paths are not supported: " + child);
        }
        if (Files.isRegularFile(child) && predicate.test(child)) result.add(child);
      }
      if (result.size() > COLLECTION_ENTRIES_MAX) {
        throw new IllegalStateException(
            "selected files exceed " + COLLECTION_ENTRIES_MAX + " entries");
      }
    }
    if (result.isEmpty() && !ignoreNonMatchingFiles) {
      throw new IllegalStateException("no " + description + " files selected");
    }
    return List.copyOf(result);
  }

  private static Path resolveProjectPath(String value) {
    assert value != null;
    if (value.getBytes(StandardCharsets.UTF_8).length > ARGUMENT_BYTES_MAX) {
      throw new IllegalStateException("path exceeds " + ARGUMENT_BYTES_MAX + " UTF-8 bytes");
    }
    Path path = Path.of(value);
    path = (path.isAbsolute() ? path : ROOT.resolve(path)).normalize();
    if (!path.startsWith(ROOT)) throw new IllegalStateException("path escapes project: " + value);
    Path component = ROOT;
    if (Files.isSymbolicLink(component)) {
      throw new IllegalStateException("symbolic paths are not supported: " + component);
    }
    for (Path name : ROOT.relativize(path)) {
      component = component.resolve(name);
      if (Files.isSymbolicLink(component)) {
        throw new IllegalStateException("symbolic paths are not supported: " + component);
      }
      if (!Files.exists(component)) break;
    }
    return path;
  }

  static record TestSelection(boolean buildTool, List<String> classNames) {
    TestSelection {
      assert classNames != null;
      assert buildTool || !classNames.isEmpty();
      assert classNames.size() <= COLLECTION_ENTRIES_MAX;
    }
  }

  private static List<Path> directoryEntries(Path directory, int entriesMax) throws IOException {
    assert directory != null;
    assert entriesMax > 0 && entriesMax <= TREE_ENTRIES_MAX;
    var result = new ArrayList<Path>();
    try (Stream<Path> paths = Files.list(directory)) {
      Iterator<Path> iterator = paths.iterator();
      while (iterator.hasNext()) {
        if (result.size() >= entriesMax) {
          throw new IllegalStateException(
              "directory exceeds " + entriesMax + " entries: " + directory);
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
    assert arguments != null;
    if (arguments.size() > ARGUMENTS_MAX) {
      throw new IllegalStateException("arguments exceed " + ARGUMENTS_MAX + " entries");
    }
    var content =
        new StringBuilder(Math.min(ARGFILE_BYTES_MAX, Math.multiplyExact(arguments.size(), 32)));
    int contentBytes = 0;
    for (String argument : arguments) {
      assert argument != null;
      if (argument.indexOf('\r') >= 0 || argument.indexOf('\n') >= 0) {
        throw new IllegalStateException("argument contains a line break");
      }
      int argumentBytes = argument.getBytes(StandardCharsets.UTF_8).length;
      if (argumentBytes > ARGUMENT_BYTES_MAX) {
        throw new IllegalStateException("argument exceeds " + ARGUMENT_BYTES_MAX + " UTF-8 bytes");
      }
      String line = quoteArg(argument) + "\n";
      contentBytes = Math.addExact(contentBytes, line.getBytes(StandardCharsets.UTF_8).length);
      if (contentBytes > ARGFILE_BYTES_MAX) {
        throw new IllegalStateException(
            "argument file exceeds " + ARGFILE_BYTES_MAX + " UTF-8 bytes");
      }
      content.append(line);
    }
    assert content.toString().getBytes(StandardCharsets.UTF_8).length == contentBytes;
    return content.toString();
  }

  static int waitForProcessForTest(Process process, Duration timeout, Duration killTimeout)
      throws InterruptedException {
    return waitForProcess(process, timeout, killTimeout, false);
  }

  static int waitForProcessForTest(
      Process process, Duration timeout, Duration killTimeout, boolean forceAtTimeout)
      throws InterruptedException {
    return waitForProcess(process, timeout, killTimeout, forceAtTimeout);
  }

  private static int waitForProcess(
      Process process, Duration timeout, Duration killTimeout, boolean forceAtTimeout)
      throws InterruptedException {
    assert process != null;
    requirePositiveDuration(timeout, "timeout");
    requirePositiveDuration(killTimeout, "killTimeout");
    if (process.waitFor(timeout.toNanos(), TimeUnit.NANOSECONDS)) return process.exitValue();
    if (forceAtTimeout) {
      terminateForcibly(process, killTimeout);
    } else {
      terminate(process, killTimeout);
    }
    throw new IllegalStateException("process timed out after " + timeout);
  }

  private static void terminate(Process process, Duration killTimeout) throws InterruptedException {
    assert process != null;
    assert killTimeout != null && !killTimeout.isNegative() && !killTimeout.isZero();
    List<ProcessHandle> descendants = processDescendants(process);
    destroy(descendants, false);
    process.destroy();
    if (waitForProcessTree(process, descendants, killTimeout)) return;
    destroy(descendants, true);
    process.destroyForcibly();
    if (!waitForProcessTree(process, descendants, killTimeout)) {
      throw new IllegalStateException("process tree did not terminate");
    }
  }

  private static void terminateForcibly(Process process, Duration killTimeout)
      throws InterruptedException {
    assert process != null;
    assert killTimeout != null && !killTimeout.isNegative() && !killTimeout.isZero();
    List<ProcessHandle> descendants = processDescendants(process);
    destroy(descendants, true);
    process.destroyForcibly();
    if (!waitForProcessTree(process, descendants, killTimeout)) {
      throw new IllegalStateException("process tree did not terminate");
    }
    assert !process.isAlive();
    assert descendants.stream().noneMatch(ProcessHandle::isAlive);
  }

  private static List<ProcessHandle> processDescendants(Process process) {
    assert process != null;
    var descendants =
        new ArrayList<>(
            process.toHandle().descendants().limit(COLLECTION_ENTRIES_MAX + 1L).toList());
    descendants.sort(Comparator.comparingInt(Build::processDepth).reversed());
    if (descendants.size() > COLLECTION_ENTRIES_MAX) {
      destroy(descendants, true);
      process.destroyForcibly();
      throw new IllegalStateException(
          "process descendants exceed " + COLLECTION_ENTRIES_MAX + " entries");
    }
    return List.copyOf(descendants);
  }

  private static int processDepth(ProcessHandle process) {
    assert process != null;
    int depth = 0;
    Optional<ProcessHandle> parent = process.parent();
    while (parent.isPresent() && depth < COLLECTION_ENTRIES_MAX) {
      depth = Math.addExact(depth, 1);
      parent = parent.orElseThrow().parent();
    }
    return depth;
  }

  private static void destroy(List<ProcessHandle> descendants, boolean forcibly) {
    assert descendants != null;
    for (ProcessHandle descendant : descendants) {
      if (!descendant.isAlive()) continue;
      if (forcibly) descendant.destroyForcibly();
      else descendant.destroy();
    }
  }

  private static boolean waitForProcessTree(
      Process process, List<ProcessHandle> descendants, Duration timeout)
      throws InterruptedException {
    assert process != null;
    assert descendants != null;
    assert timeout != null && !timeout.isNegative() && !timeout.isZero();
    long deadline = Math.addExact(System.nanoTime(), timeout.toNanos());
    while (process.isAlive() || descendants.stream().anyMatch(ProcessHandle::isAlive)) {
      long remaining = deadline - System.nanoTime();
      if (remaining <= 0) return false;
      process.waitFor(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(10)), TimeUnit.NANOSECONDS);
      if (!process.isAlive() && descendants.stream().anyMatch(ProcessHandle::isAlive)) {
        Thread.sleep(1);
      }
    }
    return true;
  }

  private static void requirePositiveDuration(Duration duration, String name) {
    assert duration != null;
    if (duration.isNegative() || duration.isZero() || duration.compareTo(PROCESS_TIMEOUT_MAX) > 0) {
      throw new IllegalArgumentException(
          name + " must be positive and at most " + PROCESS_TIMEOUT_MAX);
    }
  }

  private static void requireAssertions() {
    if (!Build.class.desiredAssertionStatus()) {
      throw new IllegalStateException("Java assertions must be enabled with -ea");
    }
  }

  private static void printCached(String executable, Path argFile, long started) {
    printInvocation(executable, argFile);
    printCompletion("cached", System.nanoTime() - started, null);
  }

  private static void printInvocation(String executable, Path argFile) {
    String directory =
        "@" + ROOT.relativize(argFile.getParent()).toString().replace('\\', '/') + "/";
    System.out.println(
        emphasize(toolName(executable))
            + "  "
            + dim(directory)
            + emphasize(argFile.getFileName().toString()));
  }

  private static void printInvocation(String executable, String name) {
    assert name != null && !name.isBlank();
    System.out.println(emphasize(toolName(executable)) + "  " + dim(name));
  }

  private static String toolName(String executable) {
    assert executable != null && !executable.isBlank();
    return Path.of(executable).getFileName().toString().replaceFirst("(?i)\\.exe$", "");
  }

  private static void printCompletion(String state, long elapsedNanos, String cpu) {
    String statePadded = String.format(Locale.ROOT, "%-7s", state);
    String styledState = state.equals("failed") ? emphasize(statePadded) : dim(statePadded);
    String cpuSuffix = cpu == null ? "" : dim("  cpu ") + emphasize(cpu);
    System.out.println(
        "       " + styledState + " " + emphasize(formatDuration(elapsedNanos)) + cpuSuffix);
  }

  private static void printTotal(long elapsedNanos) {
    assert elapsedNanos >= 0;
    System.out.println(dim("total  ") + emphasize(formatDuration(elapsedNanos)));
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

  private static String googleJavaFormatExecutable() {
    return isWindows() ? "google-java-format.exe" : "google-java-format";
  }

  private static String jlinkExecutable() {
    return Path.of(System.getProperty("java.home"), "bin", isWindows() ? "jlink.exe" : "jlink")
        .toString();
  }

  private static boolean isWindows() {
    return System.getProperty("os.name").startsWith("Windows");
  }
}
