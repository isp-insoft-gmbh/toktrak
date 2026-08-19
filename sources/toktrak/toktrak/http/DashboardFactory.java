package toktrak.http;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import toktrak.health.HealthState;
import toktrak.http.DashboardSnapshot.DashboardBlockRow;
import toktrak.http.DashboardSnapshot.DashboardHealth;
import toktrak.http.DashboardSnapshot.DashboardIngestionRow;
import toktrak.http.DashboardSnapshot.DashboardMetricRow;
import toktrak.http.DashboardSnapshot.DashboardSessionRow;
import toktrak.http.DashboardSnapshot.DashboardSpikeRow;
import toktrak.http.DashboardSnapshot.DashboardTrendRow;
import toktrak.http.DashboardSnapshot.DashboardUserRow;
import toktrak.http.DashboardSnapshot.ReportCompleteness;
import toktrak.projection.Projection;
import toktrak.projection.Projection.FxRate;
import toktrak.projection.Projection.User;
import toktrak.projection.Projection.UserKey;
import toktrak.usage.UsageProjection.Ingestion;
import toktrak.usage.UsageProjection.Row;
import toktrak.usage.UsageProjection.Summary;
import toktrak.usage.UsageUpload;
import toktrak.usage.UsageUpload.Report;

final class DashboardFactory {
  private static final int DETAIL_ROWS_MAX = 20;
  private static final int NESTED_ENTRIES_MAX = 100;
  private static final Map<String, String> COLOR_CLASSES =
      Map.of(
          "#f4a6a6", "color-1",
          "#f7c59f", "color-2",
          "#f5e6a8", "color-3",
          "#b9e4c9", "color-4",
          "#a8dadc", "color-5",
          "#b8c0ff", "color-6",
          "#d0b4f4", "color-7",
          "#f4b8d8", "color-8");

  private DashboardFactory() {}

  static DashboardSnapshot create(
      Projection projection, HealthState health, DashboardCurrency currency) {
    assert projection != null;
    assert health != null;
    assert currency != null;
    List<Row> daily = projection.usageRows(Report.DAILY);
    List<Row> sessions = projection.usageRows(Report.SESSION);
    List<Row> blocks = projection.usageRows(Report.BLOCKS);
    List<User> users = projection.users();
    Map<UserKey, User> usersByKey = new HashMap<>();
    for (User user : users) usersByKey.put(user.key(), user);
    FxRate rate = projection.fxRate().orElse(null);
    assert currency == DashboardCurrency.USD || rate != null;

    LocalDate latest =
        daily.stream()
            .map(row -> LocalDate.parse(row.firstKey()))
            .max(LocalDate::compareTo)
            .orElse(null);
    YearMonth month = latest == null ? null : YearMonth.from(latest);
    var monthlyUsers = new LinkedHashMap<UserKey, Totals>();
    var dailyTotals = new LinkedHashMap<LocalDate, Totals>();
    for (Row row : daily) {
      LocalDate date = LocalDate.parse(row.firstKey());
      Totals totals = totals(row);
      dailyTotals.merge(date, totals, Totals::add);
      if (month != null && month.equals(YearMonth.from(date))) {
        monthlyUsers.merge(row.owner(), totals, Totals::add);
      }
    }
    Totals monthly = monthlyUsers.values().stream().reduce(Totals.ZERO, Totals::add);
    String period = month == null ? "No usage yet" : month.toString();
    int activeUsers = monthlyUsers.size();
    BigDecimal average =
        activeUsers == 0
            ? BigDecimal.ZERO
            : monthly.cost.divide(BigDecimal.valueOf(activeUsers), 8, RoundingMode.HALF_UP);

    List<DashboardUserRow> leaderboard =
        userRows(monthlyUsers, usersByKey, currency, rate, DETAIL_ROWS_MAX);
    List<DashboardUserRow> userStreams =
        userRows(allUserTotals(daily), usersByKey, currency, rate, DETAIL_ROWS_MAX);
    List<DashboardTrendRow> trends = trends(dailyTotals, currency, rate, DETAIL_ROWS_MAX);
    Summary summary = projection.usageSummary();
    List<DashboardMetricRow> tokenTypes = tokenTypes(summary);
    List<DashboardMetricRow> models = models(daily, currency, rate);
    List<DashboardMetricRow> sources = sources(daily, currency, rate);
    List<DashboardMetricRow> rhythm = rhythm(blocks, currency, rate);
    List<DashboardSpikeRow> spikes = spikes(daily, usersByKey, currency, rate);
    List<DashboardSessionRow> sessionRows = sessions(sessions, usersByKey, currency, rate);
    List<DashboardBlockRow> blockRows = blocks(blocks, currency, rate);
    List<DashboardIngestionRow> ingestion = ingestion(projection.ingestion(), usersByKey);
    boolean partial = projection.ingestion().stream().anyMatch(Ingestion::partial);

    return new DashboardSnapshot(
        period,
        money(monthly.cost, currency, rate),
        integer(monthly.tokens),
        activeUsers,
        money(average, currency, rate),
        fx(rate, currency),
        health.healthy() ? DashboardHealth.HEALTHY : DashboardHealth.DEGRADED,
        partial ? ReportCompleteness.PARTIAL : ReportCompleteness.COMPLETE,
        leaderboard,
        ingestion,
        tokenTypes,
        models,
        sources,
        rhythm,
        trends,
        userStreams,
        spikes,
        sessionRows,
        blockRows);
  }

