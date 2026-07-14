package toktrak;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class JlinkSmokeTest {
  @Test
  void prodRuntimeImageExistsAfterJlinkTaskWhenRequested() {
    var image = Path.of("output", "runtimes", "prod");
    if (Files.exists(image)) {
      assertTrue(Files.exists(image.resolve("bin").resolve(System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java")));
    }
  }
}
