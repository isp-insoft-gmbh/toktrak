package toktrak.tests;

import static org.junit.jupiter.api.Assertions.*;
import static toktrak.store.EventTypes.*;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import toktrak.projection.Projection;
import toktrak.projection.Projection.UserKey;
import toktrak.store.EventEnvelope;
import toktrak.usage.UsageProjection.Ingestion;
import toktrak.usage.UsageProjection.Row;
import toktrak.usage.UsageProjection.Summary;
import toktrak.usage.UsageUpload;
import toktrak.usage.UsageUpload.Report;

final class UsageProjectionTest {
  private static final Instant AT = Instant.parse("2026-07-15T00:00:00Z");
  private static final UserKey USER = new UserKey("https://issuer.example", "user-1");

  @Test
  void given_overlappingUsageSnapshots_when_projecting_then_newestRowsWinAndStaleRowsFillGaps() {
    var projection = projectionWithUser();
    projection.apply(
        usageEvent(
            AT,
            upload(
                AT.minusSeconds(2),
                success("daily", List.of(daily("2026-07-10", 1, "first"))),
                success("session", List.of(session("session-1"))),
                failed())));
    projection.apply(
        usageEvent(
            AT.plusSeconds(1),
            upload(
                AT.minusSeconds(1),
                success("daily", List.of(daily("2026-07-10", 2, "newest"))),
                failed(),
                success("blocks", List.of(block("block-1"))))));
    projection.apply(
        usageEvent(
            AT.plusSeconds(2),
            upload(
                AT.minusSeconds(2),
                success(
                    "daily",
                    List.of(daily("2026-07-10", 99, "stale"), daily("2026-07-11", 3, "filled"))),
                failed(),
                failed())));
    projection.apply(
        usageEvent(
            AT.plusSeconds(3),
            upload(
                AT.minusSeconds(1),
                success("daily", List.of(daily("2026-07-10", 4, "equal-later"))),
                failed(),
                failed())));

    var summary = projection.usageSummary();
    assertEquals(new BigDecimal("7"), summary.costUsd());
    assertEquals(2, summary.dailyRows());
    assertEquals(1, summary.sessionRows());
    assertEquals(1, summary.blockRows());
    assertEquals(70, summary.totalTokens());
    var rows = projection.usageRows(Report.DAILY);
    assertEquals("equal-later", rows.getFirst().data().get("unknownField"));
    assertEquals("filled", rows.getLast().data().get("unknownField"));
    assertTrue(projection.ingestion().getFirst().partial());
    assertEquals(AT.minusSeconds(1), projection.ingestion().getFirst().generatedAt());
  }

  @Test
  void given_concurrentEqualTimeUploads_when_projecting_then_keepsOneCanonicalRow()
      throws Exception {
    var projection = projectionWithUser();
    EventEnvelope first =
        usageEvent(
            AT,
            upload(
                AT,
                success("daily", List.of(daily("2026-07-10", 1, "first"))),
                failed(),
                failed()));
    EventEnvelope second =
        usageEvent(
            AT,
            upload(
                AT,
                success("daily", List.of(daily("2026-07-10", 2, "second"))),
                failed(),
                failed()));
    try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
      var one = executor.submit(() -> projection.apply(first));
      var two = executor.submit(() -> projection.apply(second));
      one.get();
      two.get();
    }

