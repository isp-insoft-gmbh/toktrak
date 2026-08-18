package toktrak.usage;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import toktrak.json.Json;
import toktrak.store.EventEnvelope;

public final class UsageUpload {
  private static final Duration FUTURE_TOLERANCE = Duration.ofHours(24);
  private static final int VERSION_CHARACTERS_MAX = 128;
  private static final int TIME_ZONE_CHARACTERS_MAX = 128;
  private static final int ERROR_CHARACTERS_MAX = 2_048;
  private static final int KEY_CHARACTERS_MAX = 2_048;
  private static final int REPORT_ROWS_MAX = 20_000;

  private final Map<String, Object> data;
  private final String trackerVersion;
  private final String ccusageVersion;
  private final String clientTimeZone;
  private final boolean full;
  private final Instant generatedAt;
  private final Map<Report, List<Map<String, Object>>> rows;
  private final List<String> successfulReports;
  private final List<String> failedReports;

  private UsageUpload(
      Map<String, Object> data,
      String trackerVersion,
      String ccusageVersion,
      String clientTimeZone,
      boolean full,
      Instant generatedAt,
      Map<Report, List<Map<String, Object>>> rows,
      List<String> successfulReports,
      List<String> failedReports) {
    assert data != null;
    assert trackerVersion != null;
    assert ccusageVersion != null;
    assert clientTimeZone != null;
    assert generatedAt != null;
    assert rows != null;
    assert successfulReports != null;
    assert failedReports != null;
    this.data = data;
    this.trackerVersion = trackerVersion;
    this.ccusageVersion = ccusageVersion;
    this.clientTimeZone = clientTimeZone;
    this.full = full;
    this.generatedAt = generatedAt;
    this.rows = Map.copyOf(rows);
    this.successfulReports = List.copyOf(successfulReports);
    this.failedReports = List.copyOf(failedReports);
  }

  public static UsageUpload parse(byte[] input, Instant receivedAt) {
    Objects.requireNonNull(input, "input");
    return parse(Json.read(input, Map.class), receivedAt);
  }

  public static UsageUpload parse(Map<?, ?> input, Instant receivedAt) {
    Objects.requireNonNull(input, "input");
    Objects.requireNonNull(receivedAt, "receivedAt");
    EventEnvelope.create("usage-validation", receivedAt, null, Map.of("upload", input));
    Map<String, Object> data = object(input, "upload");
    String trackerVersion = string(data, "trackerVersion", VERSION_CHARACTERS_MAX);
    String ccusageVersion = string(data, "ccusageVersion", VERSION_CHARACTERS_MAX);
    String clientTimeZone = string(data, "clientTimeZone", TIME_ZONE_CHARACTERS_MAX);
    try {
      var _ = ZoneId.of(clientTimeZone);
    } catch (DateTimeException exception) {
      throw new IllegalArgumentException("clientTimeZone is invalid", exception);
    }
    boolean full = bool(data, "full");
    Instant generatedAt = instant(data, "generatedAt");
    if (generatedAt.isAfter(receivedAt.plus(FUTURE_TOLERANCE))) {
      throw new IllegalArgumentException("generatedAt is more than 24 hours ahead");
    }
    Map<String, Object> reports = object(data.get("reports"), "reports");
    var rows = new EnumMap<Report, List<Map<String, Object>>>(Report.class);
    var successful = new ArrayList<String>();
    var failed = new ArrayList<String>();
    int found = 0;
    for (Report report : Report.values()) {
      Object value = reports.get(report.name);
      if (value == null) continue;
      found = Math.addExact(found, 1);
      Map<String, Object> result = object(value, report.name + " report");
      if (bool(result, "ok")) {
        Map<String, Object> json = object(result.get("json"), report.name + " JSON");
        List<Map<String, Object>> reportRows = rows(json.get(report.name), report);
        rows.put(report, reportRows);
        successful.add(report.name);
      } else {
        string(result, "error", ERROR_CHARACTERS_MAX);
        failed.add(report.name);
      }
    }
    if (found == 0) throw new IllegalArgumentException("reports contains no known report");
    return new UsageUpload(
        data,
        trackerVersion,
        ccusageVersion,
        clientTimeZone,
        full,
        generatedAt,
        rows,
        successful,
        failed);
  }

  public Map<String, Object> data() {
    return data;
  }

  public String trackerVersion() {
    return trackerVersion;
  }

  public String ccusageVersion() {
    return ccusageVersion;
  }

  public String clientTimeZone() {
    return clientTimeZone;
  }

  public boolean full() {
    return full;
  }

  public Instant generatedAt() {
    return generatedAt;
  }

  public List<Map<String, Object>> rows(Report report) {
    Objects.requireNonNull(report, "report");
    return rows.getOrDefault(report, List.of());
  }

  public boolean succeeded(Report report) {
    Objects.requireNonNull(report, "report");
    return rows.containsKey(report);
  }

