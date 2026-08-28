package toktrak.tests;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import toktrak.http.Assets;
import toktrak.http.TrackerScript;

final class TrackerScriptTest {
  private static final String TOKEN = "tt_" + "A".repeat(43);

  @Test
  void given_invalidTemplateInputs_when_renderingTracker_then_rejectsThem() {
    byte[] template = Assets.load().privateBytes("tracker.mjs");
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new TrackerScript(
                "export {};".getBytes(StandardCharsets.UTF_8),
                URI.create("https://toktrak.example")));
    assertThrows(
        IllegalArgumentException.class,
        () -> new TrackerScript(template, URI.create("https://toktrak.example/path")));
    byte[] exactLimit = new byte[512 * 1024];
    Arrays.fill(exactLimit, (byte) ' ');
    byte[] placeholders = "__TOKTRAK_BASE_URL____TOKTRAK_TOKEN__".getBytes(StandardCharsets.UTF_8);
    System.arraycopy(placeholders, 0, exactLimit, 0, placeholders.length);
    assertDoesNotThrow(() -> new TrackerScript(exactLimit, URI.create("https://toktrak.example")));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new TrackerScript(
                new byte[exactLimit.length + 1], URI.create("https://toktrak.example")));
    var scripts = new TrackerScript(template, URI.create("https://toktrak.example/"));
    assertThrows(IllegalArgumentException.class, () -> scripts.render("invalid"));

    TrackerScript.Personalized personalized = scripts.render(TOKEN);
    byte[] changed = personalized.bytes();
    changed[0] = 0;
    assertNotEquals(0, personalized.bytes()[0]);
    assertTrue(personalized.text().contains("const BASE_URL = \"https://toktrak.example\";"));
    assertTrue(personalized.text().contains(TOKEN));
    assertFalse(personalized.text().contains("__TOKTRAK_"));
    assertTrue(personalized.sha256().matches("[0-9a-f]{64}"));
  }
}
