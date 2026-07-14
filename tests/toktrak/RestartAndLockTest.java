package toktrak;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import toktrak.store.WriteCommand;

final class RestartAndLockTest {
  @TempDir Path dir;

  @Test
  void restartRebuildsProjectionFromEventLog() throws Exception {
    var env = Map.of("TOKTRAK_DEV_AUTH", "true", "TOKTRAK_PORT", "0", "TOKTRAK_DATA_DIR", dir.toString());
    try (var app = App.start(new String[] {}, env)) {
      app.writer().submit(WriteCommand.devTest("system")).get();
      assertEquals(1, app.projection().eventCount());
    }
    try (var app = App.start(new String[] {}, env)) {
      assertEquals(1, app.projection().eventCount());
    }
  }

  @Test
  void secondAppCannotOpenSameDataDir() throws Exception {
    var env = Map.of("TOKTRAK_DEV_AUTH", "true", "TOKTRAK_PORT", "0", "TOKTRAK_DATA_DIR", dir.toString());
    try (var first = App.start(new String[] {}, env)) {
      var ex = assertThrows(IllegalStateException.class, () -> App.start(new String[] {}, env));
      assertEquals("TokTrak data directory is already locked", ex.getMessage());
    }
  }
}
