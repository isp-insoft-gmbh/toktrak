package toktrak.usage;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import toktrak.projection.Projection.UserKey;
import toktrak.usage.UsageUpload.Report;

public final class UsageProjection {
  private static final int ROWS_MAX = 100_000;
  private static final int USERS_MAX = 10_000;

  private final Map<Report, Map<Key, Row>> rowsByReport;
  private final Map<UserKey, Ingestion> ingestion;

  private UsageProjection(
      Map<Report, Map<Key, Row>> rowsByReport, Map<UserKey, Ingestion> ingestion) {
    assert rowsByReport != null && rowsByReport.size() == Report.values().length;
    for (Report report : Report.values()) {
      assert rowsByReport.get(report) != null && rowsByReport.get(report).size() <= ROWS_MAX;
    }
    assert ingestion != null && ingestion.size() <= USERS_MAX;
    this.rowsByReport = Map.copyOf(rowsByReport);
    this.ingestion = ingestion;
  }

  public static UsageProjection empty() {
    var rowsByReport = new EnumMap<Report, Map<Key, Row>>(Report.class);
    for (Report report : Report.values()) rowsByReport.put(report, Map.of());
    return new UsageProjection(rowsByReport, Map.of());
  }

  public UsageProjection apply(UserKey owner, UsageUpload upload, Instant receivedAt) {
    Objects.requireNonNull(owner, "owner");
    Objects.requireNonNull(upload, "upload");
    Objects.requireNonNull(receivedAt, "receivedAt");
    var nextRowsByReport = new EnumMap<Report, Map<Key, Row>>(Report.class);
    for (Report report : Report.values()) {
      nextRowsByReport.put(report, update(rowsByReport.get(report), owner, upload, report));
    }
    Map<UserKey, Ingestion> nextIngestion = ingestion;
    Ingestion previous = ingestion.get(owner);
    if (previous == null || !upload.generatedAt().isBefore(previous.generatedAt)) {
      var mutable = new HashMap<>(ingestion);
      mutable.put(
          owner,
          new Ingestion(
              owner,
              upload.generatedAt(),
              receivedAt,
              upload.full(),
              upload.trackerVersion(),
              upload.ccusageVersion(),
              upload.clientTimeZone(),
              upload.successfulReports(),
              upload.failedReports(),
              upload.partial()));
      if (mutable.size() > USERS_MAX)
        throw new IllegalStateException("usage users exceed " + USERS_MAX);
      nextIngestion = Map.copyOf(mutable);
    }
    return new UsageProjection(nextRowsByReport, nextIngestion);
  }

  public List<Row> rows(Report report) {
    Objects.requireNonNull(report, "report");
    Map<Key, Row> rows = rowsByReport.get(report);
    assert rows != null;
    return rows.values().stream()
        .sorted(
            Comparator.comparing((Row row) -> row.owner().toString())
                .thenComparing(Row::firstKey)
                .thenComparing(Row::secondKey))
        .toList();
  }

  public List<Ingestion> ingestion() {
    return ingestion.values().stream()
        .sorted(Comparator.comparing(value -> value.owner().toString()))
        .toList();
  }

  public Summary summary() {
    BigDecimal costUsd = BigDecimal.ZERO;
    long inputTokens = 0;
    long outputTokens = 0;
    long cacheCreationTokens = 0;
    long cacheReadTokens = 0;
    long totalTokens = 0;
    var users = new java.util.HashSet<UserKey>();
    Map<Key, Row> daily = rowsByReport.get(Report.DAILY);
    assert daily != null;
    for (Row row : daily.values()) {
      costUsd = costUsd.add(UsageUpload.nonnegativeDecimal(row.data, "totalCost"));
      inputTokens =
          Math.addExact(inputTokens, UsageUpload.nonnegativeLong(row.data, "inputTokens"));
      outputTokens =
          Math.addExact(outputTokens, UsageUpload.nonnegativeLong(row.data, "outputTokens"));
      cacheCreationTokens =
          Math.addExact(
              cacheCreationTokens, UsageUpload.nonnegativeLong(row.data, "cacheCreationTokens"));
      cacheReadTokens =
          Math.addExact(cacheReadTokens, UsageUpload.nonnegativeLong(row.data, "cacheReadTokens"));
      totalTokens =
          Math.addExact(totalTokens, UsageUpload.nonnegativeLong(row.data, "totalTokens"));
      users.add(row.owner);
    }
    return new Summary(
        costUsd,
        inputTokens,
        outputTokens,
        cacheCreationTokens,
        cacheReadTokens,
        totalTokens,
        daily.size(),
        rowsByReport.get(Report.SESSION).size(),
        rowsByReport.get(Report.BLOCKS).size(),
        users.size());
  }