  private static List<DashboardUserRow> userRows(
      Map<UserKey, Totals> totals,
      Map<UserKey, User> users,
      DashboardCurrency currency,
      FxRate rate,
      int limit) {
    BigDecimal maximum =
        totals.values().stream()
            .map(value -> value.cost)
            .max(BigDecimal::compareTo)
            .orElse(BigDecimal.ZERO);
    var sorted =
        totals.entrySet().stream()
            .sorted(
                Map.Entry.<UserKey, Totals>comparingByValue(
                        Comparator.comparing(value -> value.cost))
                    .reversed()
                    .thenComparing(entry -> entry.getKey().toString()))
            .limit(limit)
            .toList();
    var result = new ArrayList<DashboardUserRow>(sorted.size());
    int rank = 1;
    for (Map.Entry<UserKey, Totals> entry : sorted) {
      User user = users.get(entry.getKey());
      assert user != null;
      String name = user.displayName();
      String color = colorClass(user.color());
      result.add(
          new DashboardUserRow(
              rank,
              initials(name),
              name,
              color,
              money(entry.getValue().cost, currency, rate),
              integer(entry.getValue().tokens),
              bar(entry.getValue().cost, maximum)));
      rank = Math.addExact(rank, 1);
    }
    return List.copyOf(result);
  }

  private static Map<UserKey, Totals> allUserTotals(List<Row> rows) {
    var result = new LinkedHashMap<UserKey, Totals>();
    for (Row row : rows) result.merge(row.owner(), totals(row), Totals::add);
    return result;
  }

  private static List<DashboardTrendRow> trends(
      Map<LocalDate, Totals> totals, DashboardCurrency currency, FxRate rate, int limit) {
    List<Map.Entry<LocalDate, Totals>> rows =
        totals.entrySet().stream()
            .sorted(Map.Entry.<LocalDate, Totals>comparingByKey().reversed())
            .limit(limit)
            .sorted(Map.Entry.comparingByKey())
            .toList();
    BigDecimal maximum =
        rows.stream()
            .map(entry -> entry.getValue().cost)
            .max(BigDecimal::compareTo)
            .orElse(BigDecimal.ZERO);
    return rows.stream()
        .map(
            entry ->
                new DashboardTrendRow(
                    entry.getKey().toString(),
                    money(entry.getValue().cost, currency, rate),
                    integer(entry.getValue().tokens),
                    bar(entry.getValue().cost, maximum)))
        .toList();
  }

  private static List<DashboardMetricRow> tokenTypes(Summary summary) {
    long maximum =
        Math.max(
            Math.max(summary.inputTokens(), summary.outputTokens()),
            Math.max(summary.cacheCreationTokens(), summary.cacheReadTokens()));
    return List.of(
        metric("Input", summary.inputTokens(), maximum),
        metric("Output", summary.outputTokens(), maximum),
        metric("Cache creation", summary.cacheCreationTokens(), maximum),
        metric("Cache read", summary.cacheReadTokens(), maximum));
  }

