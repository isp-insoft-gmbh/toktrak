import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Duration;
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
    rejectsOversizedStamp();
    rejectsInvalidUtf8Stamp();
    waitsForNormalProcess();
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

  private static void waitsForNormalProcess() throws Exception {
    Process process = child("done");
    int exitCode =
        Build.waitForProcessForTest(process, Duration.ofSeconds(2), Duration.ofMillis(100));
    if (exitCode != 0) throw new AssertionError("normal child failed: " + exitCode);
  }

  private static void terminatesTimedOutProcess() throws Exception {
    Process process = child("sleep");
    expectFailure(
        () -> Build.waitForProcessForTest(process, Duration.ofMillis(20), Duration.ofMillis(100)),
        "process timed out");
    if (process.isAlive()) throw new AssertionError("timed-out child remains alive");
  }

  private static Process child(String argument) throws Exception {
    String java =
        Path.of(
                System.getProperty("java.home"),
                "bin",
                System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java")
            .toString();
    return new ProcessBuilder(
            java,
            "-ea",
            "-cp",
            System.getProperty("java.class.path"),
            BuildTest.class.getName(),
            argument)
        .start();
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
