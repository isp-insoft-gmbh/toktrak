package toktrak.tests;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import toktrak.usage.UsageUpload;
import toktrak.usage.UsageUpload.Report;

final class UsageUploadTest {
  private static final Instant AT = Instant.parse("2026-07-15T00:00:00Z");

  @Test
  void given_fullUploadBytes_when_parsing_then_exposesMetadataReportsAndRows() {
    String json =
        """
        {"trackerVersion":"1","ccusageVersion":"20.0.17","clientTimeZone":"UTC","full":true,
         "generatedAt":"2026-07-15T00:00:00Z","unknownUploadField":"preserved","reports":{
           "daily":{"ok":true,"json":{"daily":[{"period":"2026-07-14","agent":"claude",
             "inputTokens":10,"outputTokens":2,"cacheCreationTokens":3,"cacheReadTokens":4,
             "totalTokens":19,"totalCost":1,"unknownField":"preserved"}]}},
           "session":{"ok":true,"json":{"session":[{"period":"session-1","agent":"claude"}]}},
           "blocks":{"ok":true,"json":{"blocks":[{"id":"block-1"}]}}}}
        """;

    UsageUpload parsed = UsageUpload.parse(json.getBytes(UTF_8), AT);

    assertEquals("1", parsed.trackerVersion());
    assertEquals("20.0.17", parsed.ccusageVersion());
    assertEquals("UTC", parsed.clientTimeZone());
    assertTrue(parsed.full());
    assertFalse(parsed.partial());
    assertEquals(AT, parsed.generatedAt());
    assertEquals("preserved", parsed.data().get("unknownUploadField"));
    assertEquals(List.of("daily", "session", "blocks"), parsed.successfulReports());
    assertEquals(List.of(), parsed.failedReports());
    for (Report report : Report.values()) {
      assertTrue(parsed.succeeded(report));
      assertEquals(1, parsed.rows(report).size());
    }
    assertEquals("preserved", parsed.rows(Report.DAILY).getFirst().get("unknownField"));
    assertEquals(
        "block-1", UsageUpload.firstKey(Report.BLOCKS, parsed.rows(Report.BLOCKS).getFirst()));
    assertEquals("", UsageUpload.secondKey(Report.BLOCKS, parsed.rows(Report.BLOCKS).getFirst()));
  }

  @Test
  void given_reportEnum_when_readingNames_then_matchesWireNames() {
    assertEquals("daily", Report.DAILY.reportName());
    assertEquals("session", Report.SESSION.reportName());
    assertEquals("blocks", Report.BLOCKS.reportName());
  }

  @Test
  void given_failedReports_when_parsing_then_reportsFailuresWithoutRows() {
    Map<String, Object> value =
        upload(
            reports(
                success("daily", new ArrayList<>(List.of(dailyRow()))),
                failed("session failed"),
                failed("blocks failed")));

    UsageUpload parsed = UsageUpload.parse(value, AT);

    assertTrue(parsed.succeeded(Report.DAILY));
    assertFalse(parsed.succeeded(Report.SESSION));
    assertFalse(parsed.succeeded(Report.BLOCKS));
    assertEquals(List.of(), parsed.rows(Report.SESSION));
    assertTrue(parsed.partial());
    assertEquals(List.of("daily"), parsed.successfulReports());
    assertEquals(List.of("session", "blocks"), parsed.failedReports());
  }

  @Test
  void given_boundaryMetadataValues_when_parsing_then_acceptsLimits() {
    Map<String, Object> value = fullUpload();
    value.put("trackerVersion", "v".repeat(128));
    value.put("generatedAt", AT.plus(Duration.ofHours(24)).toString());

    UsageUpload parsed = UsageUpload.parse(value, AT);

    assertEquals("v".repeat(128), parsed.trackerVersion());
    assertEquals(AT.plus(Duration.ofHours(24)), parsed.generatedAt());
  }

