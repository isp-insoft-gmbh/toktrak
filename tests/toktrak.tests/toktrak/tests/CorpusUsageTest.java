package toktrak.tests;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import toktrak.App;
import toktrak.projection.Projection.UserKey;

final class CorpusUsageTest {
  @TempDir Path directory;

  @Test
  void given_sanitizedDevelopmentCorpus_when_replaying_then_matchesAssertedCanonicalTotals() {
    try (var app =
        App.start(
            new String[] {"--corpus", "tests/corpus/dev.jsonl", "--clock", "2026-07-15T00:00:00Z"},
            Map.of(
                "TOKTRAK_DEV_AUTH", "true",
                "TOKTRAK_PORT", "0",
                "TOKTRAK_DATA_DIR", directory.toString()))) {
      var summary = app.projection().usageSummary();
      assertEquals(new BigDecimal("6439.3490222899988205311"), summary.costUsd());
      assertEquals(BigInteger.valueOf(4_555_214_839L), summary.totalTokens());
      assertEquals(BigInteger.valueOf(221_239_488L), summary.inputTokens());
      assertEquals(BigInteger.valueOf(18_733_756L), summary.outputTokens());
      assertEquals(BigInteger.valueOf(51_538_796L), summary.cacheCreationTokens());
      assertEquals(BigInteger.valueOf(4_263_701_600L), summary.cacheReadTokens());
      assertEquals(300, summary.dailyRows());
      assertEquals(315, summary.sessionRows());
      assertEquals(335, summary.blockRows());
      assertEquals(5, summary.activeUsers());
      assertEquals(5, app.projection().ingestion().size());
      assertEquals("0.92", app.projection().fxRate().orElseThrow().eurPerUsd().toPlainString());
      assertTrue(
          app.projection()
              .activeUser(new UserKey("urn:toktrak:development", "synthetic-subject-5"))
              .isEmpty());
    }
  }
}