  private static DashboardMetricRow metric(String label, long value, long maximum) {
    String percentage =
        maximum == 0
            ? "0% of largest type"
            : BigDecimal.valueOf(value)
                    .multiply(BigDecimal.valueOf(100))
                    .divide(BigDecimal.valueOf(maximum), 0, RoundingMode.HALF_UP)
                    .toPlainString()
                + "% of largest type";
    return new DashboardMetricRow(
        label,
        integer(value),
        percentage,
        bar(BigDecimal.valueOf(value), BigDecimal.valueOf(maximum)));
  }

  private static List<DashboardMetricRow> models(
      List<Row> rows, DashboardCurrency currency, FxRate rate) {
    var totals = new HashMap<String, Totals>();
    for (Row row : rows) {
      for (Map<String, Object> breakdown : objectList(row.data().get("modelBreakdowns"))) {
        String name = text(breakdown.get("modelName"), "Unknown model", 256);
        BigDecimal cost = decimal(breakdown.get("cost"));
        long tokens =
            add(
                whole(breakdown.get("inputTokens")),
                whole(breakdown.get("outputTokens")),
                whole(breakdown.get("cacheCreationTokens")),
                whole(breakdown.get("cacheReadTokens")));
        totals.merge(name, new Totals(cost, tokens), Totals::add);
      }
    }
    return metricRows(totals, currency, rate);
  }

  private static List<DashboardMetricRow> sources(
      List<Row> rows, DashboardCurrency currency, FxRate rate) {
    var totals = new HashMap<String, Totals>();
    for (Row row : rows) {
      String source = row.secondKey();
      if (row.data().get("metadata") instanceof Map<?, ?> metadata) {
        List<String> agents = strings(metadata.get("agents"), 8);
        if (!agents.isEmpty()) source = String.join(" + ", agents);
      }
      totals.merge(source, totals(row), Totals::add);
    }
    return metricRows(totals, currency, rate);
  }

  private static List<DashboardMetricRow> metricRows(
      Map<String, Totals> totals, DashboardCurrency currency, FxRate rate) {
    BigDecimal maximum =
        totals.values().stream()
            .map(value -> value.cost)
            .max(BigDecimal::compareTo)
            .orElse(BigDecimal.ZERO);
    return totals.entrySet().stream()
        .sorted(
            Map.Entry.<String, Totals>comparingByValue(Comparator.comparing(value -> value.cost))
                .reversed()
                .thenComparing(Map.Entry.comparingByKey()))
        .limit(DETAIL_ROWS_MAX)
        .map(
            entry ->
                new DashboardMetricRow(
                    entry.getKey(),
                    money(entry.getValue().cost, currency, rate),
                    integer(entry.getValue().tokens) + " tokens",
                    bar(entry.getValue().cost, maximum)))
        .toList();
  }

  private static List<DashboardMetricRow> rhythm(
      List<Row> rows, DashboardCurrency currency, FxRate rate) {
    var totals = new EnumMap<DayOfWeek, Totals>(DayOfWeek.class);
    for (DayOfWeek day : DayOfWeek.values()) totals.put(day, Totals.ZERO);
    for (Row row : rows) {
      try {
        DayOfWeek day =
            Instant.parse(text(row.data().get("startTime"), "", 64))
                .atZone(java.time.ZoneOffset.UTC)
                .getDayOfWeek();
        totals.merge(
            day,
            new Totals(decimal(row.data().get("costUSD")), whole(row.data().get("totalTokens"))),
            Totals::add);
      } catch (DateTimeParseException ignored) {
        // Unknown optional block timestamps do not make canonical daily totals unusable.
      }
    }
    BigDecimal maximum =
        totals.values().stream()
            .map(value -> value.cost)
            .max(BigDecimal::compareTo)
            .orElse(BigDecimal.ZERO);
    return totals.entrySet().stream()
        .map(
            entry ->
                new DashboardMetricRow(
                    title(entry.getKey().name()),
                    money(entry.getValue().cost, currency, rate),
                    integer(entry.getValue().tokens) + " tokens",
                    bar(entry.getValue().cost, maximum)))
        .toList();
  }

