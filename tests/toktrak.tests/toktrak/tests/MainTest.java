package toktrak.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import toktrak.Main;

final class MainTest {
  @Test
  void helpUsesEnvironmentNeutralName() throws Exception {
    var output = new ByteArrayOutputStream();
    PrintStream original = System.out;
    try (var replacement = new PrintStream(output, true, StandardCharsets.UTF_8)) {
      System.setOut(replacement);
      Main.main(new String[] {"--help"});
    } finally {
      System.setOut(original);
    }
    assertEquals(
        "TokTrak server" + System.lineSeparator(), output.toString(StandardCharsets.UTF_8));
  }
}