  private static Map<Key, Row> update(
      Map<Key, Row> before, UserKey owner, UsageUpload upload, Report report) {
    assert before != null && before.size() <= ROWS_MAX;
    assert owner != null;
    assert upload != null;
    assert report != null;
    if (!upload.succeeded(report)) return before;
    var after = new HashMap<>(before);
    for (Map<String, Object> data : upload.rows(report)) {
      String first = UsageUpload.firstKey(report, data);
      String second = UsageUpload.secondKey(report, data);
      var key = new Key(owner, first, second);
      Row existing = after.get(key);
      if (existing == null || !upload.generatedAt().isBefore(existing.generatedAt)) {
        after.put(key, new Row(owner, first, second, upload.generatedAt(), data));
      }
    }
    if (after.size() > ROWS_MAX) {
      throw new IllegalStateException(report.reportName() + " rows exceed " + ROWS_MAX);
    }
    return Map.copyOf(after);
  }

  private record Key(UserKey owner, String first, String second) {
    private Key {
      assert owner != null;
      assert first != null;
      assert second != null;
    }
  }

  public record Row(
      UserKey owner,
      String firstKey,
      String secondKey,
      Instant generatedAt,
      Map<String, Object> data) {
    public Row {
      Objects.requireNonNull(owner, "owner");
      Objects.requireNonNull(firstKey, "firstKey");
      Objects.requireNonNull(secondKey, "secondKey");
      Objects.requireNonNull(generatedAt, "generatedAt");
      Objects.requireNonNull(data, "data");
    }
  }

  public record Ingestion(
      UserKey owner,
      Instant generatedAt,
      Instant receivedAt,
      boolean full,
      String trackerVersion,
      String ccusageVersion,
      String clientTimeZone,
      List<String> successfulReports,
      List<String> failedReports,
      boolean partial) {
    public Ingestion {
      Objects.requireNonNull(owner, "owner");
      Objects.requireNonNull(generatedAt, "generatedAt");
      Objects.requireNonNull(receivedAt, "receivedAt");
      Objects.requireNonNull(trackerVersion, "trackerVersion");
      Objects.requireNonNull(ccusageVersion, "ccusageVersion");
      Objects.requireNonNull(clientTimeZone, "clientTimeZone");
      successfulReports = List.copyOf(successfulReports);
      failedReports = List.copyOf(failedReports);
      assert partial == (successfulReports.size() != Report.values().length);
    }
  }

  public record Summary(
      BigDecimal costUsd,
      long inputTokens,
      long outputTokens,
      long cacheCreationTokens,
      long cacheReadTokens,
      long totalTokens,
      int dailyRows,
      int sessionRows,
      int blockRows,
      int activeUsers) {
    public Summary {
      Objects.requireNonNull(costUsd, "costUsd");
      if (costUsd.signum() < 0
          || inputTokens < 0
          || outputTokens < 0
          || cacheCreationTokens < 0
          || cacheReadTokens < 0
          || totalTokens < 0
          || dailyRows < 0
          || sessionRows < 0
          || blockRows < 0
          || activeUsers < 0) {
        throw new IllegalArgumentException("usage summary is invalid");
      }
    }
  }
}