  private static List<DashboardSpikeRow> spikes(
      List<Row> rows, Map<UserKey, User> users, DashboardCurrency currency, FxRate rate) {
    return rows.stream()
        .sorted(
            Comparator.comparing(
                    (Row row) -> UsageUpload.nonnegativeDecimal(row.data(), "totalCost"))
                .reversed()
                .thenComparing(Row::firstKey)
                .thenComparing(row -> row.owner().toString()))
        .limit(DETAIL_ROWS_MAX)
        .map(
            row -> {
              User user = users.get(row.owner());
              assert user != null;
              return new DashboardSpikeRow(
                  row.firstKey(),
                  user.displayName(),
                  money(UsageUpload.nonnegativeDecimal(row.data(), "totalCost"), currency, rate),
                  integer(UsageUpload.nonnegativeLong(row.data(), "totalTokens")));
            })
        .toList();
  }

  private static List<DashboardSessionRow> sessions(
      List<Row> rows, Map<UserKey, User> users, DashboardCurrency currency, FxRate rate) {
    return rows.stream()
        .sorted(
            Comparator.comparing((Row row) -> decimal(row.data().get("totalCost")))
                .reversed()
                .thenComparing(Row::firstKey))
        .limit(DETAIL_ROWS_MAX)
        .map(
            row -> {
              User user = users.get(row.owner());
              assert user != null;
              Map<?, ?> metadata =
                  row.data().get("metadata") instanceof Map<?, ?> value ? value : Map.of();
              return new DashboardSessionRow(
                  user.displayName(),
                  row.secondKey(),
                  project(text(metadata.get("projectPath"), "Not reported", 2_048)),
                  String.join(", ", strings(row.data().get("modelsUsed"), 3)),
                  money(decimal(row.data().get("totalCost")), currency, rate),
                  integer(whole(row.data().get("totalTokens"))),
                  text(metadata.get("lastActivity"), "Unknown", 64));
            })
        .toList();
  }

  private static List<DashboardBlockRow> blocks(
      List<Row> rows, DashboardCurrency currency, FxRate rate) {
    return rows.stream()
        .sorted(
            Comparator.comparing(
                    (Row row) -> text(row.data().get("startTime"), "", 64),
                    Comparator.reverseOrder())
                .thenComparing(Row::firstKey))
        .limit(DETAIL_ROWS_MAX)
        .map(
            row ->
                new DashboardBlockRow(
                    text(row.data().get("startTime"), "Unknown", 64),
                    String.join(", ", strings(row.data().get("models"), 3)),
                    money(decimal(row.data().get("costUSD")), currency, rate),
                    integer(whole(row.data().get("totalTokens"))),
                    integer(whole(row.data().get("entries")))))
        .toList();
  }

  private static List<DashboardIngestionRow> ingestion(
      List<Ingestion> rows, Map<UserKey, User> users) {
    return rows.stream()
        .map(
            row -> {
              User user = users.get(row.owner());
              assert user != null;
              String name = user.displayName();
              String detail =
                  row.partial()
                      ? "Missing: " + String.join(", ", row.failedReports())
                      : "All reports · Tracker v" + row.trackerVersion();
              return new DashboardIngestionRow(
                  initials(name),
                  name,
                  colorClass(user.color()),
                  row.partial() ? "Partial" : "Complete",
                  row.partial() ? "status-warn" : "status-ok",
                  row.generatedAt().toString(),
                  detail);
            })
        .toList();
  }

  private static Totals totals(Row row) {
    return new Totals(
        UsageUpload.nonnegativeDecimal(row.data(), "totalCost"),
        UsageUpload.nonnegativeLong(row.data(), "totalTokens"));
  }

  private static String money(BigDecimal usd, DashboardCurrency currency, FxRate rate) {
    assert usd != null && usd.signum() >= 0;
    BigDecimal value =
        currency == DashboardCurrency.EUR
            ? usd.multiply(Objects.requireNonNull(rate).eurPerUsd())
            : usd;
    var symbols = DecimalFormatSymbols.getInstance(Locale.US);
    var format = new DecimalFormat("#,##0.00", symbols);
    format.setRoundingMode(RoundingMode.HALF_UP);
    return currency.symbol() + format.format(value);
  }

  private static String fx(FxRate rate, DashboardCurrency currency) {
    if (rate == null) return "EUR unavailable · showing USD";
    String state = currency == DashboardCurrency.EUR ? "Showing EUR estimates" : "USD canonical";
    return state
        + " · 1 USD = "
        + rate.eurPerUsd().stripTrailingZeros().toPlainString()
        + " EUR · "
        + rate.date();
  }