  @Test
  void given_invalidUploadMetadata_when_parsing_then_rejectsField() {
    assertRejected(value -> value.remove("trackerVersion"), "trackerVersion is invalid");
    assertRejected(
        value -> value.put("trackerVersion", "v".repeat(129)), "trackerVersion is invalid");
    assertRejected(value -> value.put("ccusageVersion", " "), "ccusageVersion is invalid");
    assertRejected(
        value -> value.put("clientTimeZone", "Mars/Olympus"), "clientTimeZone is invalid");
    assertRejected(value -> value.put("full", "yes"), "full is invalid");
    assertRejected(value -> value.put("generatedAt", "today"), "generatedAt is invalid");
    assertRejected(value -> value.put("generatedAt", false), "generatedAt is invalid");
    assertRejected(value -> value.put("reports", "invalid"), "reports is invalid");
  }

  @Test
  void given_reportsWithoutKnownReport_when_parsing_then_rejectsUpload() {
    var unknown = new LinkedHashMap<String, Object>();
    unknown.put("weekly", success("weekly", new ArrayList<>()));
    Map<String, Object> value = upload(unknown);

    var exception =
        assertThrows(IllegalArgumentException.class, () -> UsageUpload.parse(value, AT));
    assertEquals("reports contains no known report", exception.getMessage());
  }

  @Test
  void given_malformedReportEnvelopes_when_parsing_then_rejectsReport() {
    var notAnObject = new LinkedHashMap<String, Object>();
    notAnObject.put("daily", "invalid");
    assertRejectedUpload(upload(notAnObject), "daily report is invalid");

    var withoutJson = new LinkedHashMap<String, Object>();
    withoutJson.put("ok", true);
    var missingJson = new LinkedHashMap<String, Object>();
    missingJson.put("daily", withoutJson);
    assertRejectedUpload(upload(missingJson), "daily JSON is invalid");

    var withoutOk = new LinkedHashMap<String, Object>();
    withoutOk.put("json", new LinkedHashMap<String, Object>());
    var missingOk = new LinkedHashMap<String, Object>();
    missingOk.put("daily", withoutOk);
    assertRejectedUpload(upload(missingOk), "ok is invalid");

    var withoutError = new LinkedHashMap<String, Object>();
    withoutError.put("ok", false);
    var missingError = new LinkedHashMap<String, Object>();
    missingError.put("daily", withoutError);
    assertRejectedUpload(upload(missingError), "error is invalid");
  }

  @Test
  void given_malformedRowCollections_when_parsing_then_rejectsRows() {
    assertRejectedUpload(reportUpload("daily", "invalid"), "daily rows are invalid");
    assertRejectedUpload(
        reportUpload("daily", new ArrayList<>(List.of("row"))), "daily row is invalid");
  }

  @Test
  void given_invalidDailyMetrics_when_parsing_then_rejectsEachMetric() {
    assertDailyRowRejected(row -> row.put("period", "14.07.2026"), "daily period is invalid");
    assertDailyRowRejected(row -> row.remove("agent"), "agent is invalid");
    assertDailyRowRejected(row -> row.put("inputTokens", -1), "invalid inputTokens");
    assertDailyRowRejected(row -> row.put("outputTokens", 1.5), "invalid outputTokens");
    assertDailyRowRejected(
        row -> row.put("totalTokens", new BigDecimal("9223372036854775808")),
        "invalid totalTokens");
    assertDailyRowRejected(row -> row.remove("cacheCreationTokens"), "invalid cacheCreationTokens");
    assertDailyRowRejected(row -> row.put("cacheReadTokens", "4"), "invalid cacheReadTokens");
    assertDailyRowRejected(row -> row.put("totalCost", -0.5), "invalid totalCost");
    assertDailyRowRejected(row -> row.put("totalCost", Double.NaN), "invalid totalCost");
    assertDailyRowRejected(row -> row.remove("totalCost"), "invalid totalCost");
  }

  @Test
  void given_rowsWithoutRequiredKeys_when_parsing_then_rejectsRow() {
    var session = sessionRow();
    session.remove("agent");
    assertRowRejected("session", session, "session row is invalid", "agent is invalid");
    var block = blockRow();
    block.remove("id");
    assertRowRejected("blocks", block, "blocks row is invalid", "id is invalid");
  }

