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
    var config = Config.from(new String[] {}, productionEnvironment("https://toktrak.example"));
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
  void given_invalidPortValues_when_parsingConfig_then_rejectsPort() {
    for (String port : new String[] {"-1", "65536", "not-a-port"}) {
      var exception =
          assertThrows(
              IllegalArgumentException.class,
              () ->
                  Config.from(
                      new String[] {}, Map.of("TOKTRAK_DEV_AUTH", "true", "TOKTRAK_PORT", port)));
      assertEquals("TOKTRAK_PORT must be 0..65535", exception.getMessage());
    }
  }

  @Test
  void given_portBoundaries_when_parsingConfig_then_acceptsPorts() {
    for (String port : new String[] {"0", "65535"}) {
      assertDoesNotThrow(
          () ->
              Config.from(
                  new String[] {}, Map.of("TOKTRAK_DEV_AUTH", "true", "TOKTRAK_PORT", port)));
    }
  }

  @Test
  void given_invalidBaseUrl_when_parsingConfig_then_rejectsUrl() {
    var exception =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                Config.from(
                    new String[] {},
                    Map.of("TOKTRAK_DATA_DIR", "data", "TOKTRAK_BASE_URL", "http://[invalid")));
    assertEquals("TOKTRAK_BASE_URL must be a valid URL", exception.getMessage());
  }

  @Test
  void given_productionWithoutBaseUrl_when_parsingConfig_then_rejectsBaseUrl() {
    for (Map<String, String> environment :
        java.util.List.of(
            Map.of("TOKTRAK_DATA_DIR", "data"),
            Map.of("TOKTRAK_DATA_DIR", "data", "TOKTRAK_BASE_URL", " "))) {
      var exception =
          assertThrows(
              IllegalArgumentException.class, () -> Config.from(new String[] {}, environment));
      assertEquals("TOKTRAK_BASE_URL is required", exception.getMessage());
    }
  }

  @Test
  void given_localHttpProductionUrls_when_parsingConfig_then_acceptsBaseUrl() {
    for (String baseUrl : new String[] {"http://localhost:8080", "http://127.0.0.1:8080"}) {
      assertDoesNotThrow(() -> Config.from(new String[] {}, productionEnvironment(baseUrl)));
    }
  }

  @Test
  void given_devOnlyArgumentsInProduction_when_parsingConfig_then_rejectsArguments() {
    Map<String, String> environment = productionEnvironment("https://toktrak.example");
    for (String[] arguments :
        new String[][] {
          {"--corpus", "events.ndjson"},
          {"--fail-writes"},
          {"--clock", "2026-07-10T00:00:00Z"}
        }) {
      assertThrows(IllegalArgumentException.class, () -> Config.from(arguments, environment));
    }
  }

  @Test
  void given_unknownOption_when_parsingConfig_then_rejectsArgument() {
    var exception =
        assertThrows(
            IllegalArgumentException.class,
            () -> Config.from(new String[] {"--unknown"}, Map.of("TOKTRAK_DEV_AUTH", "true")));
    assertEquals("unknown argument: --unknown", exception.getMessage());
  }

  @Test
  void given_helpOption_when_parsingConfig_then_acceptsArgument() {
    assertDoesNotThrow(
        () -> Config.from(new String[] {"--help"}, Map.of("TOKTRAK_DEV_AUTH", "true")));
  }

  @Test
  void given_productionWithoutAuthSetting_when_parsingConfig_then_rejectsSetting() {
    var environment = new java.util.HashMap<>(productionEnvironment("https://toktrak.example"));
    environment.remove("TOKTRAK_TOKEN_PEPPER");
    var exception =
        assertThrows(
            IllegalArgumentException.class,
            () -> Config.from(new String[] {}, Map.copyOf(environment)));
    assertEquals("TOKTRAK_TOKEN_PEPPER is required", exception.getMessage());
  }

  @Test
  void given_invalidProductionAuthSettings_when_parsingConfig_then_rejectsSettings() {
    for (Map.Entry<String, String> invalid :
        Map.of(
                "TOKTRAK_ALLOWED_DOMAIN", "invalid",
                "TOKTRAK_SESSION_SECRET", "short",
                "TOKTRAK_TOKEN_PEPPER", "short",
                "TOKTRAK_OIDC_DISCOVERY_URL", "ftp://accounts.example/config")
            .entrySet()) {
      var environment = new java.util.HashMap<>(productionEnvironment("https://toktrak.example"));
      environment.put(invalid.getKey(), invalid.getValue());
      assertThrows(
          IllegalArgumentException.class,
          () -> Config.from(new String[] {}, Map.copyOf(environment)),
          invalid.getKey());
    }
    assertThrows(
        IllegalArgumentException.class,
        () -> Config.from(new String[] {}, productionEnvironment("ftp://toktrak.example")));
  }

  @Test
  void given_devAuthWithProductionAuthSetting_when_parsingConfig_then_rejectsSetting() {
    var exception =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                Config.from(
                    new String[] {},
                    Map.of("TOKTRAK_DEV_AUTH", "true", "TOKTRAK_OIDC_CLIENT_ID", "client")));
    assertEquals(
        "TOKTRAK_OIDC_CLIENT_ID is forbidden with TOKTRAK_DEV_AUTH=true", exception.getMessage());
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

  private static Map<String, String> productionEnvironment(String baseUrl) {
    String secret = java.util.Base64.getEncoder().encodeToString(new byte[32]);
    return Map.of(
        "TOKTRAK_DATA_DIR", "data",
        "TOKTRAK_BASE_URL", baseUrl,
        "TOKTRAK_OIDC_DISCOVERY_URL", "https://accounts.example/.well-known/openid-configuration",
        "TOKTRAK_OIDC_CLIENT_ID", "client",
        "TOKTRAK_OIDC_CLIENT_SECRET", "secret",
        "TOKTRAK_ALLOWED_DOMAIN", "example.com",
        "TOKTRAK_SESSION_SECRET", secret,
        "TOKTRAK_TOKEN_PEPPER", secret);
  }
}