  private static String integer(long value) {
    if (value < 0) throw new IllegalArgumentException("value is negative");
    return new DecimalFormat("#,##0", DecimalFormatSymbols.getInstance(Locale.US)).format(value);
  }

  private static String bar(BigDecimal value, BigDecimal maximum) {
    int filled =
        maximum.signum() == 0
            ? 0
            : value
                .multiply(BigDecimal.TEN)
                .divide(maximum, 0, RoundingMode.CEILING)
                .intValueExact();
    filled = Math.clamp(filled, 0, 10);
    return "█".repeat(filled) + "░".repeat(10 - filled);
  }

  private static String initials(String name) {
    String stripped = name.strip();
    int lastStart = 0;
    boolean afterWhitespace = false;
    for (int index = 0; index < stripped.length(); ) {
      int codePoint = stripped.codePointAt(index);
      if (Character.isWhitespace(codePoint)) {
        afterWhitespace = true;
      } else if (afterWhitespace) {
        lastStart = index;
        afterWhitespace = false;
      }
      index += Character.charCount(codePoint);
    }
    String first = stripped.substring(0, stripped.offsetByCodePoints(0, 1));
    String last =
        lastStart == 0
            ? ""
            : stripped.substring(lastStart, stripped.offsetByCodePoints(lastStart, 1));
    return (first + last).toUpperCase(Locale.ROOT);
  }

  private static String colorClass(String color) {
    return COLOR_CLASSES.getOrDefault(color, "color-1");
  }

  private static String project(String path) {
    int separator = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
    String name = path.substring(separator + 1);
    return name.endsWith(".jsonl") ? name.substring(0, name.length() - 6) : name;
  }

  private static String title(String value) {
    return value.substring(0, 1) + value.substring(1).toLowerCase(Locale.ROOT);
  }

  private static List<Map<String, Object>> objectList(Object value) {
    if (!(value instanceof List<?> list)) return List.of();
    var result = new ArrayList<Map<String, Object>>(Math.min(list.size(), NESTED_ENTRIES_MAX));
    for (Object element : list) {
      if (result.size() == NESTED_ENTRIES_MAX) break;
      if (!(element instanceof Map<?, ?> map)) continue;
      var converted = new HashMap<String, Object>();
      for (Map.Entry<?, ?> entry : map.entrySet()) {
        if (entry.getKey() instanceof String key) converted.put(key, entry.getValue());
      }
      result.add(Map.copyOf(converted));
    }
    return List.copyOf(result);
  }

  private static List<String> strings(Object value, int limit) {
    if (!(value instanceof List<?> list)) return List.of();
    Set<String> result = new HashSet<>();
    for (Object element : list) {
      if (result.size() == limit) break;
      if (element instanceof String text && !text.isBlank() && text.length() <= 256)
        result.add(text);
    }
    return result.stream().sorted().toList();
  }

  private static String text(Object value, String fallback, int lengthMax) {
    return value instanceof String text && !text.isBlank() && text.length() <= lengthMax
        ? text
        : fallback;
  }

  private static BigDecimal decimal(Object value) {
    if (!(value instanceof Number number)) return BigDecimal.ZERO;
    try {
      BigDecimal result = new BigDecimal(number.toString());
      return result.signum() < 0 ? BigDecimal.ZERO : result;
    } catch (NumberFormatException exception) {
      return BigDecimal.ZERO;
    }
  }

  private static long whole(Object value) {
    if (!(value instanceof Number number)) return 0;
    try {
      long result = new BigDecimal(number.toString()).longValueExact();
      return Math.max(0, result);
    } catch (ArithmeticException | NumberFormatException exception) {
      return 0;
    }
  }

  private static long add(long... values) {
    long result = 0;
    for (long value : values) result = Math.addExact(result, value);
    return result;
  }

  private record Totals(BigDecimal cost, long tokens) {
    private static final Totals ZERO = new Totals(BigDecimal.ZERO, 0);

    private Totals {
      assert cost != null && cost.signum() >= 0;
      assert tokens >= 0;
    }

    private Totals add(Totals other) {
      return new Totals(cost.add(other.cost), Math.addExact(tokens, other.tokens));
    }
  }
}
