package toktrak.http;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import toktrak.http.UsageCharts.ColumnChart;
import toktrak.http.UsageCharts.DataRow;
import toktrak.http.UsageCharts.Kpi;
import toktrak.http.UsageCharts.LegendItem;
import toktrak.http.UsageCharts.RangeOption;
import toktrak.http.UsageCharts.Selection;
import toktrak.http.UsageCharts.SvgLabel;
import toktrak.http.UsageCharts.SvgPath;
import toktrak.http.UsageCharts.SvgRect;
import toktrak.http.UsageCharts.SvgTick;
import toktrak.projection.Projection.FxRate;
import toktrak.projection.Projection.UserKey;
import toktrak.usage.UsageProjection.Row;
import toktrak.usage.UsageUpload;

/// Builds range-scoped chart models from canonical daily usage rows.
final class UsageChartsFactory {
  static final String PATH = "/visualizations";
  private static final int DAILY_BUCKETS_DAYS_MAX = 92;
  private static final int WEEKLY_BUCKETS_DAYS_MAX = 731;
  private static final int SERIES_MAX = 6;
  private static final int AGENTS_PER_ROW_MAX = 64;
  private static final int AGENT_NAME_CHARACTERS_MAX = 64;
  private static final int X_LABELS_MAX = 8;
  private static final int Y_TICKS = 4;
  private static final int AVERAGE_DAYS = 7;
  private static final String NOT_REPORTED = "Not reported";
  private static final BigDecimal ROUNDING_SLACK = new BigDecimal("0.000001");
  private static final String OTHER_TOOLS = "Other tools";
  private static final double WIDTH = 960;
  private static final double HEIGHT = 260;
  private static final double PAD_LEFT = 64;
  private static final double PAD_RIGHT = 8;
  private static final double PAD_TOP = 10;
  private static final double PAD_BOTTOM = 24;
  private static final DateTimeFormatter DAY_LABEL =
      DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH);
  private static final DateTimeFormatter FULL_DAY_LABEL =
      DateTimeFormatter.ofPattern("d MMM uuuu", Locale.ENGLISH);
  private static final DateTimeFormatter MONTH_LABEL =
      DateTimeFormatter.ofPattern("MMM uuuu", Locale.ENGLISH);

  private UsageChartsFactory() {}

  enum Bucket {
    DAY("day"),
    WEEK("week"),
    MONTH("month");

    private final String noun;

    Bucket(String noun) {
      this.noun = noun;
    }

    static Bucket of(UsageRange range) {
      if (range.days() <= DAILY_BUCKETS_DAYS_MAX) return DAY;
      return range.days() <= WEEKLY_BUCKETS_DAYS_MAX ? WEEK : MONTH;
    }

    LocalDate start(LocalDate date) {
      return switch (this) {
        case DAY -> date;
        case WEEK -> date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        case MONTH -> date.withDayOfMonth(1);
      };
    }

    LocalDate next(LocalDate start) {
      return switch (this) {
        case DAY -> start.plusDays(1);
        case WEEK -> start.plusWeeks(1);
        case MONTH -> start.plusMonths(1);
      };
    }

    String label(LocalDate start) {
      return this == MONTH ? MONTH_LABEL.format(start) : DAY_LABEL.format(start);
    }
  }

  static UsageCharts create(
      List<Row> dailyRows,
      UsageRange range,
      LocalDate today,
      DashboardCurrency currency,
      FxRate rate) {
    Objects.requireNonNull(dailyRows, "dailyRows");
    Objects.requireNonNull(range, "range");
    Objects.requireNonNull(today, "today");
    Objects.requireNonNull(currency, "currency");
    assert currency == DashboardCurrency.USD || rate != null;

    Bucket bucket = Bucket.of(range);
    var buckets = new TreeMap<LocalDate, Map<String, BigDecimal>>();
    for (LocalDate start = bucket.start(range.from());
        !start.isAfter(range.to());
        start = bucket.next(start)) {
      buckets.put(start, new HashMap<>());
    }
    var toolTotals = new HashMap<String, BigDecimal>();
    var personDays = new HashMap<UserKey, Map<LocalDate, BigDecimal>>();
    BigDecimal total = BigDecimal.ZERO;
    for (Row row : dailyRows) {
      LocalDate date = LocalDate.parse(row.firstKey());
      if (!range.contains(date)) continue;
      BigDecimal rowCost = UsageUpload.nonnegativeDecimal(row.data(), "totalCost");
      total = total.add(rowCost);
      personDays
          .computeIfAbsent(row.owner(), owner -> new HashMap<>())
          .merge(date, rowCost, BigDecimal::add);
      Map<String, BigDecimal> bucketTools = buckets.get(bucket.start(date));
      assert bucketTools != null;
      for (Map.Entry<String, BigDecimal> tool : toolCosts(row, rowCost).entrySet()) {
        bucketTools.merge(tool.getKey(), tool.getValue(), BigDecimal::add);
        toolTotals.merge(tool.getKey(), tool.getValue(), BigDecimal::add);
      }
    }
    assert toolTotals.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add).compareTo(total)
        == 0;

    List<String> series = series(toolTotals);
    long elapsedDays = elapsedDays(range, today);
    return new UsageCharts(
        range.label(),
        range.from().toString(),
        range.to().toString(),
        Long.toString(range.days()),
        rangeOptions(range),
        kpis(total, elapsedDays, personDays, currency, rate),
        dailyCost(bucket, buckets, series, toolTotals, total, range, currency, rate));
  }

  /// Costs per tool for one daily row; every row's cost is attributed exactly once. Reported tool
  /// costs within {@code ROUNDING_SLACK} of the row total absorb that rounding noise. Tool costs
  /// that disagree more are ignored, so the row falls back to a single reported tool or to Not
  /// reported instead of being split arbitrarily.
  private static Map<String, BigDecimal> toolCosts(Row row, BigDecimal rowCost) {
    var reported = new LinkedHashMap<String, BigDecimal>();
    if (row.data().get("agents") instanceof List<?> agents) {
      int seen = 0;
      for (Object element : agents) {
        seen = Math.addExact(seen, 1);
        if (seen > AGENTS_PER_ROW_MAX) break;
        if (!(element instanceof Map<?, ?> agent)) continue;
        if (!(agent.get("agent") instanceof String name)
            || name.isBlank()
            || name.length() > AGENT_NAME_CHARACTERS_MAX
            || !(agent.get("totalCost") instanceof Number number)) {
          continue;
        }
        try {
          BigDecimal cost = new BigDecimal(number.toString());
          if (cost.signum() > 0) reported.merge(name, cost, BigDecimal::add);
        } catch (NumberFormatException exception) {
          // Malformed optional tool costs leave the row to the fallbacks below.
        }
      }
    }
    BigDecimal reportedTotal = reported.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    var result = new LinkedHashMap<String, BigDecimal>();
    BigDecimal attributed = BigDecimal.ZERO;
    if (reportedTotal.subtract(rowCost).compareTo(ROUNDING_SLACK) <= 0) {
      for (Map.Entry<String, BigDecimal> tool : reported.entrySet()) {
        BigDecimal cost = tool.getValue().min(rowCost.subtract(attributed));
        if (cost.signum() <= 0) continue;
        result.put(tool.getKey(), cost);
        attributed = attributed.add(cost);
      }
    }
    if (result.isEmpty()
        && row.data().get("metadata") instanceof Map<?, ?> metadata
        && metadata.get("agents") instanceof List<?> names
        && names.size() == 1
        && names.getFirst() instanceof String name
        && !name.isBlank()
        && name.length() <= AGENT_NAME_CHARACTERS_MAX
        && rowCost.signum() > 0) {
      result.put(name, rowCost);
      attributed = rowCost;
    }
    BigDecimal unattributed = rowCost.subtract(attributed);
    assert unattributed.signum() >= 0;
    if (unattributed.signum() > 0
        && !result.isEmpty()
        && unattributed.compareTo(ROUNDING_SLACK) <= 0) {
      String largest =
          result.entrySet().stream().max(Map.Entry.comparingByValue()).orElseThrow().getKey();
      result.merge(largest, unattributed, BigDecimal::add);
    } else if (unattributed.signum() > 0) {
      result.merge(NOT_REPORTED, unattributed, BigDecimal::add);
    }
    return result;
  }

  /// Tool names in descending cost order; the long tail folds into one final series.
  private static List<String> series(Map<String, BigDecimal> toolTotals) {
    List<String> names =
        toolTotals.entrySet().stream()
            .sorted(
                Map.Entry.<String, BigDecimal>comparingByValue()
                    .reversed()
                    .thenComparing(Map.Entry.comparingByKey()))
            .map(Map.Entry::getKey)
            .toList();
    if (names.size() <= SERIES_MAX) return names;
    var result = new ArrayList<>(names.subList(0, SERIES_MAX - 1));
    result.add(OTHER_TOOLS);
    return List.copyOf(result);
  }

  private static String seriesOf(List<String> series, String tool) {
    int index = series.indexOf(tool);
    if (index < 0) index = series.indexOf(OTHER_TOOLS);
    assert index >= 0 && index < SERIES_MAX;
    return "series-" + (index + 1);
  }

  private static long elapsedDays(UsageRange range, LocalDate today) {
    LocalDate end = range.to().isAfter(today) ? today : range.to();
    return end.isBefore(range.from()) ? 0 : ChronoUnit.DAYS.between(range.from(), end) + 1;
  }

  private static List<RangeOption> rangeOptions(UsageRange range) {
    YearMonth month = YearMonth.from(range.to());
    int year = range.to().getYear();
    return List.of(
        option("Month", UsageRange.month(month), range, UsageRange.Kind.MONTH),
        option(
            "Year",
            new UsageRange(
                UsageRange.Kind.YEAR, LocalDate.of(year, 1, 1), LocalDate.of(year, 12, 31)),
            range,
            UsageRange.Kind.YEAR),
        new RangeOption(
            "All time",
            PATH + "?range=all",
            range.kind() == UsageRange.Kind.ALL ? Selection.CURRENT : Selection.OTHER));
  }

  private static RangeOption option(
      String label, UsageRange target, UsageRange current, UsageRange.Kind kind) {
    return new RangeOption(
        label,
        PATH + "?" + target.query(),
        current.kind() == kind ? Selection.CURRENT : Selection.OTHER);
  }

  private static List<Kpi> kpis(
      BigDecimal total,
      long elapsedDays,
      Map<UserKey, Map<LocalDate, BigDecimal>> personDays,
      DashboardCurrency currency,
      FxRate rate) {
    List<BigDecimal> dayCosts =
        personDays.values().stream()
            .flatMap(days -> days.values().stream())
            .filter(cost -> cost.signum() > 0)
            .sorted()
            .toList();
    long daysWithUsage =
        personDays.values().stream()
            .flatMap(days -> days.entrySet().stream())
            .filter(entry -> entry.getValue().signum() > 0)
            .map(Map.Entry::getKey)
            .distinct()
            .count();
    long people =
        personDays.values().stream()
            .filter(days -> days.values().stream().anyMatch(cost -> cost.signum() > 0))
            .count();
    BigDecimal perDay =
        elapsedDays == 0
            ? BigDecimal.ZERO
            : total.divide(BigDecimal.valueOf(elapsedDays), 8, RoundingMode.HALF_UP);
    BigDecimal typical = dayCosts.isEmpty() ? BigDecimal.ZERO : median(dayCosts);
    return List.of(
        new Kpi("Estimated cost", DashboardFactory.money(total, currency, rate)),
        new Kpi("Per calendar day", DashboardFactory.money(perDay, currency, rate)),
        new Kpi("Days with usage", daysWithUsage + " of " + elapsedDays),
        new Kpi("People with usage", Long.toString(people)),
        new Kpi("Typical person-day", DashboardFactory.money(typical, currency, rate)));
  }

  private static BigDecimal median(List<BigDecimal> sorted) {
    assert !sorted.isEmpty();
    int middle = sorted.size() / 2;
    return sorted.size() % 2 == 1
        ? sorted.get(middle)
        : sorted
            .get(middle - 1)
            .add(sorted.get(middle))
            .divide(BigDecimal.valueOf(2), 8, RoundingMode.HALF_UP);
  }

  private static ColumnChart dailyCost(
      Bucket bucket,
      Map<LocalDate, Map<String, BigDecimal>> buckets,
      List<String> series,
      Map<String, BigDecimal> toolTotals,
      BigDecimal total,
      UsageRange range,
      DashboardCurrency currency,
      FxRate rate) {
    int count = buckets.size();
    // Daily ranges have at most 92 buckets; weekly at most 106; monthly at most 122.
    assert count > 0 && count <= 122;
    var bucketTotals = new ArrayList<BigDecimal>(count);
    for (Map<String, BigDecimal> tools : buckets.values()) {
      bucketTotals.add(tools.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add));
    }
    BigDecimal maximum =
        niceCeiling(bucketTotals.stream().max(Comparator.naturalOrder()).orElseThrow());
    double plotWidth = WIDTH - PAD_LEFT - PAD_RIGHT;
    double plotHeight = HEIGHT - PAD_TOP - PAD_BOTTOM;
    double columnWidth = plotWidth / count;

    var columns = new ArrayList<SvgRect>();
    var rows = new ArrayList<DataRow>(count);
    int index = 0;
    for (Map.Entry<LocalDate, Map<String, BigDecimal>> entry : buckets.entrySet()) {
      double x = PAD_LEFT + index * columnWidth;
      double base = HEIGHT - PAD_BOTTOM;
      String period = bucketPeriod(bucket, entry.getKey(), range);
      for (String tool : series.reversed()) {
        BigDecimal cost =
            tool.equals(OTHER_TOOLS)
                ? entry.getValue().entrySet().stream()
                    .filter(value -> !series.contains(value.getKey()))
                    .map(Map.Entry::getValue)
                    .reduce(BigDecimal.ZERO, BigDecimal::add)
                : entry.getValue().getOrDefault(tool, BigDecimal.ZERO);
        if (cost.signum() == 0) continue;
        double height = plotHeight * ratio(cost, maximum);
        base -= height;
        columns.add(
            new SvgRect(
                number(x + 0.5),
                number(base),
                number(Math.max(columnWidth - 1, 0.5)),
                number(height),
                seriesOf(series, tool),
                period + " · " + tool + " · " + DashboardFactory.money(cost, currency, rate)));
      }
      rows.add(
          new DataRow(period, DashboardFactory.money(bucketTotals.get(index), currency, rate)));
      index++;
    }

    var ticks = new ArrayList<SvgTick>(Y_TICKS + 1);
    for (int tick = 0; tick <= Y_TICKS; tick++) {
      BigDecimal value =
          maximum
              .multiply(BigDecimal.valueOf(tick))
              .divide(BigDecimal.valueOf(Y_TICKS), 8, RoundingMode.HALF_UP);
      double y = HEIGHT - PAD_BOTTOM - plotHeight * tick / Y_TICKS;
      ticks.add(
          new SvgTick(
              number(y),
              number(y + 4),
              number(PAD_LEFT),
              number(WIDTH - PAD_RIGHT),
              number(PAD_LEFT - 6),
              compactMoney(value, currency, rate)));
    }

    var labels = new ArrayList<SvgLabel>();
    int step = Math.max(1, (count + X_LABELS_MAX - 1) / X_LABELS_MAX);
    index = 0;
    for (LocalDate start : buckets.keySet()) {
      if (index % step == 0) {
        labels.add(
            new SvgLabel(
                number(PAD_LEFT + (index + 0.5) * columnWidth),
                number(HEIGHT - 6),
                bucket.label(start)));
      }
      index++;
    }

    var averages = new ArrayList<SvgPath>();
    // Only full seven-day windows inside the range; a line needs at least two of them.
    if (bucket == Bucket.DAY && count > AVERAGE_DAYS) {
      var path = new StringBuilder();
      for (int day = AVERAGE_DAYS - 1; day < count; day++) {
        BigDecimal sum = BigDecimal.ZERO;
        for (int back = 0; back < AVERAGE_DAYS; back++) {
          sum = sum.add(bucketTotals.get(day - back));
        }
        BigDecimal average = sum.divide(BigDecimal.valueOf(AVERAGE_DAYS), 8, RoundingMode.HALF_UP);
        path.append(day == AVERAGE_DAYS - 1 ? 'M' : 'L')
            .append(number(PAD_LEFT + (day + 0.5) * columnWidth))
            .append(' ')
            .append(number(HEIGHT - PAD_BOTTOM - plotHeight * ratio(average, maximum)));
      }
      averages.add(new SvgPath(path.toString(), "7-day average"));
    }

    var legend = new ArrayList<LegendItem>(series.size());
    for (String tool : series) {
      BigDecimal cost =
          tool.equals(OTHER_TOOLS)
              ? toolTotals.entrySet().stream()
                  .filter(entry -> !series.contains(entry.getKey()))
                  .map(Map.Entry::getValue)
                  .reduce(BigDecimal.ZERO, BigDecimal::add)
              : toolTotals.get(tool);
      legend.add(
          new LegendItem(
              seriesOf(series, tool), tool, DashboardFactory.money(cost, currency, rate)));
    }

    var stats = new ArrayList<Kpi>();
    int peak = 0;
    for (int candidate = 1; candidate < count; candidate++) {
      if (bucketTotals.get(candidate).compareTo(bucketTotals.get(peak)) > 0) peak = candidate;
    }
    if (bucketTotals.get(peak).signum() > 0) {
      stats.add(
          new Kpi(
              "Peak " + bucket.noun,
              DashboardFactory.money(bucketTotals.get(peak), currency, rate)
                  + " · "
                  + rows.get(peak).bucket()));
      List<BigDecimal> used =
          bucketTotals.stream().filter(value -> value.signum() > 0).sorted().toList();
      stats.add(
          new Kpi(
              "Typical " + bucket.noun + " with usage",
              DashboardFactory.money(median(used), currency, rate)));
    }
    stats.add(new Kpi("Total", DashboardFactory.money(total, currency, rate)));

    String description =
        "Estimated cost per "
            + bucket.noun
            + ", stacked by coding tool."
            + (averages.isEmpty() ? "" : " Line: 7-day average.")
            + " Empty columns are "
            + bucket.noun
            + "s without usage.";
    return new ColumnChart(
        number(WIDTH),
        number(HEIGHT),
        bucket.noun,
        description,
        columns,
        ticks,
        labels,
        averages,
        legend,
        stats,
        rows);
  }

  private static String bucketPeriod(Bucket bucket, LocalDate start, UsageRange range) {
    return switch (bucket) {
      case DAY -> FULL_DAY_LABEL.format(start);
      case WEEK -> {
        LocalDate first = start.isBefore(range.from()) ? range.from() : start;
        LocalDate last = start.plusDays(6).isAfter(range.to()) ? range.to() : start.plusDays(6);
        yield FULL_DAY_LABEL.format(first) + " – " + FULL_DAY_LABEL.format(last);
      }
      case MONTH -> MONTH_LABEL.format(start);
    };
  }

  private static double ratio(BigDecimal value, BigDecimal maximum) {
    return maximum.signum() == 0 ? 0 : value.divide(maximum, 8, RoundingMode.HALF_UP).doubleValue();
  }

  /// Smallest 1, 2, 2.5, or 5 times a power of ten that is at least `value`; at least 1.
  static BigDecimal niceCeiling(BigDecimal value) {
    if (value.compareTo(BigDecimal.ONE) <= 0) return BigDecimal.ONE;
    BigDecimal power = BigDecimal.ONE.scaleByPowerOfTen(value.precision() - value.scale() - 1);
    for (String factor : List.of("1", "2", "2.5", "5", "10")) {
      BigDecimal candidate = power.multiply(new BigDecimal(factor));
      if (candidate.compareTo(value) >= 0) return candidate;
    }
    throw new AssertionError("nice ceiling not found");
  }

  private static String compactMoney(BigDecimal usd, DashboardCurrency currency, FxRate rate) {
    BigDecimal value =
        currency == DashboardCurrency.EUR
            ? usd.multiply(Objects.requireNonNull(rate).eurPerUsd())
            : usd;
    if (value.compareTo(BigDecimal.valueOf(1000)) >= 0) {
      return currency.symbol()
          + value
              .divide(BigDecimal.valueOf(1000), 1, RoundingMode.HALF_UP)
              .stripTrailingZeros()
              .toPlainString()
          + "k";
    }
    return currency.symbol()
        + value
            .setScale(value.compareTo(BigDecimal.TEN) < 0 ? 2 : 0, RoundingMode.HALF_UP)
            .toPlainString();
  }

  private static String number(double value) {
    assert Double.isFinite(value);
    return BigDecimal.valueOf(value)
        .setScale(1, RoundingMode.HALF_UP)
        .stripTrailingZeros()
        .toPlainString();
  }
}