  public List<String> successfulReports() {
    return successfulReports;
  }

  public List<String> failedReports() {
    return failedReports;
  }

  public boolean partial() {
    return successfulReports.size() != Report.values().length;
  }

  public static String firstKey(Report report, Map<String, Object> row) {
    Objects.requireNonNull(report, "report");
    Objects.requireNonNull(row, "row");
    return switch (report) {
      case DAILY, SESSION -> string(row, "period", KEY_CHARACTERS_MAX);
      case BLOCKS -> string(row, "id", KEY_CHARACTERS_MAX);
    };
  }

  public static String secondKey(Report report, Map<String, Object> row) {
    Objects.requireNonNull(report, "report");
    Objects.requireNonNull(row, "row");
    return switch (report) {
      case DAILY, SESSION -> string(row, "agent", KEY_CHARACTERS_MAX);
      case BLOCKS -> "";
    };
  }

  public static long nonnegativeLong(Map<String, Object> data, String name) {
    Object value = data.get(name);
    if (!(value instanceof Number number)) throw new IllegalStateException("invalid " + name);
    try {
      long result = new BigDecimal(number.toString()).longValueExact();
      if (result < 0) throw new ArithmeticException();
      return result;
    } catch (ArithmeticException | NumberFormatException exception) {
      throw new IllegalStateException("invalid " + name, exception);
    }
  }

  public static BigDecimal nonnegativeDecimal(Map<String, Object> data, String name) {
    Object value = data.get(name);
    if (!(value instanceof Number number)) throw new IllegalStateException("invalid " + name);
    try {
      BigDecimal result = new BigDecimal(number.toString());
      if (result.signum() < 0) throw new NumberFormatException();
      return result;
    } catch (NumberFormatException exception) {
      throw new IllegalStateException("invalid " + name, exception);
    }
  }

  private static List<Map<String, Object>> rows(Object value, Report report) {
    if (!(value instanceof List<?> list)) {
      throw new IllegalArgumentException(report.name + " rows are invalid");
    }
    if (list.size() > REPORT_ROWS_MAX) {
      throw new IllegalArgumentException(
          report.name + " rows exceed " + REPORT_ROWS_MAX + " entries");
    }
    var result = new ArrayList<Map<String, Object>>(list.size());
    for (Object element : list) {
      Map<String, Object> row = object(element, report.name + " row");
      try {
        validateRow(report, row);
      } catch (IllegalArgumentException | IllegalStateException exception) {
        throw new IllegalArgumentException(report.name + " row is invalid", exception);
      }
      result.add(row);
    }
    return List.copyOf(result);
  }

  private static void validateRow(Report report, Map<String, Object> row) {
    String first = firstKey(report, row);
    secondKey(report, row);
    if (report == Report.DAILY) {
      try {
        LocalDate.parse(first);
      } catch (DateTimeParseException exception) {
        throw new IllegalArgumentException("daily period is invalid", exception);
      }
      nonnegativeLong(row, "inputTokens");
      nonnegativeLong(row, "outputTokens");
      nonnegativeLong(row, "cacheCreationTokens");
      nonnegativeLong(row, "cacheReadTokens");
      nonnegativeLong(row, "totalTokens");
      nonnegativeDecimal(row, "totalCost");
    }
  }

  private static Map<String, Object> object(Object value, String name) {
    if (!(value instanceof Map<?, ?> map)) throw new IllegalArgumentException(name + " is invalid");
    var result = new LinkedHashMap<String, Object>();
    for (Map.Entry<?, ?> entry : map.entrySet()) {
      if (!(entry.getKey() instanceof String key)) {
        throw new IllegalArgumentException(name + " keys are invalid");
      }
      result.put(key, entry.getValue());
    }
    return Collections.unmodifiableMap(result);
  }

  private static String string(Map<String, Object> data, String name, int charactersMax) {
    Object value = data.get(name);
    if (!(value instanceof String string)
        || string.isBlank()
        || string.length() > charactersMax
        || string.getBytes(StandardCharsets.UTF_8).length > charactersMax * 4L) {
      throw new IllegalArgumentException(name + " is invalid");
    }
    return string;
  }

  private static boolean bool(Map<String, Object> data, String name) {
    Object value = data.get(name);
    if (!(value instanceof Boolean result))
      throw new IllegalArgumentException(name + " is invalid");
    return result;
  }

  private static Instant instant(Map<String, Object> data, String name) {
    try {
      return Instant.parse(string(data, name, 64));
    } catch (IllegalArgumentException | DateTimeParseException exception) {
      throw new IllegalArgumentException(name + " is invalid", exception);
    }
  }

  public enum Report {
    DAILY("daily"),
    SESSION("session"),
    BLOCKS("blocks");

    private final String name;

    Report(String name) {
      this.name = name;
    }

    public String reportName() {
      return name;
    }
  }
}
