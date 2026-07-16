package tools;

import java.io.Console;
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
import java.util.Optional;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.stream.Stream;

public final class Build {
  private static final Path ROOT = Path.of("").toAbsolutePath().normalize();
  private static final Path OUTPUT = ROOT.resolve("output");
  private static final Path MODULES = OUTPUT.resolve("modules");
  private static final Path APP_MODULE = MODULES.resolve("toktrak");
  private static final Path TEST_MODULE = MODULES.resolve("toktrak.tests");
  private static final Path MAIN_DEPS = OUTPUT.resolve("deps/main");
  private static final Path TEST_DEPS = OUTPUT.resolve("deps/test");
  private static final Path BUILD_DEPS = OUTPUT.resolve("deps/build");
  private static final Path REFASTER_DEPS = OUTPUT.resolve("deps/refaster");
  private static final Path RUNTIMES = OUTPUT.resolve("runtimes");
  private static final Path IDE = OUTPUT.resolve("ide");
  private static final Path ECLIPSE_IDE = IDE.resolve("eclipse");
  private static final Path ARGFILES = OUTPUT.resolve("args");
  private static final Path BUILD_TESTS = OUTPUT.resolve("build-tests");
  private static final Path REFASTER_OUTPUT = OUTPUT.resolve("refaster");
  private static final Path REFASTER_CLASSES = REFASTER_OUTPUT.resolve("classes");
  private static final Path REFASTER_APPLY_MODULES = REFASTER_OUTPUT.resolve("apply-modules");
  private static final Path REFASTER_APPLY_BUILD = REFASTER_OUTPUT.resolve("apply-build");
  private static final Path REFASTER_RULE = REFASTER_OUTPUT.resolve("toktrak.refaster");
  private static final Path REFASTER_SOURCE = ROOT.resolve("tools/refaster/Rules.java");
  private static final Path JUNIT_TEST_SOURCES = ROOT.resolve("tests/toktrak.tests");
  private static final Path BUILD_TEST_SOURCE = ROOT.resolve("tests/tools/BuildTest.java");
  private static final Path ERROR_PRONE_CONFIG = ROOT.resolve("sources/error-prone.cfg");
  private static final long FILE_BYTES_MAX = 512L * 1024 * 1024;
  private static final int TREE_ENTRIES_MAX = 100_000;
  private static final int COLLECTION_ENTRIES_MAX = 10_000;
  private static final int ARGUMENTS_MAX = 10_000;
  private static final int ARGUMENT_BYTES_MAX = 32 * 1024;
  private static final int ARGFILE_BYTES_MAX = 8 * 1024 * 1024;
  // OS command-line limits vary. Keep formatter commands below 24 KiB and 128 source files;
  // split larger source sets into bounded batches so repository growth cannot break fmt/check.
  private static final int COMMAND_BYTES_MAX = 24 * 1024;
  private static final int FORMAT_BATCH_FILES_MAX = 128;
  private static final int COPY_BUFFER_BYTES = 64 * 1024;
  private static final int STAMP_BYTES_MAX = 128;
  private static final int IDE_FILE_BYTES_MAX = 1024 * 1024;
  private static final int GIT_STATUS_BYTES_MAX = 1024 * 1024;
  private static final Duration PROCESS_TIMEOUT = Duration.ofMinutes(10);
  private static final Duration UNIT_TEST_TIMEOUT = Duration.ofSeconds(30);
  private static final Duration PROCESS_KILL_TIMEOUT = Duration.ofSeconds(5);
  private static final boolean ANSI =
      Optional.ofNullable(System.console()).filter(Console::isTerminal).isPresent()
          && System.getenv("NO_COLOR") == null;
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
    long started = System.nanoTime();
    try {
      requireAssertions();
      assert args != null;
      if (args.length == 0) {
        throw new IllegalStateException(
            "command required: clean, fmt, check, test, refactor, ci, verify, ide, dev, or prod");
      }
      if (args.length > 256)
        throw new IllegalStateException("command arguments exceed 256 entries");
      deleteTree(ARGFILES);
      switch (args[0]) {
        case "clean" -> clean();
        case "fmt" -> formatCommand(List.of(args).subList(1, args.length));
        case "check" -> check();
        case "test" -> testCommand(List.of(args).subList(1, args.length));
        case "refactor" -> refactor();
        case "ci" -> ci();
        case "verify" -> verify();
        case "ide" -> ideCommand(List.of(args).subList(1, args.length));
        case "dev" -> dev(List.of(args).subList(1, args.length));
        case "prod" -> jlinkProd();
        default -> throw new IllegalStateException("unknown command: " + args[0]);
      }
    } finally {
      printTotal(System.nanoTime() - started);
    }
  }

  private static void clean() throws IOException {
    deleteTree(MODULES);
    deleteTree(MAIN_DEPS);
    deleteTree(TEST_DEPS);
    deleteTree(BUILD_DEPS);
    deleteTree(REFASTER_DEPS);
    deleteTree(RUNTIMES);
    deleteTree(IDE);
    deleteTree(ARGFILES);
    deleteTree(BUILD_TESTS);
    deleteTree(REFASTER_OUTPUT);
  }

  private static void deps() throws Exception {
    ensureDependency(
        "sources/main-deps.txt", MAIN_DEPS, "resolve-toktrak-production-dependencies", true);
    ensureDependency("sources/test-deps.txt", TEST_DEPS, "resolve-toktrak-test-dependencies", true);
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
    Path stamp = output.resolve(".fingerprint");
    if (Files.isDirectory(output) && Files.exists(stamp) && readStamp(stamp).equals(fingerprint)) {
      printCached(javaExecutable(), argFile, started);
      return;
    }

    deleteTree(output);
    Files.createDirectories(output);
    runArgFile(javaExecutable(), argFile);
    writeStamp(stamp, fingerprint);
  }

  private static void ideCommand(List<String> arguments) throws Exception {
    assert arguments != null;
    if (arguments.isEmpty()) {
      throw new IllegalStateException(
          "IntelliJ IDE generation is not implemented; use 'ide eclipse'");
    }
    if (arguments.size() != 1) {
      throw new IllegalArgumentException("ide requires zero or one target");
    }
    switch (arguments.getFirst()) {
      case "--help" -> System.out.println("usage: mise run ide [eclipse|intellij]");
      case "eclipse" -> generateEclipseProjects();
      case "intellij" ->
          throw new IllegalStateException("IntelliJ IDE generation is not implemented");
      default -> throw new IllegalArgumentException("unknown IDE target: " + arguments.getFirst());
    }
  }

  private static void generateEclipseProjects() throws Exception {
    ensureDependency(
        "sources/main-deps.txt", MAIN_DEPS, "resolve-toktrak-production-dependencies", true);
    ensureDependency("sources/test-deps.txt", TEST_DEPS, "resolve-toktrak-test-dependencies", true);
    ensureRefasterDependencies();
    verifyModules(MAIN_DEPS);
    verifyModules(TEST_DEPS);
    generateEclipseProjects(
        ROOT,
        ECLIPSE_IDE,
        jarPaths(List.of(MAIN_DEPS)),
        jarPaths(List.of(TEST_DEPS)),
        refasterJar());
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
    if (!output.equals(root.resolve("output/ide/eclipse"))) {
      throw new IllegalArgumentException("Eclipse output must be output/ide/eclipse");
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

  private static String eclipseAppClasspath(List<Path> jars) {
    var entries = new StringBuilder();
    entries.append("  <classpathentry kind=\"src\" path=\"src\" output=\"bin/main\"/>\n");
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
    assert owner != null;
    assert file != null;
    assert content != null;
    Path normalizedOwner = owner.toAbsolutePath().normalize();
    Path normalizedFile = file.toAbsolutePath().normalize();
    if (!normalizedFile.startsWith(normalizedOwner)) {
      throw new IllegalArgumentException("IDE file escapes output directory");
    }
    int contentBytes = content.getBytes(StandardCharsets.UTF_8).length;
    if (contentBytes > IDE_FILE_BYTES_MAX) {
      throw new IllegalStateException("IDE file exceeds " + IDE_FILE_BYTES_MAX + " bytes");
    }
    Files.createDirectories(normalizedFile.getParent());
    Files.writeString(
        normalizedFile, content, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    assert Files.size(normalizedFile) == contentBytes;
  }

  private static String xml(String value) {
    assert value != null;
    return value
        .replace("&", "&amp;")
        .replace("\"", "&quot;")
        .replace("<", "&lt;")
        .replace(">", "&gt;");
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
    compile();
  }

  private static void compile() throws Exception {
    deps();
    compileRefaster();
    List<String> arguments = new ArrayList<>();
    arguments.add("-Xlint:all");
    arguments.add("-Werror");
    arguments.add("-g");
    addErrorProne(arguments);
    addModuleSourcePaths(arguments);
    arguments.add("--module-path");
    arguments.add(modulePath(List.of(MAIN_DEPS, TEST_DEPS)));
    addExports(arguments);
    arguments.add("-d");
    arguments.add(MODULES.toString());
    arguments.add("--module");
    arguments.add("toktrak,toktrak.tests");
    Path argFile = writeArgFile("compile-toktrak-and-test-modules", arguments);
    List<String> fingerprintArguments = new ArrayList<>(arguments);
    fingerprintArguments.addAll(errorProneJvmArguments());
    String fingerprint =
        compilationFingerprint(
            "modules",
            List.of(
                ROOT.resolve("sources/toktrak"),
                ROOT.resolve("tests/toktrak.tests"),
                ERROR_PRONE_CONFIG),
            List.of(MAIN_DEPS, TEST_DEPS, BUILD_DEPS),
            fingerprintArguments);
    Path stamp = MODULES.resolve(".compile-fingerprint");
    long started = System.nanoTime();
    if (cacheHit(
        MODULES,
        stamp,
        fingerprint,
        List.of(
            APP_MODULE.resolve("module-info.class"), TEST_MODULE.resolve("module-info.class")))) {
      printCached(javacExecutable(), argFile, started);
      return;
    }
    deleteTree(MODULES);
    Files.createDirectories(MODULES);
    runJavacArgFile(argFile);
    writeStamp(stamp, fingerprint);
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
    Path stamp = REFASTER_OUTPUT.resolve(".compile-fingerprint");
    List<Path> requiredFiles =
        List.of(REFASTER_CLASSES.resolve("tools/refaster/Rules.class"), REFASTER_RULE);
    long started = System.nanoTime();
    if (cacheHit(REFASTER_OUTPUT, stamp, fingerprint, requiredFiles)
        && Files.size(REFASTER_RULE) > 0) {
      printCached(javacExecutable(), argFile, started);
      return;
    }
    deleteTree(REFASTER_CLASSES);
    Files.deleteIfExists(REFASTER_RULE);
    Files.createDirectories(REFASTER_CLASSES);
    runJavacArgFile(argFile);
    if (!Files.isRegularFile(REFASTER_RULE) || Files.size(REFASTER_RULE) == 0) {
      throw new IllegalStateException("Refaster rule was not generated");
    }
    writeStamp(stamp, fingerprint);
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
    deps();
    compileRefaster();
    applyRefasterToModules();
    applyRefasterToBuildTool();
    format(true, List.of());
  }

  private static void applyRefasterToModules() throws Exception {
    var arguments = new ArrayList<String>();
    arguments.addAll(refasterPatchArguments());
    addModuleSourcePaths(arguments);
    arguments.add("--module-path");
    arguments.add(modulePath(List.of(MAIN_DEPS, TEST_DEPS)));
    addExports(arguments);
    arguments.add("-d");
    arguments.add(REFASTER_APPLY_MODULES.toString());
    arguments.add("--module");
    arguments.add("toktrak,toktrak.tests");
    deleteTree(REFASTER_APPLY_MODULES);
    Files.createDirectories(REFASTER_APPLY_MODULES);
    runJavacArgFile(writeArgFile("apply-refaster-to-modules", arguments));
  }

  private static void applyRefasterToBuildTool() throws Exception {
    var arguments = new ArrayList<String>();
    arguments.addAll(refasterPatchArguments());
    arguments.add("-d");
    arguments.add(REFASTER_APPLY_BUILD.toString());
    arguments.add(ROOT.resolve("tools/Build.java").toString());
    arguments.add(BUILD_TEST_SOURCE.toString());
    deleteTree(REFASTER_APPLY_BUILD);
    Files.createDirectories(REFASTER_APPLY_BUILD);
    runJavacArgFile(writeArgFile("apply-refaster-to-build-tool", arguments));
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
    Process process =
        new ProcessBuilder("git", "status", "--porcelain=v1", "--untracked-files=all")
            .directory(repository.toFile())
            .redirectErrorStream(true)
            .start();
    var output = new AtomicReference<byte[]>();
    var readFailure = new AtomicReference<IOException>();
    Thread reader =
        Thread.ofVirtual()
            .name("toktrak-git-status-reader")
            .start(
                () -> {
                  try {
                    byte[] bytes = process.getInputStream().readNBytes(GIT_STATUS_BYTES_MAX + 1);
                    output.set(bytes);
                    if (bytes.length > GIT_STATUS_BYTES_MAX) process.destroyForcibly();
                  } catch (IOException exception) {
                    readFailure.set(exception);
                    process.destroyForcibly();
                  }
                });
    int exitCode;
    try {
      exitCode = waitForProcess(process, PROCESS_TIMEOUT, PROCESS_KILL_TIMEOUT, false);
    } catch (InterruptedException exception) {
      terminate(process, PROCESS_KILL_TIMEOUT);
      reader.interrupt();
      Thread.currentThread().interrupt();
      throw exception;
    }
    if (!reader.join(PROCESS_KILL_TIMEOUT)) {
      process.destroyForcibly();
      throw new IllegalStateException("Git status reader did not terminate");
    }
    if (readFailure.get() != null) {
      throw new IllegalStateException("cannot read Git status", readFailure.get());
    }
    byte[] bytes = output.get();
    assert bytes != null;
    if (bytes.length > GIT_STATUS_BYTES_MAX) {
      throw new IllegalStateException(
          "Git status exceeds " + GIT_STATUS_BYTES_MAX + " UTF-8 bytes");
    }
    if (exitCode != 0) throw new IllegalStateException("Git status failed: " + exitCode);
    try {
      String status =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(bytes))
              .toString();
      return !status.isEmpty();
    } catch (CharacterCodingException exception) {
      throw new IllegalStateException("Git status is not valid UTF-8", exception);
    }
  }

  private static void verify() throws Exception {
    check();
    runTests(testSelection(List.of()));
  }

  private static void runTests(TestSelection selection) throws Exception {
    assert selection != null;
    if (selection.buildTool()) testBuildTool();
    if (selection.classNames().isEmpty()) return;
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
    runTestGroup(runtime, arguments, selection.classNames(), "--unit");
    runTestGroup(runtime, arguments, selection.classNames(), "--tagged");
  }

  private static void runTestGroup(
      Path runtime, List<String> baseArguments, List<String> classNames, String group)
      throws Exception {
    Duration timeout = testTimeout(group);
    var arguments = new ArrayList<>(baseArguments);
    arguments.add(group);
    arguments.addAll(classNames);
    runArgFile(
        runtimeJava(runtime),
        "run-toktrak-" + group.substring(2) + "-test-suite",
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
    compileArguments.add(BUILD_TESTS.toString());
    compileArguments.add(sources.get(0).toString());
    compileArguments.add(sources.get(1).toString());
    Path compileArgFile = writeArgFile("compile-build-tool-tests", compileArguments);
    List<Path> fingerprintSources = new ArrayList<>(sources);
    fingerprintSources.add(ERROR_PRONE_CONFIG);
    fingerprintSources.add(REFASTER_SOURCE);
    fingerprintSources.add(REFASTER_RULE);
    String compileFingerprint =
        compilationFingerprint(
            "build-tool-tests",
            fingerprintSources,
            List.of(BUILD_DEPS, REFASTER_DEPS),
            compileArguments);
    Path compileStamp = BUILD_TESTS.resolve(".compile-fingerprint");
    List<Path> requiredClasses =
        List.of(
            BUILD_TESTS.resolve("tools/Build.class"), BUILD_TESTS.resolve("tools/BuildTest.class"));
    long compileStarted = System.nanoTime();
    if (cacheHit(BUILD_TESTS, compileStamp, compileFingerprint, requiredClasses)) {
      printCached(javacExecutable(), compileArgFile, compileStarted);
    } else {
      deleteTree(BUILD_TESTS);
      Files.createDirectories(BUILD_TESTS);
      runJavacArgFile(compileArgFile);
      writeStamp(compileStamp, compileFingerprint);
    }

    List<String> testArguments = List.of("-ea", "-cp", BUILD_TESTS.toString(), "tools.BuildTest");
    Path testArgFile = writeArgFile("run-build-tool-tests", testArguments);
    String testFingerprint = testResultFingerprint(compileFingerprint, testArguments);
    Path testStamp = BUILD_TESTS.resolve(".test-fingerprint");
    long testStarted = System.nanoTime();
    if (cacheHit(BUILD_TESTS, testStamp, testFingerprint, requiredClasses)) {
      printCached(javaExecutable(), testArgFile, testStarted);
    } else {
      runArgFile(javaExecutable(), testArgFile);
      writeStamp(testStamp, testFingerprint);
    }
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
    assert name != null;
    assert dependencyDirectories != null;
    assert roots != null;
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
    writeStamp(stamp, fingerprint);
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
    for (Path jar : jarPaths(List.of(directory))) moduleName(jar);
  }

  private static List<String> moduleNames(List<Path> directories, List<String> additional)
      throws IOException {
    assert additional != null;
    requireCollectionSize(additional, "additional modules");
    var names = new ArrayList<String>();
    for (Path jar : jarPaths(directories)) {
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
    return jarPaths(List.of(directory)).stream().map(Build::moduleName).toList();
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
        "-Xplugin:ErrorProne @" + ERROR_PRONE_CONFIG + pluginArguments);
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
      if (Files.isDirectory(entry) && !entry.equals(MODULES)) {
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

  private static void deleteTree(Path path) throws IOException {
    assert path != null;
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

  static boolean cacheHitForTest(
      Path directory, Path stamp, String fingerprint, List<Path> requiredFiles) throws IOException {
    return cacheHit(directory, stamp, fingerprint, requiredFiles);
  }

  private static boolean cacheHit(
      Path directory, Path stamp, String fingerprint, List<Path> requiredFiles) throws IOException {
    assert directory != null;
    assert stamp != null;
    assert fingerprint != null;
    assert requiredFiles != null;
    requireCollectionSize(requiredFiles, "required cache files");
    if (!Files.isDirectory(directory) || !Files.isRegularFile(stamp)) return false;
    for (Path requiredFile : requiredFiles) {
      if (!requiredFile.normalize().startsWith(directory.normalize())) {
        throw new IllegalArgumentException("required cache file escapes directory");
      }
      if (!Files.isRegularFile(requiredFile)) return false;
    }
    return readStamp(stamp).equals(fingerprint);
  }

  private static void writeStamp(Path stamp, String fingerprint) throws IOException {
    assert stamp != null;
    assert fingerprint != null;
    if (fingerprint.getBytes(StandardCharsets.UTF_8).length > STAMP_BYTES_MAX) {
      throw new IllegalStateException("fingerprint exceeds " + STAMP_BYTES_MAX + " UTF-8 bytes");
    }
    Files.writeString(
        stamp, fingerprint, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    assert Files.size(stamp) <= STAMP_BYTES_MAX;
  }

  static String readStampForTest(Path path) throws IOException {
    return readStamp(path);
  }

  private static String readStamp(Path path) throws IOException {
    assert path != null;
    byte[] bytes;
    try (var input = Files.newInputStream(path)) {
      bytes = input.readNBytes(STAMP_BYTES_MAX + 1);
      if (bytes.length > STAMP_BYTES_MAX || input.read() >= 0) {
        throw new IllegalStateException("stamp exceeds " + STAMP_BYTES_MAX + " UTF-8 bytes");
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

  private static List<Path> javaSourcePaths(List<String> paths) throws IOException {
    return selectedFiles(
        paths,
        List.of(ROOT.resolve("sources"), ROOT.resolve("tests"), ROOT.resolve("tools")),
        candidate -> candidate.getFileName().toString().endsWith(".java"),
        "Java source");
  }

  private static List<Path> selectedFiles(
      List<String> requestedPaths,
      List<Path> defaults,
      Predicate<Path> predicate,
      String description)
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
    if (result.isEmpty()) {
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
    process.destroy();
    if (process.waitFor(killTimeout.toNanos(), TimeUnit.NANOSECONDS)) return;
    terminateForcibly(process, killTimeout);
  }

  private static void terminateForcibly(Process process, Duration killTimeout)
      throws InterruptedException {
    assert process != null;
    assert killTimeout != null && !killTimeout.isNegative() && !killTimeout.isZero();
    process.destroyForcibly();
    if (!process.waitFor(killTimeout.toNanos(), TimeUnit.NANOSECONDS)) {
      throw new IllegalStateException("process did not terminate");
    }
    assert !process.isAlive();
  }

  private static void requirePositiveDuration(Duration duration, String name) {
    assert duration != null;
    if (duration.isNegative() || duration.isZero() || duration.compareTo(PROCESS_TIMEOUT) > 0) {
      throw new IllegalArgumentException(name + " must be positive and at most " + PROCESS_TIMEOUT);
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
