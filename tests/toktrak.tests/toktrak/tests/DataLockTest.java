package toktrak.tests;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import toktrak.store.DataLock;

final class DataLockTest {
  /// The disk refuses a second lock in the same JVM with OverlappingFileLockException.
  @DiskTempDir Path directory;

  /// The in-memory file system refuses it with null, as the disk does for another process.
  @TempDir Path memory;

  @Test
  void given_directoryLockedElsewhere_when_acquiringSecondLock_then_rejectsAcquisition()
      throws Exception {
    try (var first = DataLock.acquire(memory)) {
      assertNotNull(first);
      var exception = assertThrows(IllegalStateException.class, () -> DataLock.acquire(memory));
      assertEquals("TokTrak data directory is already locked", exception.getMessage());
    }
  }

  @Test
  void given_directoryLockedInJvm_when_acquiringSecondLock_then_rejectsAcquisition()
      throws Exception {
    try (var first = DataLock.acquire(directory)) {
      assertNotNull(first);
      var exception = assertThrows(IllegalStateException.class, () -> DataLock.acquire(directory));
      assertEquals("TokTrak data directory is already locked", exception.getMessage());
    }
  }
}
