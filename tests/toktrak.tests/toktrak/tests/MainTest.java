package toktrak.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import toktrak.Main;

final class MainTest {
  @Test
  void given_helpOption_when_runningMain_then_printsEnvironmentNeutralName() throws Exception {
    String output = captureOutput(() -> Main.main(new String[] {"--help"}));

    assertEquals("TokTrak server" + System.lineSeparator(), output);
  }

  @Test
  void given_checkAssetsOption_when_runningMain_then_verifiesPackagedAssets() throws Exception {
    String output = captureOutput(() -> Main.main(new String[] {"--check-assets"}));

    assertTrue(
        output.matches("TokTrak assets and rendering ok: [1-9][0-9]*, [1-9][0-9]* bytes\\R"),
        output);
  }

  private static String captureOutput(ThrowingAction action) throws Exception {
    var output = new ByteArrayOutputStream();
    PrintStream original = System.out;
    try (var replacement = new PrintStream(output, true, StandardCharsets.UTF_8)) {
      System.setOut(replacement);
      action.run();
    } finally {
      System.setOut(original);
    }
    return output.toString(StandardCharsets.UTF_8);
  }

  @FunctionalInterface
  private interface ThrowingAction {
    void run() throws Exception;
  }
}
