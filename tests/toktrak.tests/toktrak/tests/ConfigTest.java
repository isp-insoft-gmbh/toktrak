package toktrak.tests;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import toktrak.*;

final class ConfigTest {
  @Test
  void given_devAuthMode_when_parsingConfig_then_defaultsPortAndDataDirectory() {
    var config = Config.from(new String[] {}, Map.of("TOKTRAK_DEV_AUTH", "true"));
    assertEquals(8080, config.port());
    assertTrue(config.devAuth());
    assertTrue(config.dataDirectory().toString().contains("toktrak-dev"));
  }

  @Test
  void given_productionWithoutRequiredOptions_when_parsingConfig_then_rejectsArguments() {
    var exception =
        assertThrows(IllegalArgumentException.class, () -> Config.from(new String[] {}, Map.of()));
    assertEquals("TOKTRAK_DEV_AUTH=true or TOKTRAK_DATA_DIR is required", exception.getMessage());
  }

  @Test
  void given_nonLocalHttpWithoutDevAuth_when_parsingConfig_then_rejectsBaseUrl() {
    var environment =
        Map.of("TOKTRAK_DATA_DIR", "data", "TOKTRAK_BASE_URL", "http://toktrak.example");
    var exception =
        assertThrows(
            IllegalArgumentException.class, () -> Config.from(new String[] {}, environment));
    assertEquals("non-local HTTP requires TOKTRAK_DEV_AUTH=true", exception.getMessage());
  }

  @Test
  void given_devModeWithClockArgument_when_parsingConfig_then_clockReportsRequestedInstant() {
    var config =
        Config.from(
            new String[] {"--clock", "2026-07-10T00:00:00Z"}, Map.of("TOKTRAK_DEV_AUTH", "true"));
    assertEquals(Instant.parse("2026-07-10T00:00:00Z"), config.clock().instant());
  }

  @Test
  void given_argumentCountAboveLimit_when_parsingConfig_then_rejectsArguments() {
    var args = new String[65];
    java.util.Arrays.fill(args, "--help");
    var exception =
        assertThrows(
            IllegalArgumentException.class,
            () -> Config.from(args, Map.of("TOKTRAK_DEV_AUTH", "true")));
    assertEquals("arguments exceed 64 entries", exception.getMessage());
  }

  @Test
  void given_argumentAboveLengthLimit_when_parsingConfig_then_rejectsArgument() {
    var exception =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                Config.from(new String[] {"x".repeat(8_193)}, Map.of("TOKTRAK_DEV_AUTH", "true")));
    assertEquals("argument 0 exceeds 8192 characters", exception.getMessage());
  }

  @Test
  void given_optionsWithoutValues_when_parsingConfig_then_rejectsArguments() {
    for (String option : new String[] {"--corpus", "--clock"}) {
      var exception =
          assertThrows(
              IllegalArgumentException.class,
              () -> Config.from(new String[] {option}, Map.of("TOKTRAK_DEV_AUTH", "true")));
      assertEquals(option + " requires a value", exception.getMessage());
    }
  }

  @Test
  void given_nonLocalHttpsProductionUrl_when_parsingConfig_then_acceptsBaseUrl() {
    var config =
        Config.from(
            new String[] {},
            Map.of(
                "TOKTRAK_DATA_DIR", "data",
                "TOKTRAK_BASE_URL", "https://toktrak.example"));
    assertEquals("https://toktrak.example", config.baseUrl());
  }

  @Test
  void given_environmentValueAboveLimit_when_parsingConfig_then_rejectsValue() {
    var exception =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                Config.from(
                    new String[] {},
                    Map.of("TOKTRAK_DEV_AUTH", "true", "TOKTRAK_BASE_URL", "x".repeat(8_193))));
    assertEquals("TOKTRAK_BASE_URL exceeds 8192 characters", exception.getMessage());
  }

  @Test
  void given_explicitDataDirectoryAndPort_when_parsingConfig_then_usesValues() {
    var config =
        Config.from(
            new String[] {},
            Map.of(
                "TOKTRAK_DEV_AUTH",
                "true",
                "TOKTRAK_DATA_DIR",
                Path.of("output", "dev-data").toString(),
                "TOKTRAK_PORT",
                "9090"));
    assertEquals(9090, config.port());
    assertEquals(Path.of("output", "dev-data"), config.dataDirectory());
  }
}
