package toktrak.tests;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import toktrak.*;

@Tag("smoke")
final class JlinkSmokeTest {
  @Test
  void prodRuntimeImageExistsAfterJlinkTaskWhenRequested() {
    var image = Path.of("output", "runtimes", "prod");
    if (Files.exists(image)) {
      assertTrue(
          Files.exists(
              image
                  .resolve("bin")
                  .resolve(
                      System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")
                          ? "java.exe"
                          : "java")));
    }
  }
}