  @Test
  void given_reportRowLimit_when_parsing_then_acceptsLimitAndRejectsExcess() {
    UsageUpload parsed = UsageUpload.parse(reportUpload("blocks", blockRows(20_000)), AT);
    assertEquals(20_000, parsed.rows(Report.BLOCKS).size());

    Map<String, Object> value = reportUpload("blocks", blockRows(20_001));
    var exception =
        assertThrows(IllegalArgumentException.class, () -> UsageUpload.parse(value, AT));
    assertEquals("blocks rows exceed 20000 entries", exception.getMessage());
  }

  private static void assertRejected(Consumer<Map<String, Object>> mutation, String message) {
    Map<String, Object> value = fullUpload();
    mutation.accept(value);
    assertRejectedUpload(value, message);
  }

  private static void assertRejectedUpload(Map<String, Object> value, String message) {
    var exception =
        assertThrows(IllegalArgumentException.class, () -> UsageUpload.parse(value, AT));
    assertEquals(message, exception.getMessage());
  }

  private static void assertDailyRowRejected(
      Consumer<Map<String, Object>> mutation, String causeMessage) {
    var row = dailyRow();
    mutation.accept(row);
    assertRowRejected("daily", row, "daily row is invalid", causeMessage);
  }

  private static void assertRowRejected(
      String name, Map<String, Object> row, String message, String causeMessage) {
    Map<String, Object> value = reportUpload(name, new ArrayList<>(List.of(row)));
    var exception =
        assertThrows(IllegalArgumentException.class, () -> UsageUpload.parse(value, AT));
    assertEquals(message, exception.getMessage());
    assertEquals(causeMessage, exception.getCause().getMessage());
  }

  private static Map<String, Object> fullUpload() {
    return upload(
        reports(
            success("daily", new ArrayList<>(List.of(dailyRow()))),
            success("session", new ArrayList<>(List.of(sessionRow()))),
            success("blocks", new ArrayList<>(List.of(blockRow())))));
  }

  private static Map<String, Object> reportUpload(String name, Object rows) {
    var reports = new LinkedHashMap<String, Object>();
    reports.put(name, success(name, rows));
    return upload(reports);
  }

  private static Map<String, Object> upload(Map<String, Object> reports) {
    var value = new LinkedHashMap<String, Object>();
    value.put("trackerVersion", "1");
    value.put("ccusageVersion", "20.0.17");
    value.put("clientTimeZone", "UTC");
    value.put("full", true);
    value.put("generatedAt", AT.toString());
    value.put("reports", reports);
    return value;
  }

  private static Map<String, Object> reports(Object daily, Object session, Object blocks) {
    var reports = new LinkedHashMap<String, Object>();
    reports.put("daily", daily);
    reports.put("session", session);
    reports.put("blocks", blocks);
    return reports;
  }

  private static Map<String, Object> success(String name, Object rows) {
    var json = new LinkedHashMap<String, Object>();
    json.put(name, rows);
    var report = new LinkedHashMap<String, Object>();
    report.put("ok", true);
    report.put("json", json);
    return report;
  }

  private static Map<String, Object> failed(String message) {
    var report = new LinkedHashMap<String, Object>();
    report.put("ok", false);
    report.put("error", message);
    return report;
  }

  private static Map<String, Object> dailyRow() {
    var row = new LinkedHashMap<String, Object>();
    row.put("period", "2026-07-14");
    row.put("agent", "claude");
    row.put("inputTokens", 10);
    row.put("outputTokens", 2);
    row.put("cacheCreationTokens", 3);
    row.put("cacheReadTokens", 4);
    row.put("totalTokens", 19);
    row.put("totalCost", 1);
    row.put("unknownField", "preserved");
    return row;
  }

  private static Map<String, Object> sessionRow() {
    var row = new LinkedHashMap<String, Object>();
    row.put("period", "session-1");
    row.put("agent", "claude");
    return row;
  }

  private static Map<String, Object> blockRow() {
    var row = new LinkedHashMap<String, Object>();
    row.put("id", "block-1");
    return row;
  }

  private static List<Map<String, Object>> blockRows(int count) {
    var rows = new ArrayList<Map<String, Object>>(count);
    for (int index = 0; index < count; index++) {
      var row = new LinkedHashMap<String, Object>();
      row.put("id", "block-" + index);
      rows.add(row);
    }
    return rows;
  }
}
