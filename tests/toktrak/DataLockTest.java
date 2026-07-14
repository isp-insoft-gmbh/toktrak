package toktrak;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import toktrak.store.DataLock;

final class DataLockTest {
  @TempDir Path dir;

  @Test
  void rejectsSecondLockInSameJvm() throws Exception {
    try (var first = DataLock.acquire(dir)) {
      var ex = assertThrows(IllegalStateException.class, () -> DataLock.acquire(dir));
      assertEquals("TokTrak data directory is already locked", ex.getMessage());
    }
  }
}
