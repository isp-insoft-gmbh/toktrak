package toktrak.tests;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import toktrak.*;
import toktrak.store.DataLock;

final class DataLockTest {
  @TempDir Path directory;

  @Test
  void rejectsSecondLockInSameJvm() throws Exception {
    try (var first = DataLock.acquire(directory)) {
      assertNotNull(first);
      var exception = assertThrows(IllegalStateException.class, () -> DataLock.acquire(directory));
      assertEquals("TokTrak data directory is already locked", exception.getMessage());
    }
  }
}
