package toktrak.tests;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import toktrak.*;

final class ConfigTest {
  @Test
  void devAuthDefaultsPortAndDataDir() {
    var config = Config.from(new String[] {}, Map.of("TOKTRAK_DEV_AUTH", "true"));
    assertEquals(8080, config.port());
    assertTrue(config.devAuth());
    assertTrue(config.dataDirectory().toString().contains("toktrak-dev"));
  }

  @Test
  void productionRequiresDataDirAndBaseUrl() {
    var exception =
        assertThrows(IllegalArgumentException.class, () -> Config.from(new String[] {}, Map.of()));
    assertEquals("TOKTRAK_DEV_AUTH=true or TOKTRAK_DATA_DIR is required", exception.getMessage());
  }

  @Test
  void rejectsNonLocalHttpWithoutDevAuth() {
    var environment =
        Map.of("TOKTRAK_DATA_DIR", "data", "TOKTRAK_BASE_URL", "http://toktrak.example");
    var exception =
        assertThrows(
            IllegalArgumentException.class, () -> Config.from(new String[] {}, environment));
    assertEquals("non-local HTTP requires TOKTRAK_DEV_AUTH=true", exception.getMessage());
  }

  @Test
  void acceptsPinnedDevClockOnlyInDevMode() {
    var config =
        Config.from(
            new String[] {"--clock", "2026-07-10T00:00:00Z"}, Map.of("TOKTRAK_DEV_AUTH", "true"));
    assertEquals(Instant.parse("2026-07-10T00:00:00Z"), config.clock().instant());
  }

  @Test
  void rejectsTooManyArguments() {
    var args = new String[65];
    java.util.Arrays.fill(args, "--help");
    var exception =
        assertThrows(
            IllegalArgumentException.class,
            () -> Config.from(args, Map.of("TOKTRAK_DEV_AUTH", "true")));
    assertEquals("arguments exceed 64 entries", exception.getMessage());
  }

  @Test
  void rejectsOversizedEnvironmentValue() {
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
  void acceptsExplicitDataDirAndPort() {
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
