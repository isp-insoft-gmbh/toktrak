package toktrak;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class ConfigTest {
  @Test
  void devAuthDefaultsPortAndDataDir() {
    var cfg = Config.from(new String[] {}, Map.of("TOKTRAK_DEV_AUTH", "true"));
    assertEquals(8080, cfg.port());
    assertTrue(cfg.devAuth());
    assertTrue(cfg.dataDir().toString().contains("toktrak-dev"));
  }

  @Test
  void productionRequiresDataDirAndBaseUrl() {
    var ex = assertThrows(IllegalArgumentException.class, () -> Config.from(new String[] {}, Map.of()));
    assertEquals("TOKTRAK_DEV_AUTH=true or TOKTRAK_DATA_DIR is required", ex.getMessage());
  }

  @Test
  void rejectsNonLocalHttpWithoutDevAuth() {
    var env = Map.of("TOKTRAK_DATA_DIR", "data", "TOKTRAK_BASE_URL", "http://toktrak.example");
    var ex = assertThrows(IllegalArgumentException.class, () -> Config.from(new String[] {}, env));
    assertEquals("non-local HTTP requires TOKTRAK_DEV_AUTH=true", ex.getMessage());
  }

  @Test
  void acceptsPinnedDevClockOnlyInDevMode() {
    var cfg = Config.from(new String[] {"--clock", "2026-07-10T00:00:00Z"}, Map.of("TOKTRAK_DEV_AUTH", "true"));
    assertEquals(Instant.parse("2026-07-10T00:00:00Z"), cfg.clock().instant());
  }

  @Test
  void acceptsExplicitDataDirAndPort() {
    var cfg = Config.from(
        new String[] {},
        Map.of("TOKTRAK_DEV_AUTH", "true", "TOKTRAK_DATA_DIR", Path.of("output", "dev-data").toString(), "TOKTRAK_PORT", "9090"));
    assertEquals(9090, cfg.port());
    assertEquals(Path.of("output", "dev-data"), cfg.dataDir());
  }
}
