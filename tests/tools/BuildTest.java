package tools;

import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class BuildTest {
  private BuildTest() {}

  public static void main(String[] args) throws Exception {
    requireAssertions();
    if (args.length == 1) {
      if (args[0].equals("done")) return;
      if (args[0].equals("sleep")) {
        Thread.sleep(Duration.ofMinutes(1));
        return;
      }
      throw new IllegalArgumentException("unexpected argument: " + args[0]);
    }
    if (args.length != 0) throw new IllegalArgumentException("unexpected arguments");
    rejectsOversizedHashInput();
    rejectsTraversalAboveLimit();
    rejectsArgumentLimits();
    generatesEclipseProjects();
    generatesIntellijProjects();
    selectsTestsByFileAndDirectory();
    rejectsNonTestSelection();
    rejectsOversizedStamp();
    rejectsInvalidUtf8Stamp();
    validatesCacheArtifacts();
    waitsForNormalProcess();
    passesArgumentsWithSpaces();
    batchesFormatterSources();
    rejectsOversizedCommand();
    appliesWindowsOsNameRule();
    detectsDirtyGitTree();
    assignsTestGroupTimeouts();
    forceTerminatesHardTimedOutProcess();
    terminatesTimedOutProcess();
  }

  private static void rejectsOversizedHashInput() throws Exception {
    Path path = Files.createTempFile("toktrak-build-large-", ".bin");
    try {
      try (var channel = FileChannel.open(path, StandardOpenOption.WRITE)) {
        channel.position(512L * 1024 * 1024);
        channel.write(ByteBuffer.wrap(new byte[] {0}));
      }
      var digest = MessageDigest.getInstance("SHA-256");
      expectFailure(
          () -> Build.updateDigestFromFileForTest(digest, path), "file exceeds 536870912 bytes");
    } finally {
      Files.deleteIfExists(path);
    }
  }

  private static void rejectsTraversalAboveLimit() throws Exception {
    Path directory = Files.createTempDirectory("toktrak-build-tree-");
    try {
      for (int index = 0; index < 4; index++)
        Files.createFile(directory.resolve(Integer.toString(index)));
      expectFailure(() -> Build.treePathsForTest(directory, 3), "tree exceeds 3 entries");
    } finally {
      for (int index = 0; index < 4; index++)
        Files.deleteIfExists(directory.resolve(Integer.toString(index)));
      Files.deleteIfExists(directory);
    }
  }

  private static void rejectsArgumentLimits() {
    expectFailure(
        () -> Build.argumentFileContentForTest(Collections.nCopies(10_001, "x")),
        "arguments exceed 10000 entries");
    expectFailure(
        () -> Build.argumentFileContentForTest(List.of("x".repeat(32 * 1024 + 1))),
        "argument exceeds 32768 UTF-8 bytes");
    expectFailure(
        () -> Build.argumentFileContentForTest(Collections.nCopies(300, "x".repeat(32 * 1024))),
        "argument file exceeds 8388608 UTF-8 bytes");
  }

  private static void generatesEclipseProjects() throws Exception {
    Path root = Files.createTempDirectory("toktrak-ide-");
    try {
      for (String directory :
          List.of(
              "sources/toktrak", "tests/toktrak.tests", "tools/refaster", "tests/tools", "deps")) {
        Files.createDirectories(root.resolve(directory));
      }
      for (String file :
          List.of(
              "sources/toktrak/module-info.java",
              "tests/toktrak.tests/module-info.java",
              "tools/Build.java",
              "tools/refaster/Rules.java",
              "tests/tools/BuildTest.java",
              "deps/main.jar",
              "deps/test.jar",
              "deps/error_prone_refaster-2.50.0.jar")) {
        Files.createFile(root.resolve(file));
      }
      Path output = root.resolve("output/ide/eclipse");
      Build.generateEclipseProjectsForTest(
          root,
          output,
          List.of(root.resolve("deps/main.jar")),
          List.of(root.resolve("deps/test.jar")),
          root.resolve("deps/error_prone_refaster-2.50.0.jar"));

      var parser = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder();
      for (String project : List.of("toktrak", "toktrak.tests", "toktrak.build")) {
        Path directory = output.resolve(project);
        parser.parse(directory.resolve(".project").toFile());
        parser.parse(directory.resolve(".classpath").toFile());
        if (!Files.isRegularFile(directory.resolve(".settings/org.eclipse.core.resources.prefs"))
            || !Files.isRegularFile(directory.resolve(".settings/org.eclipse.jdt.core.prefs"))) {
          throw new AssertionError("missing Eclipse settings: " + project);
        }
      }
      String generated =
          Files.readString(output.resolve("toktrak/.project"))
              + Files.readString(output.resolve("toktrak/.classpath"))
              + Files.readString(output.resolve("toktrak.tests/.project"))
              + Files.readString(output.resolve("toktrak.tests/.classpath"))
              + Files.readString(output.resolve("toktrak.build/.project"))
              + Files.readString(output.resolve("toktrak.build/.classpath"));
      for (String expected :
          List.of(
              "<name>toktrak</name>",
              "<name>toktrak.tests</name>",
              "<name>toktrak.build</name>",
              "name=\"module\" value=\"true\"",
              "name=\"test\" value=\"true\"",
              "name=\"add-exports\"",
              "JavaSE-26",
              "src/tools",
              "test/tools",
              "error_prone_refaster-2.50.0.jar")) {
        if (!generated.contains(expected)) {
          throw new AssertionError("missing Eclipse metadata: " + expected);
        }
      }
      if (generated.contains("output/modules") || generated.contains("output/runtimes")) {
        throw new AssertionError("Eclipse metadata references authoritative output");
      }
    } finally {
      List<Path> paths = Build.treePathsForTest(root, 1_000);
      for (int index = paths.size() - 1; index >= 0; index--) {
        Files.deleteIfExists(paths.get(index));
      }
    }
  }

  private static void generatesIntellijProjects() throws Exception {
    Path root = Files.createTempDirectory("toktrak-intellij-");
    try {
      for (String directory :
          List.of(
              "sources/toktrak", "tests/toktrak.tests", "tools/refaster", "tests/tools", "deps")) {
        Files.createDirectories(root.resolve(directory));
      }
      for (String file :
          List.of(
              "sources/toktrak/module-info.java",
              "tests/toktrak.tests/module-info.java",
              "tools/Build.java",
              "tools/refaster/Rules.java",
              "tests/tools/BuildTest.java",
              "deps/main.jar",
              "deps/test.jar",
              "deps/error_prone_refaster-2.50.0.jar")) {
        Files.createFile(root.resolve(file));
      }
      Path idea = root.resolve(".idea");
      Files.createDirectories(idea);
      Path workspace = idea.resolve("workspace.xml");
      Files.writeString(workspace, "user-owned");
      Build.generateIntellijProjectsForTest(
          root,
          idea,
          List.of(root.resolve("deps/main.jar")),
          List.of(root.resolve("deps/test.jar")),
          root.resolve("deps/error_prone_refaster-2.50.0.jar"));

      var parser = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder();
      for (String file :
          List.of(
              "modules.xml",
              "misc.xml",
              "compiler.xml",
              "modules/toktrak.iml",
              "modules/toktrak.tests.iml",
              "modules/toktrak.build.iml")) {
        parser.parse(idea.resolve(file).toFile());
      }
      String generated =
          Files.readString(idea.resolve("modules.xml"))
              + Files.readString(idea.resolve("misc.xml"))
              + Files.readString(idea.resolve("compiler.xml"))
              + Files.readString(idea.resolve("modules/toktrak.iml"))
              + Files.readString(idea.resolve("modules/toktrak.tests.iml"))
              + Files.readString(idea.resolve("modules/toktrak.build.iml"));
      for (String expected :
          List.of(
              "toktrak.iml",
              "toktrak.tests.iml",
              "toktrak.build.iml",
              "languageLevel=\"JDK_26\"",
              "isTestSource=\"true\"",
              "packagePrefix=\"tools\"",
              "scope=\"TEST\"",
              "type=\"module-library\"",
              "module-name=\"toktrak\"",
              "ADDITIONAL_OPTIONS_OVERRIDE",
              "--add-exports=toktrak/toktrak.dev=toktrak.tests",
              "output/ide/intellij")) {
        if (!generated.contains(expected)) {
          throw new AssertionError("missing IntelliJ metadata: " + expected);
        }
      }
      if (generated.contains("output/modules") || generated.contains("output/runtimes")) {
        throw new AssertionError("IntelliJ metadata references authoritative output");
      }
      if (!Files.readString(workspace).equals("user-owned")) {
        throw new AssertionError("IntelliJ generation replaced workspace.xml");
      }
    } finally {
      List<Path> paths = Build.treePathsForTest(root, 1_000);
      for (int index = paths.size() - 1; index >= 0; index--) {
        Files.deleteIfExists(paths.get(index));
      }
    }
  }

  private static void selectsTestsByFileAndDirectory() throws Exception {
    Build.TestSelection junit =
        Build.testSelectionForTest(List.of("tests/toktrak.tests/toktrak/tests/ConfigTest.java"));
    if (junit.buildTool() || !junit.classNames().equals(List.of("toktrak.tests.ConfigTest"))) {
      throw new AssertionError("unexpected JUnit selection: " + junit);
    }
    Build.TestSelection build = Build.testSelectionForTest(List.of("tests/tools"));
    if (!build.buildTool() || !build.classNames().isEmpty()) {
      throw new AssertionError("unexpected build test selection: " + build);
    }
  }

  private static void rejectsNonTestSelection() {
    expectFailure(
        () -> Build.testSelectionForTest(List.of("sources/toktrak/toktrak/Main.java")),
        "not a test source");
  }

  private static void rejectsOversizedStamp() throws Exception {
    Path stamp = Files.createTempFile("toktrak-build-stamp-", ".txt");
    try {
      Files.writeString(stamp, "x".repeat(129));
      expectFailure(() -> Build.readStampForTest(stamp), "stamp exceeds 128 UTF-8 bytes");
    } finally {
      Files.deleteIfExists(stamp);
    }
  }

  private static void rejectsInvalidUtf8Stamp() throws Exception {
    Path stamp = Files.createTempFile("toktrak-build-stamp-", ".txt");
    try {
      Files.write(stamp, new byte[] {(byte) 0xC3});
      expectFailure(() -> Build.readStampForTest(stamp), "stamp is not valid UTF-8");
    } finally {
      Files.deleteIfExists(stamp);
    }
  }

  private static void validatesCacheArtifacts() throws Exception {
    Path directory = Files.createTempDirectory("toktrak-build-cache-");
    Path stamp = directory.resolve(".fingerprint");
    Path required = directory.resolve("Output.class");
    try {
      Files.writeString(stamp, "expected");
      if (Build.cacheHitForTest(directory, stamp, "expected", List.of(required))) {
        throw new AssertionError("cache hit without required artifact");
      }
      Files.createFile(required);
      if (!Build.cacheHitForTest(directory, stamp, "expected", List.of(required))) {
        throw new AssertionError("valid cache missed");
      }
      if (Build.cacheHitForTest(directory, stamp, "changed", List.of(required))) {
        throw new AssertionError("stale fingerprint hit");
      }
    } finally {
      Files.deleteIfExists(required);
      Files.deleteIfExists(stamp);
      Files.deleteIfExists(directory);
    }
  }

  private static void waitsForNormalProcess() throws Exception {
    Process process = child("done");
    int exitCode =
        Build.waitForProcessForTest(process, Duration.ofSeconds(2), Duration.ofMillis(100));
    if (exitCode != 0) throw new AssertionError("normal child failed: " + exitCode);
  }

  private static void passesArgumentsWithSpaces() {
    List<String> command = Build.commandForTest("tool", List.of("path with spaces/Source.java"));
    if (!command.equals(List.of("tool", "path with spaces/Source.java"))) {
      throw new AssertionError("unexpected command: " + command);
    }
  }

  private static void batchesFormatterSources() {
    var sources = new ArrayList<String>();
    for (int index = 0; index < 300; index++) sources.add("Source" + index + ".java");
    List<List<String>> batches = Build.formatterArgumentsForTest(sources);
    if (batches.size() != 3) throw new AssertionError("unexpected formatter batches: " + batches);
    int sourceCount = 0;
    for (List<String> batch : batches) {
      Build.commandForTest("google-java-format", batch);
      sourceCount = Math.addExact(sourceCount, batch.size() - 2);
    }
    if (sourceCount != sources.size()) {
      throw new AssertionError("formatter sources lost: " + sourceCount);
    }
  }

  private static void rejectsOversizedCommand() {
    expectFailure(
        () -> Build.commandForTest("unused", Collections.nCopies(100, "x".repeat(300))),
        "command exceeds 24576 UTF-8 bytes");
  }

  private static void appliesWindowsOsNameRule() throws Exception {
    Path directory = Files.createTempDirectory("toktrak-refaster-");
    try {
      Path source = directory.resolve("Demo.java");
      Files.writeString(
          source,
          "package probe; import java.util.Locale; final class Demo { boolean windows() { return"
              + " System.getProperty(\"os.name\").toLowerCase(Locale.ROOT).contains(\"win\"); }"
              + " boolean arbitrary(String value) { return"
              + " value.toLowerCase(Locale.ROOT).contains(\"win\"); } }");
      var arguments = new ArrayList<>(Build.refasterArgumentsForTest());
      arguments.add("-d");
      arguments.add(directory.toString());
      arguments.add(source.toString());
      Process process =
          new ProcessBuilder(Build.commandForTest(javacExecutable(), arguments))
              .redirectErrorStream(true)
              .redirectOutput(ProcessBuilder.Redirect.DISCARD)
              .start();
      int exitCode =
          Build.waitForProcessForTest(process, Duration.ofSeconds(30), Duration.ofSeconds(2), true);
      if (exitCode != 0) throw new AssertionError("Refaster failed: " + exitCode);
      String transformed = Files.readString(source);
      if (!transformed.contains("System.getProperty(\"os.name\").startsWith(\"Windows\")")) {
        throw new AssertionError("OS-name pattern was not refactored: " + transformed);
      }
      if (!transformed.contains("value.toLowerCase(Locale.ROOT).contains(\"win\")")) {
        throw new AssertionError("arbitrary string pattern was refactored: " + transformed);
      }
    } finally {
      List<Path> paths = Build.treePathsForTest(directory, 100);
      for (int index = paths.size() - 1; index >= 0; index--)
        Files.deleteIfExists(paths.get(index));
    }
  }

  private static void detectsDirtyGitTree() throws Exception {
    Path directory = Files.createTempDirectory("toktrak-git-dirty-");
    try {
      runGit(directory, "init", "--quiet");
      if (Build.isGitDirtyForTest(directory)) {
        throw new AssertionError("clean Git tree reported dirty");
      }
      Files.writeString(directory.resolve("untracked.txt"), "dirty");
      if (!Build.isGitDirtyForTest(directory)) {
        throw new AssertionError("dirty Git tree reported clean");
      }
    } finally {
      List<Path> paths = Build.treePathsForTest(directory, 1_000);
      for (int index = paths.size() - 1; index >= 0; index--)
        Files.deleteIfExists(paths.get(index));
    }
  }

  private static void runGit(Path directory, String... arguments) throws Exception {
    var command = new ArrayList<String>();
    command.add("git");
    command.addAll(List.of(arguments));
    Process process =
        new ProcessBuilder(command)
            .directory(directory.toFile())
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .start();
    int exitCode =
        Build.waitForProcessForTest(process, Duration.ofSeconds(30), Duration.ofSeconds(2), true);
    if (exitCode != 0) throw new AssertionError("Git failed: " + command);
  }

  private static void assignsTestGroupTimeouts() {
    if (!Build.testTimeout("--unit").equals(Duration.ofSeconds(30))) {
      throw new AssertionError("unit test timeout is not 30 seconds");
    }
    if (!Build.testTimeout("--tagged").equals(Duration.ofMinutes(10))) {
      throw new AssertionError("tagged test timeout changed");
    }
  }

  private static void forceTerminatesHardTimedOutProcess() throws Exception {
    Process process = child("sleep");
    expectFailure(
        () ->
            Build.waitForProcessForTest(
                process, Duration.ofMillis(20), Duration.ofMillis(100), true),
        "process timed out");
    if (process.isAlive()) throw new AssertionError("hard-timed-out child remains alive");
  }

  private static void terminatesTimedOutProcess() throws Exception {
    Process process = child("sleep");
    expectFailure(
        () -> Build.waitForProcessForTest(process, Duration.ofMillis(20), Duration.ofMillis(100)),
        "process timed out");
    if (process.isAlive()) throw new AssertionError("timed-out child remains alive");
  }

  private static Process child(String argument) throws Exception {
    return new ProcessBuilder(
            javaExecutable(),
            "-ea",
            "-cp",
            System.getProperty("java.class.path"),
            BuildTest.class.getName(),
            argument)
        .start();
  }

  private static String javaExecutable() {
    return executable("java");
  }

  private static String javacExecutable() {
    return executable("javac");
  }

  private static String executable(String name) {
    return Path.of(
            System.getProperty("java.home"),
            "bin",
            System.getProperty("os.name").startsWith("Windows") ? name + ".exe" : name)
        .toString();
  }

  private static void expectFailure(ThrowingAction action, String message) {
    try {
      action.run();
      throw new AssertionError("expected failure: " + message);
    } catch (IllegalStateException ex) {
      if (!ex.getMessage().contains(message)) {
        throw new AssertionError("unexpected failure: " + ex.getMessage(), ex);
      }
    } catch (Exception ex) {
      throw new AssertionError("unexpected exception", ex);
    }
  }

  private static void requireAssertions() {
    if (!BuildTest.class.desiredAssertionStatus()) {
      throw new IllegalStateException("Java assertions must be enabled with -ea");
    }
  }

  @FunctionalInterface
  private interface ThrowingAction {
    void run() throws Exception;
  }
}
