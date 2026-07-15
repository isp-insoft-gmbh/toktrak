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
    selectsTestsByFileAndDirectory();
    runsBuildToolTestsWithoutApplicationSources();
    rejectsNonTestSelection();
    rejectsOversizedStamp();
    rejectsInvalidUtf8Stamp();
    validatesCacheArtifacts();
    waitsForNormalProcess();
    passesArgumentsWithSpaces();
    batchesFormatterSources();
    rejectsOversizedCommand();
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

  private static void runsBuildToolTestsWithoutApplicationSources() throws Exception {
    Path directory = Files.createTempDirectory("toktrak-build-tool-only-");
    try {
      Path tools = Files.createDirectories(directory.resolve("tools"));
      Path tests = Files.createDirectories(directory.resolve("tests/tools"));
      Files.copy(Path.of("tools/Build.java"), tools.resolve("Build.java"));
      Files.writeString(
          tests.resolve("BuildTest.java"),
          "public final class BuildTest {"
              + " private BuildTest() {}"
              + " public static void main(String[] args) { assert args.length == 0; }"
              + " }");
      Process process =
          new ProcessBuilder(
                  javaExecutable(),
                  "-ea",
                  tools.resolve("Build.java").toString(),
                  "test",
                  "tests/tools")
              .directory(directory.toFile())
              .redirectErrorStream(true)
              .redirectOutput(ProcessBuilder.Redirect.DISCARD)
              .start();
      int exitCode =
          Build.waitForProcessForTest(process, Duration.ofSeconds(30), Duration.ofSeconds(2));
      if (exitCode != 0) throw new AssertionError("build-tool-only test failed: " + exitCode);
    } finally {
      List<Path> paths = Build.treePathsForTest(directory, 1_000);
      for (int index = paths.size() - 1; index >= 0; index--)
        Files.deleteIfExists(paths.get(index));
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
    return Path.of(
            System.getProperty("java.home"),
            "bin",
            System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java")
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
    boolean enabled = false;
    assert enabled = true;
    if (!enabled) throw new IllegalStateException("Java assertions must be enabled with -ea");
  }

  @FunctionalInterface
  private interface ThrowingAction {
    void run() throws Exception;
  }
}