    assertEquals(1, projection.usageSummary().dailyRows());
    assertTrue(
        java.util.Set.of(new BigDecimal("1"), new BigDecimal("2"))
            .contains(projection.usageSummary().costUsd()));
  }

  @Test
  void given_usageAndSnapshot_when_rebuilding_then_snapshotPreservesUsageProjection() {
    var projection = projectionWithUser();
    projection.apply(
        usageEvent(
            AT,
            upload(
                AT,
                success("daily", List.of(daily("2026-07-10", 5, "kept"))),
                failed(),
                failed())));
    projection.apply(
        EventEnvelope.create(
            PROJECTION_SNAPSHOT, AT.plusSeconds(1), "system", projection.snapshotData()));

    assertEquals(new BigDecimal("5"), projection.usageSummary().costUsd());
    assertEquals("kept", projection.usageRows(Report.DAILY).getFirst().data().get("unknownField"));
  }

  @Test
  void given_partialUploadMetadata_when_parsing_then_preservesVersionsFailuresAndFullState() {
    Map<String, Object> value =
        new java.util.HashMap<>(upload(AT, success("daily", List.of()), failed(), failed()));
    value.put("full", false);

    UsageUpload parsed = UsageUpload.parse(value, AT);

    assertEquals("1", parsed.trackerVersion());
    assertEquals("20.0.17", parsed.ccusageVersion());
    assertEquals("UTC", parsed.clientTimeZone());
    assertFalse(parsed.full());
    assertEquals(List.of("session", "blocks"), parsed.failedReports());
  }

  @Test
  void given_twoOwners_when_listingRowsAndIngestion_then_sortsByOwnerBeforeRowKeys() {
    var alpha = new UserKey("https://issuer.example", "alpha");
    var beta = new UserKey("https://issuer.example", "beta");
    var projection = Projection.empty();
    authenticate(projection, alpha);
    authenticate(projection, beta);
    projection.apply(
        usageEvent(
            beta,
            AT,
            upload(
                AT,
                success("daily", List.of(daily("2026-07-01", 1, "beta-early"))),
                failed(),
                failed())));
    projection.apply(
        usageEvent(
            alpha,
            AT.plusSeconds(1),
            upload(
                AT,
                success("daily", List.of(daily("2026-07-31", 2, "alpha-late"))),
                failed(),
                failed())));

    assertEquals(
        List.of(alpha, beta), projection.usageRows(Report.DAILY).stream().map(Row::owner).toList());
    assertEquals(
        List.of(alpha, beta), projection.ingestion().stream().map(Ingestion::owner).toList());
  }

  @Test
  void given_invalidSummaryValues_when_constructing_then_rejectsThem() {
    assertDoesNotThrow(() -> new Summary(BigDecimal.ZERO, 0, 0, 0, 0, 0, 0, 0, 0, 0));
    assertThrows(NullPointerException.class, () -> new Summary(null, 0, 0, 0, 0, 0, 0, 0, 0, 0));
    assertThrows(
        IllegalArgumentException.class,
        () -> new Summary(new BigDecimal("-0.01"), 0, 0, 0, 0, 0, 0, 0, 0, 0));
    assertThrows(
        IllegalArgumentException.class,
        () -> new Summary(BigDecimal.ZERO, -1, 0, 0, 0, 0, 0, 0, 0, 0));
    assertThrows(
        IllegalArgumentException.class,
        () -> new Summary(BigDecimal.ZERO, 0, -1, 0, 0, 0, 0, 0, 0, 0));
    assertThrows(
        IllegalArgumentException.class,
        () -> new Summary(BigDecimal.ZERO, 0, 0, -1, 0, 0, 0, 0, 0, 0));
    assertThrows(
        IllegalArgumentException.class,
        () -> new Summary(BigDecimal.ZERO, 0, 0, 0, -1, 0, 0, 0, 0, 0));
    assertThrows(
        IllegalArgumentException.class,
        () -> new Summary(BigDecimal.ZERO, 0, 0, 0, 0, -1, 0, 0, 0, 0));
    assertThrows(
        IllegalArgumentException.class,
        () -> new Summary(BigDecimal.ZERO, 0, 0, 0, 0, 0, -1, 0, 0, 0));
    assertThrows(
        IllegalArgumentException.class,
        () -> new Summary(BigDecimal.ZERO, 0, 0, 0, 0, 0, 0, -1, 0, 0));
    assertThrows(
        IllegalArgumentException.class,
        () -> new Summary(BigDecimal.ZERO, 0, 0, 0, 0, 0, 0, 0, -1, 0));
    assertThrows(
        IllegalArgumentException.class,
        () -> new Summary(BigDecimal.ZERO, 0, 0, 0, 0, 0, 0, 0, 0, -1));
  }

  @Test
  void given_futureUpload_when_parsing_then_rejectsMoreThanTwentyFourHoursAhead() {
    Map<String, Object> value =
        upload(
            AT.plus(Duration.ofHours(24)).plusNanos(1),
            success("daily", List.of()),
            failed(),
            failed());

    var exception =
        assertThrows(IllegalArgumentException.class, () -> UsageUpload.parse(value, AT));
    assertEquals("generatedAt is more than 24 hours ahead", exception.getMessage());
  }

  @Test
  void given_revisionWait_when_projectionChanges_then_returnsNewRevision() throws Exception {
    var projection = Projection.empty();
    try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
      var waiting = executor.submit(() -> projection.awaitRevision(0, Duration.ofSeconds(1)));
      projection.apply(EventEnvelope.create("change", AT, "system", Map.of()));
      assertEquals(1, waiting.get());
    }
  }

  @Test
  void given_revisionWait_when_timingOutAndValidatingBounds_then_returnsCurrentRevision()
      throws Exception {
    var projection = Projection.empty();

    assertEquals(0, projection.awaitRevision(0, Duration.ofMillis(1)));
    assertThrows(
        IllegalArgumentException.class, () -> projection.awaitRevision(-1, Duration.ofSeconds(1)));
    assertThrows(IllegalArgumentException.class, () -> projection.awaitRevision(0, Duration.ZERO));
    assertThrows(
        IllegalArgumentException.class, () -> projection.awaitRevision(0, Duration.ofSeconds(31)));
  }

  @Test
  void given_invalidFxRateValues_when_constructingRate_then_rejectsThem() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new Projection.FxRate("2026-07-14", BigDecimal.ZERO, AT));
    assertDoesNotThrow(() -> new Projection.FxRate("2026-07-14", BigDecimal.TEN, AT));
    assertThrows(
        IllegalArgumentException.class,
        () -> new Projection.FxRate("2026-07-14", new BigDecimal("10.01"), AT));
    assertThrows(
        IllegalArgumentException.class, () -> new Projection.FxRate("invalid", BigDecimal.ONE, AT));
    assertThrows(NullPointerException.class, () -> new Projection.FxRate(null, BigDecimal.ONE, AT));
  }

  @Test
  void given_lastGoodFxRate_when_laterFxEventIsInvalid_then_retainsLastGoodRate() {
    var projection = Projection.empty();
    projection.apply(
        EventEnvelope.create(
            FX_RATE_UPDATED, AT, "system", Map.of("date", "2026-07-14", "eurPerUsd", "0.92")));

    assertThrows(
        IllegalStateException.class,
        () ->
            projection.apply(
                EventEnvelope.create(
                    FX_RATE_UPDATED,
                    AT.plusSeconds(1),
                    "system",
                    Map.of("date", "2026-07-15", "eurPerUsd", "0"))));
    assertEquals(new BigDecimal("0.92"), projection.fxRate().orElseThrow().eurPerUsd());
    assertEquals("2026-07-14", projection.fxRate().orElseThrow().date());
  }

  private static Projection projectionWithUser() {
    var projection = Projection.empty();
    authenticate(projection, USER);
    return projection;
  }

  private static void authenticate(Projection projection, UserKey user) {
    projection.apply(
        EventEnvelope.create(
            IDENTITY_USER_AUTHENTICATED,
            AT.minusSeconds(10),
            user.subject(),
            Map.of(
                "issuer", user.issuer(),
                "subject", user.subject(),
                "email", "user@example.com",
                "displayName", "Example User",
                "color", "#a8dadc")));
  }

  private static EventEnvelope usageEvent(Instant at, Map<String, Object> upload) {
    return usageEvent(USER, at, upload);
  }

  private static EventEnvelope usageEvent(UserKey user, Instant at, Map<String, Object> upload) {
    return EventEnvelope.create(
        USAGE_UPLOADED,
        at,
        user.subject(),
        Map.of("issuer", user.issuer(), "subject", user.subject(), "upload", upload));
  }

  private static Map<String, Object> upload(
      Instant generatedAt,
      Map<String, Object> daily,
      Map<String, Object> session,
      Map<String, Object> blocks) {
    return Map.of(
        "trackerVersion", "1",
        "ccusageVersion", "20.0.17",
        "clientTimeZone", "UTC",
        "full", true,
        "generatedAt", generatedAt.toString(),
        "reports", Map.of("daily", daily, "session", session, "blocks", blocks),
        "unknownUploadField", "preserved");
  }

  private static Map<String, Object> success(String name, List<Map<String, Object>> rows) {
    return Map.of("ok", true, "json", Map.of(name, rows));
  }

  private static Map<String, Object> failed() {
    return Map.of("ok", false, "error", "synthetic failure");
  }

  private static Map<String, Object> daily(String period, long cost, String unknown) {
    return Map.of(
        "period",
        period,
        "agent",
        "claude",
        "inputTokens",
        cost * 10,
        "outputTokens",
        0,
        "cacheCreationTokens",
        0,
        "cacheReadTokens",
        0,
        "totalTokens",
        cost * 10,
        "totalCost",
        cost,
        "unknownField",
        unknown);
  }

  private static Map<String, Object> session(String period) {
    return Map.of("period", period, "agent", "claude", "future", Map.of("nested", true));
  }

  private static Map<String, Object> block(String id) {
    return Map.of("id", id, "future", List.of("value"));
  }
}
