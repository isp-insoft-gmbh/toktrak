package toktrak.tests;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

// TODO: this is just demo shit to test junit tags in build system. replace this with real
// integration tests later! and no more "smoke" crap...
@Tag("smoke")
final class JlinkSmokeTest {
  @Test
  void given_optionalProductionImage_when_checkingImage_then_existingImageHasJavaExecutable() {
    var image = Path.of("output", "runtimes", "prod");
    if (Files.exists(image)) {
      assertTrue(
          Files.exists(
              image
                  .resolve("bin")
                  .resolve(
                      System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java")));
    }
  }
}
