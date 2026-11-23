package toktrak.tests;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.channels.FileChannel;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import org.junit.jupiter.api.Test;

final class MemoryTempDirTest {
  @MemoryTempDir Path directory;

  @Test
  void given_memoryTempDir_when_usingStorageOperations_then_staysOffTheDefaultFileSystem()
      throws Exception {
    assertNotEquals(FileSystems.getDefault(), directory.getFileSystem());

    Path temporary = directory.resolve("events.ndjson.tmp");
    Files.writeString(temporary, "line\n");
    Path events =
        Files.move(temporary, directory.resolve("events.ndjson"), StandardCopyOption.ATOMIC_MOVE);
    try (var channel = FileChannel.open(events, StandardOpenOption.APPEND)) {
      channel.write(
          java.nio.ByteBuffer.wrap("more\n".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
      channel.force(true);
    }
    assertEquals("line\nmore\n", Files.readString(events));

    // A held lock refuses a second one with null, the answer another process gets on disk; a
    // disk refuses it inside one JVM with OverlappingFileLockException instead.
    try (var first = FileChannel.open(events, StandardOpenOption.WRITE);
        var lock = first.tryLock()) {
      assertNotNull(lock);
      try (var second = FileChannel.open(events, StandardOpenOption.WRITE)) {
        assertNull(second.tryLock());
      }
    }
  }
}
