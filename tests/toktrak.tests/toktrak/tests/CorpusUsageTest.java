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
  void given_sanitizedDevelopmentCorpus_when_replaying_then_matchesAssertedCanonicalTotals()
      throws Exception {
    String corpus = MemoryTempDirFactory.devCorpusIn(directory).toString();
    try (var app =
        App.start(
            new String[] {"--corpus", corpus, "--clock", "2026-07-15T00:00:00Z"},
            Map.of(
                "TOKTRAK_DEV_AUTH", "true",
                "TOKTRAK_PORT", "0",
                "TOKTRAK_DATA_DIR", directory.resolve("data").toString()),
            directory.getFileSystem())) {
      var summary = app.projection().usageSummary();
      assertEquals(new BigDecimal("6472.8836786188425035311"), summary.costUsd());
      assertEquals(BigInteger.valueOf(4_555_214_918L), summary.totalTokens());
      assertEquals(BigInteger.valueOf(221_239_545L), summary.inputTokens());
      assertEquals(BigInteger.valueOf(18_733_786L), summary.outputTokens());
      assertEquals(BigInteger.valueOf(51_538_825L), summary.cacheCreationTokens());
      assertEquals(BigInteger.valueOf(4_263_701_684L), summary.cacheReadTokens());
      assertEquals(302, summary.dailyRows());
      assertEquals(317, summary.sessionRows());
      assertEquals(337, summary.blockRows());
      assertEquals(6, summary.activeUsers());
      assertEquals(6, app.projection().ingestion().size());
      assertEquals("0.92", app.projection().fxRate().orElseThrow().eurPerUsd().toPlainString());
      assertTrue(
          app.projection()
              .activeUser(new UserKey("urn:toktrak:development", "synthetic-subject-5"))
              .isEmpty());
      var tokens =
          app.projection()
              .trackerTokens(new UserKey("urn:toktrak:development", "synthetic-subject-6"));
      assertEquals(2, tokens.size());
      assertEquals(
          1, tokens.stream().filter(token -> token.lastUsedAt() != null).count(), tokens::toString);
      assertEquals(
          1, tokens.stream().filter(token -> token.revokedAt() != null).count(), tokens::toString);
    }
  }
}
