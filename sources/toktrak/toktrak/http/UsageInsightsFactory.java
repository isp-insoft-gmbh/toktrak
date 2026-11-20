package toktrak.http;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import toktrak.http.UsageCharts.ColumnChart;
import toktrak.http.UsageCharts.DataRow;
import toktrak.http.UsageCharts.Kpi;
import toktrak.http.UsageCharts.LegendItem;
import toktrak.http.UsageCharts.SvgLabel;
import toktrak.http.UsageCharts.SvgRect;
import toktrak.http.UsageCharts.SvgTick;
import toktrak.http.UsageChartsFactory.Bucket;
import toktrak.http.UsageInsights.BarList;
import toktrak.http.UsageInsights.BarRow;
import toktrak.http.UsageInsights.CoverageLevel;
import toktrak.http.UsageInsights.HeatCell;
import toktrak.http.UsageInsights.Heatmap;
import toktrak.http.UsageInsights.Line;
import toktrak.http.UsageInsights.LineChart;
import toktrak.http.UsageInsights.Meter;
import toktrak.http.UsageInsights.PeopleTable;
import toktrak.http.UsageInsights.PersonRow;
import toktrak.http.UsageInsights.Segment;
import toktrak.http.UsageInsights.SessionInsights;
import toktrak.http.UsageInsights.StackedRow;
import toktrak.http.UsageInsights.StackedRows;
import toktrak.projection.Projection.FxRate;
import toktrak.projection.Projection.User;
import toktrak.projection.Projection.UserKey;
import toktrak.usage.UsageProjection.Row;
import toktrak.usage.UsageUpload;

/// Builds the range-scoped insight charts from canonical daily and session rows.
final class UsageInsightsFactory {
  private static final double WIDTH = 480;
  private static final double HEIGHT = 220;
  private static final double PAD_LEFT = 56;
  private static final double PAD_RIGHT = 10;
  private static final double PAD_TOP = 10;
  private static final double PAD_BOTTOM = 24;
  private static final int Y_TICKS = 4;
  private static final int X_LABELS_MAX = 5;
  private static final int SERIES_MAX = 6;
  private static final int MODEL_ENTRIES_PER_ROW_MAX = 64;
  private static final int MODEL_NAME_CHARACTERS_MAX = 256;
  private static final int PRICE_MODELS_MAX = 8;
  private static final int PEOPLE_MAX = 50;
  private static final int SPARK_WIDTH = 120;
  private static final int SPARK_HEIGHT = 24;
  private static final int MIX_WEEKLY_DAYS_MAX = 92;
  private static final int MIX_MONTHLY_DAYS_MAX = 731;
  private static final int PROJECTION_DAYS = 30;
  private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
  private static final BigDecimal MILLION = BigDecimal.valueOf(1_000_000);
  private static final BigDecimal COVERAGE_WARNING_PERCENT = BigDecimal.valueOf(98);
  private static final List<Integer> CONCENTRATION_PERCENTS =
      List.of(1, 2, 5, 10, 20, 30, 50, 75, 100);
  private static final List<String> PERSON_DAY_EDGES =
      List.of("0", "0.3", "1", "3", "10", "30", "100", "300", "1000");
  private static final List<String> SESSION_EDGES =
      List.of("0", "0.1", "0.3", "1", "3", "10", "30", "100", "300");
  private static final String OTHER_MODELS = "Other models";
  private static final DateTimeFormatter WEEK =
      DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH);
  private static final DateTimeFormatter MONTH =
      DateTimeFormatter.ofPattern("MMM uuuu", Locale.ENGLISH);

  private UsageInsightsFactory() {}

  /// One canonical daily row reduced to what the insight charts need.
  private record Day(
      UserKey owner,
      LocalDate date,
      BigDecimal cost,
      BigInteger input,
      BigInteger output,
      BigInteger cacheCreation,
      BigInteger cacheRead,
      BigInteger totalTokens,
      Map<String, BigDecimal> models,
      Map<String, BigInteger> modelTokens) {}

  static UsageInsights create(
      List<Row> dailyRows,
      List<Row> sessionRows,
      Map<UserKey, User> users,
      Map<UserKey, ZoneId> zones,
      UsageRange range,
      LocalDate today,
      DashboardCurrency currency,
      FxRate rate) {
    Objects.requireNonNull(dailyRows, "dailyRows");
    Objects.requireNonNull(sessionRows, "sessionRows");
    Objects.requireNonNull(users, "users");
    Objects.requireNonNull(zones, "zones");
    Objects.requireNonNull(range, "range");
    Objects.requireNonNull(today, "today");
    Objects.requireNonNull(currency, "currency");
    var days = new ArrayList<Day>();
    for (Row row : dailyRows) {
      LocalDate date = LocalDate.parse(row.firstKey());
      if (range.contains(date)) days.add(day(row, date));
    }
    BigDecimal total = days.stream().map(Day::cost).reduce(BigDecimal.ZERO, BigDecimal::add);
    long elapsed = UsageChartsFactory.elapsedDays(range, today);
    Bucket bucket = Bucket.of(range);
    var personDays = new HashMap<UserKey, Map<LocalDate, BigDecimal>>();
    for (Day day : days) {
      personDays
          .computeIfAbsent(day.owner(), owner -> new TreeMap<>())
          .merge(day.date(), day.cost(), BigDecimal::add);
    }
    List<BigDecimal> usedPersonDays =
        personDays.values().stream()
            .flatMap(values -> values.values().stream())
            .filter(cost -> cost.signum() > 0)
            .sorted()
            .toList();
    return new UsageInsights(
        cumulative(days, bucket, range, total, elapsed, currency, rate),
        activePeople(days, bucket, range, total, usedPersonDays.size(), currency, rate),
        sizes(usedPersonDays, PERSON_DAY_EDGES, "person-days", currency, rate),
        concentration(usedPersonDays, "person-days"),
        people(personDays, users, bucket, range, total, elapsed, currency, rate),
        modelMix(days, range, total, currency, rate),
        tokenPrice(days, total, currency, rate),
        cacheReuse(days, bucket, range),
        sessions(sessionRows, zones, range, total, currency, rate));
  }

  private static Day day(Row row, LocalDate date) {
    Map<String, Object> data = row.data();
    BigDecimal cost = UsageUpload.nonnegativeDecimal(data, "totalCost");
    var reported = new LinkedHashMap<String, BigDecimal>();
    var tokens = new HashMap<String, BigInteger>();
    if (data.get("modelBreakdowns") instanceof List<?> breakdowns) {
      int seen = 0;
      for (Object element : breakdowns) {
        seen = Math.addExact(seen, 1);
        if (seen > MODEL_ENTRIES_PER_ROW_MAX) break;
        if (!(element instanceof Map<?, ?> breakdown)
            || !(breakdown.get("modelName") instanceof String name)
            || name.isBlank()
            || name.length() > MODEL_NAME_CHARACTERS_MAX) {
          continue;
        }
        BigDecimal modelCost = decimal(breakdown.get("cost"));
        if (modelCost.signum() > 0) reported.merge(name, modelCost, BigDecimal::add);
        BigInteger modelTokens =
            whole(breakdown.get("inputTokens"))
                .add(whole(breakdown.get("outputTokens")))
                .add(whole(breakdown.get("cacheCreationTokens")))
                .add(whole(breakdown.get("cacheReadTokens")));
        tokens.merge(name, modelTokens, BigInteger::add);
      }
    }
    return new Day(
        row.owner(),
        date,
        cost,
        whole(data.get("inputTokens")),
        whole(data.get("outputTokens")),
        whole(data.get("cacheCreationTokens")),
        whole(data.get("cacheReadTokens")),
        whole(data.get("totalTokens")),
        UsageChartsFactory.attribute(reported, cost, null),
        tokens);
  }

  private static LineChart cumulative(
      List<Day> days,
      Bucket bucket,
      UsageRange range,
      BigDecimal total,
      long elapsed,
      DashboardCurrency currency,
      FxRate rate) {
    java.util.SortedMap<LocalDate, BigDecimal> perBucket = bucketCosts(days, bucket, range);
    BigDecimal maximum = UsageChartsFactory.niceCeiling(total);
    int count = perBucket.size();
    var path = new StringBuilder();
    BigDecimal running = BigDecimal.ZERO;
    int index = 0;
    path.append('M').append(x(0, count)).append(' ').append(y(BigDecimal.ZERO, maximum));
    for (BigDecimal cost : perBucket.values()) {
      running = running.add(cost);
      index++;
      path.append('L').append(x(index, count)).append(' ').append(y(running, maximum));
    }
    assert running.compareTo(total) == 0;
    long rangeDays = range.days();
    double paceEnd = elapsed == 0 ? 0 : (double) count * Math.min(elapsed, rangeDays) / rangeDays;
    String reference =
        "M"
            + x(0, count)
            + " "
            + y(BigDecimal.ZERO, maximum)
            + "L"
            + x(paceEnd, count)
            + " "
            + y(total, maximum);
    BigDecimal pace =
        elapsed == 0
            ? BigDecimal.ZERO
            : total.divide(BigDecimal.valueOf(elapsed), 8, RoundingMode.HALF_UP);
    return new LineChart(
        number(WIDTH),
        number(HEIGHT),
        moneyTicks(maximum, currency, rate),
        xLabels(new ArrayList<>(perBucket.keySet()), bucket, true),
        List.of(
            new Line(path.toString(), "Cumulative cost", "chart-line series-1"),
            new Line(reference, "Even pace to the same total", "chart-reference")),
        List.of(
            UsageChartsFactory.money("Total", total, currency, rate),
            UsageChartsFactory.money("Pace per calendar day", pace, currency, rate),
            UsageChartsFactory.money(
                PROJECTION_DAYS + "-day projection",
                pace.multiply(BigDecimal.valueOf(PROJECTION_DAYS)),
                currency,
                rate)));
  }

  private static ColumnChart activePeople(
      List<Day> days,
      Bucket bucket,
      UsageRange range,
      BigDecimal total,
      int personDaysWithUsage,
      DashboardCurrency currency,
      FxRate rate) {
    var people = new TreeMap<LocalDate, Set<UserKey>>();
    for (LocalDate start = bucket.start(range.from());
        !start.isAfter(range.to());
        start = bucket.next(start)) {
      people.put(start, new HashSet<>());
    }
    for (Day day : days) {
      if (day.cost().signum() > 0) people.get(bucket.start(day.date())).add(day.owner());
    }
    int maximum = people.values().stream().mapToInt(Set::size).max().orElse(0);
    int scale = Math.max(1, maximum);
    int count = people.size();
    double plotWidth = WIDTH - PAD_LEFT - PAD_RIGHT;
    double plotHeight = HEIGHT - PAD_TOP - PAD_BOTTOM;
    double columnWidth = plotWidth / count;
    var columns = new ArrayList<SvgRect>();
    var rows = new ArrayList<DataRow>();
    int index = 0;
    for (Map.Entry<LocalDate, Set<UserKey>> entry : people.entrySet()) {
      int value = entry.getValue().size();
      String period = UsageChartsFactory.bucketPeriod(bucket, entry.getKey(), range);
      if (value > 0) {
        double height = plotHeight * value / scale;
        columns.add(
            new SvgRect(
                number(PAD_LEFT + index * columnWidth + 0.5),
                number(HEIGHT - PAD_BOTTOM - height),
                number(Math.max(columnWidth - 1, 0.5)),
                number(height),
                "series-2",
                period + " · " + value + (value == 1 ? " person" : " people"),
                ""));
      }
      rows.add(new DataRow(period, Integer.toString(value), ""));
      index++;
    }
    var ticks = new ArrayList<SvgTick>();
    for (int tick = 0; tick <= Math.min(Y_TICKS, scale); tick++) {
      int value = (int) Math.round((double) scale * tick / Math.min(Y_TICKS, scale));
      double y = HEIGHT - PAD_BOTTOM - plotHeight * value / scale;
      ticks.add(tick(y, Integer.toString(value)));
    }
    List<Integer> used =
        people.values().stream().map(Set::size).filter(value -> value > 0).sorted().toList();
    BigDecimal perPersonDay =
        personDaysWithUsage == 0
            ? BigDecimal.ZERO
            : total.divide(BigDecimal.valueOf(personDaysWithUsage), 8, RoundingMode.HALF_UP);
    return new ColumnChart(
        number(WIDTH),
        number(HEIGHT),
        bucket.noun(),
        "People with any reported usage per " + bucket.noun() + ".",
        columns,
        ticks,
        xLabels(new ArrayList<>(people.keySet()), bucket, false),
        List.of(),
        List.of(),
        List.of(
            new Kpi("Most in one " + bucket.noun(), Integer.toString(maximum), ""),
            new Kpi(
                "Typical " + bucket.noun() + " with usage",
                used.isEmpty() ? "0" : Integer.toString(used.get(used.size() / 2)),
                ""),
            UsageChartsFactory.money("Cost per active person-day", perPersonDay, currency, rate)),
        rows);
  }

  /// Log-scale size bins: how often a size occurs and how much of the cost it adds.
  private static BarList sizes(
      List<BigDecimal> sortedCosts,
      List<String> edges,
      String unit,
      DashboardCurrency currency,
      FxRate rate) {
    int bins = edges.size();
    long[] counts = new long[bins];
    BigDecimal[] costs = new BigDecimal[bins];
    java.util.Arrays.fill(costs, BigDecimal.ZERO);
    for (BigDecimal cost : sortedCosts) {
      int bin = bins - 1;
      while (bin > 0 && cost.compareTo(new BigDecimal(edges.get(bin))) < 0) bin--;
      counts[bin]++;
      costs[bin] = costs[bin].add(cost);
    }
    BigDecimal total = sortedCosts.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    long count = sortedCosts.size();
    long countMax = java.util.Arrays.stream(counts).max().orElse(0);
    BigDecimal costMax =
        java.util.Arrays.stream(costs).max(Comparator.naturalOrder()).orElse(BigDecimal.ZERO);
    var rows = new ArrayList<BarRow>(bins);
    for (int bin = 0; bin < bins; bin++) {
      String label =
          bin == 0
              ? "under $" + edges.get(1)
              : bin == bins - 1
                  ? "$" + edges.get(bin) + "+"
                  : "$" + edges.get(bin) + "–" + edges.get(bin + 1);
      rows.add(
          new BarRow(
              label,
              meter(BigDecimal.valueOf(counts[bin]), BigDecimal.valueOf(countMax)),
              percent(BigDecimal.valueOf(counts[bin]), BigDecimal.valueOf(count)),
              List.of(new Meter(meter(costs[bin], costMax), percent(costs[bin], total))),
              UsageChartsFactory.exact(costs[bin])));
    }
    var stats = new ArrayList<Kpi>();
    stats.add(new Kpi(capitalized(unit), Long.toString(count), ""));
    if (count > 0) {
      stats.add(
          UsageChartsFactory.money(
              "Median", UsageChartsFactory.median(sortedCosts), currency, rate));
      stats.add(
          UsageChartsFactory.money(
              "Mean",
              total.divide(BigDecimal.valueOf(count), 8, RoundingMode.HALF_UP),
              currency,
              rate));
      stats.add(
          UsageChartsFactory.money("90th percentile", quantile(sortedCosts, 90), currency, rate));
      stats.add(UsageChartsFactory.money("Largest", sortedCosts.getLast(), currency, rate));
    }
    stats.add(UsageChartsFactory.money("Total", total, currency, rate));
    return new BarList(List.of("Range (USD)", "Share of " + unit, "Share of cost"), rows, stats);
  }

  /// Share of cost caused by the costliest x% of items.
  private static LineChart concentration(List<BigDecimal> sortedCosts, String unit) {
    List<BigDecimal> descending = sortedCosts.reversed();
    BigDecimal total = descending.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    var path = new StringBuilder("M" + percentX(0) + " " + percentY(BigDecimal.ZERO));
    var stats = new ArrayList<Kpi>();
    for (int percent : CONCENTRATION_PERCENTS) {
      int items = (int) Math.ceil(descending.size() * percent / 100.0);
      BigDecimal share =
          total.signum() == 0
              ? BigDecimal.ZERO
              : descending.subList(0, items).stream()
                  .reduce(BigDecimal.ZERO, BigDecimal::add)
                  .multiply(HUNDRED)
                  .divide(total, 8, RoundingMode.HALF_UP);
      path.append('L').append(percentX(percent)).append(' ').append(percentY(share));
      if (percent == 1 || percent == 10 || percent == 50) {
        stats.add(
            new Kpi(
                "Top " + percent + "% of " + unit,
                share.setScale(1, RoundingMode.HALF_UP).toPlainString() + "% of cost",
                ""));
      }
    }
    var ticks = new ArrayList<SvgTick>();
    var labels = new ArrayList<SvgLabel>();
    for (int value = 0; value <= 100; value += 25) {
      ticks.add(tick(Double.parseDouble(percentY(BigDecimal.valueOf(value))), value + "%"));
      labels.add(new SvgLabel(percentX(value), number(HEIGHT - 6), value + "%"));
    }
    return new LineChart(
        number(WIDTH),
        number(HEIGHT),
        ticks,
        labels,
        List.of(
            new Line(path.toString(), "Costliest " + unit + " first", "chart-line series-1"),
            new Line(
                "M"
                    + percentX(0)
                    + " "
                    + percentY(BigDecimal.ZERO)
                    + "L"
                    + percentX(100)
                    + " "
                    + percentY(HUNDRED),
                "Perfectly even",
                "chart-reference")),
        stats);
  }

  private static PeopleTable people(
      Map<UserKey, Map<LocalDate, BigDecimal>> personDays,
      Map<UserKey, User> users,
      Bucket bucket,
      UsageRange range,
      BigDecimal total,
      long elapsed,
      DashboardCurrency currency,
      FxRate rate) {
    var buckets = new ArrayList<LocalDate>();
    for (LocalDate start = bucket.start(range.from());
        !start.isAfter(range.to());
        start = bucket.next(start)) {
      buckets.add(start);
    }
    var perPerson = new HashMap<UserKey, BigDecimal[]>();
    for (Map.Entry<UserKey, Map<LocalDate, BigDecimal>> person : personDays.entrySet()) {
      BigDecimal[] values = new BigDecimal[buckets.size()];
      java.util.Arrays.fill(values, BigDecimal.ZERO);
      for (Map.Entry<LocalDate, BigDecimal> day : person.getValue().entrySet()) {
        int index = buckets.indexOf(bucket.start(day.getKey()));
        values[index] = values[index].add(day.getValue());
      }
      perPerson.put(person.getKey(), values);
    }
    var everyone =
        personDays.entrySet().stream()
            .map(entry -> Map.entry(entry.getKey(), sum(entry.getValue().values())))
            .sorted(
                Map.Entry.<UserKey, BigDecimal>comparingByValue()
                    .reversed()
                    .thenComparing(entry -> entry.getKey().toString()))
            .toList();
    var ordered = everyone.subList(0, Math.min(everyone.size(), PEOPLE_MAX));
    var rows = new ArrayList<PersonRow>();
    double barWidth = (double) SPARK_WIDTH / buckets.size();
    for (Map.Entry<UserKey, BigDecimal> person : ordered) {
      BigDecimal[] values = perPerson.get(person.getKey());
      // Each person's trend uses their own scale; totals carry the comparison.
      BigDecimal bucketMax =
          java.util.Arrays.stream(values).max(Comparator.naturalOrder()).orElse(BigDecimal.ZERO);
      var spark = new ArrayList<SvgRect>();
      for (int index = 0; index < values.length; index++) {
        if (values[index].signum() == 0) continue;
        double height =
            Math.max(1, SPARK_HEIGHT * UsageChartsFactory.ratio(values[index], bucketMax));
        spark.add(
            new SvgRect(
                number(index * barWidth),
                number(SPARK_HEIGHT - height),
                number(Math.max(barWidth - 0.5, 0.3)),
                number(height),
                "series-1",
                UsageChartsFactory.bucketPeriod(bucket, buckets.get(index), range)
                    + " · "
                    + DashboardFactory.money(values[index], currency, rate),
                ""));
      }
      List<BigDecimal> active =
          personDays.get(person.getKey()).values().stream()
              .filter(cost -> cost.signum() > 0)
              .sorted()
              .toList();
      User user = users.get(person.getKey());
      rows.add(
          new PersonRow(
              user == null ? "Unknown person" : user.displayName(),
              spark,
              DashboardFactory.money(person.getValue(), currency, rate),
              UsageChartsFactory.exact(person.getValue()),
              percent(person.getValue(), total),
              Integer.toString(active.size()),
              DashboardFactory.money(
                  active.isEmpty() ? BigDecimal.ZERO : UsageChartsFactory.median(active),
                  currency,
                  rate),
              DashboardFactory.money(
                  elapsed == 0
                      ? BigDecimal.ZERO
                      : person
                          .getValue()
                          .divide(BigDecimal.valueOf(elapsed), 8, RoundingMode.HALF_UP),
                  currency,
                  rate)));
    }
    var rest = everyone.subList(ordered.size(), everyone.size());
    if (!rest.isEmpty()) {
      // Remaining people share one row so the rows still add up to the range total.
      BigDecimal restTotal = sum(rest.stream().map(Map.Entry::getValue).toList());
      List<BigDecimal> restDays =
          rest.stream()
              .flatMap(person -> personDays.get(person.getKey()).values().stream())
              .filter(cost -> cost.signum() > 0)
              .sorted()
              .toList();
      rows.add(
          new PersonRow(
              rest.size() + " more people",
              List.of(),
              DashboardFactory.money(restTotal, currency, rate),
              UsageChartsFactory.exact(restTotal),
              percent(restTotal, total),
              restDays.size() + " person-days",
              DashboardFactory.money(
                  restDays.isEmpty() ? BigDecimal.ZERO : UsageChartsFactory.median(restDays),
                  currency,
                  rate),
              DashboardFactory.money(
                  elapsed == 0
                      ? BigDecimal.ZERO
                      : restTotal.divide(BigDecimal.valueOf(elapsed), 8, RoundingMode.HALF_UP),
                  currency,
                  rate)));
    }
    List<BigDecimal> totals = everyone.stream().map(Map.Entry::getValue).sorted().toList();
    assert sum(totals).compareTo(total) == 0;
    return new PeopleTable(
        rows,
        List.of(
            new Kpi("People", Integer.toString(personDays.size()), ""),
            UsageChartsFactory.money(
                "Median person",
                totals.isEmpty() ? BigDecimal.ZERO : UsageChartsFactory.median(totals),
                currency,
                rate),
            UsageChartsFactory.money("Total", total, currency, rate)));
  }

  private static StackedRows modelMix(
      List<Day> days, UsageRange range, BigDecimal total, DashboardCurrency currency, FxRate rate) {
    String periodName =
        range.days() <= MIX_WEEKLY_DAYS_MAX
            ? "week"
            : range.days() <= MIX_MONTHLY_DAYS_MAX ? "month" : "year";
    var totals = new HashMap<String, BigDecimal>();
    var periods = new TreeMap<LocalDate, Map<String, BigDecimal>>();
    for (Day day : days) {
      LocalDate period =
          switch (periodName) {
            case "week" -> Bucket.WEEK.start(day.date());
            case "month" -> day.date().withDayOfMonth(1);
            default -> day.date().withDayOfYear(1);
          };
      Map<String, BigDecimal> mix = periods.computeIfAbsent(period, key -> new HashMap<>());
      for (Map.Entry<String, BigDecimal> model : day.models().entrySet()) {
        mix.merge(model.getKey(), model.getValue(), BigDecimal::add);
        totals.merge(model.getKey(), model.getValue(), BigDecimal::add);
      }
    }
    List<String> ordered =
        totals.entrySet().stream()
            .sorted(
                Map.Entry.<String, BigDecimal>comparingByValue()
                    .reversed()
                    .thenComparing(Map.Entry.comparingByKey()))
            .map(Map.Entry::getKey)
            .toList();
    // Grouping is positional, so a reported model literally named like the group stays separate.
    List<String> shown =
        ordered.size() <= SERIES_MAX ? ordered : ordered.subList(0, SERIES_MAX - 1);
    var shownSet = new java.util.HashSet<String>(shown);
    int seriesCount = ordered.size() <= SERIES_MAX ? shown.size() : SERIES_MAX;
    var rows = new ArrayList<StackedRow>();
    for (Map.Entry<LocalDate, Map<String, BigDecimal>> period : periods.entrySet()) {
      BigDecimal periodTotal = sum(period.getValue().values());
      if (periodTotal.signum() == 0) continue;
      var segments = new ArrayList<Segment>();
      BigDecimal offset = BigDecimal.ZERO;
      for (int index = 0; index < seriesCount; index++) {
        String name = index < shown.size() ? shown.get(index) : OTHER_MODELS;
        BigDecimal cost =
            index < shown.size()
                ? period.getValue().getOrDefault(name, BigDecimal.ZERO)
                : remainder(period.getValue(), shownSet);
        if (cost.signum() == 0) continue;
        BigDecimal width = cost.multiply(HUNDRED).divide(periodTotal, 4, RoundingMode.HALF_UP);
        segments.add(
            new Segment(
                offset.stripTrailingZeros().toPlainString(),
                width.stripTrailingZeros().toPlainString(),
                "series-" + (index + 1),
                periodLabel(periodName, period.getKey())
                    + " · "
                    + name
                    + " · "
                    + DashboardFactory.money(cost, currency, rate)
                    + " ("
                    + percent(cost, periodTotal)
                    + ")",
                UsageChartsFactory.exact(cost)));
        offset = offset.add(width);
      }
      rows.add(
          new StackedRow(
              periodLabel(periodName, period.getKey()),
              UsageChartsFactory.exact(periodTotal),
              segments));
    }
    var legend = new ArrayList<LegendItem>();
    for (int index = 0; index < seriesCount; index++) {
      String name = index < shown.size() ? shown.get(index) : OTHER_MODELS;
      BigDecimal cost = index < shown.size() ? totals.get(name) : remainder(totals, shownSet);
      legend.add(
          new LegendItem(
              "series-" + (index + 1),
              name,
              DashboardFactory.money(cost, currency, rate),
              UsageChartsFactory.exact(cost)));
    }
    assert sum(legend.stream().map(item -> new BigDecimal(item.usd())).toList()).compareTo(total)
        == 0;
    assert sum(totals.values()).compareTo(total) == 0;
    return new StackedRows(
        periodName,
        rows,
        legend,
        List.of(
            new Kpi("Models", Integer.toString(ordered.size()), ""),
            UsageChartsFactory.money("Total", total, currency, rate)));
  }

  private static BarList tokenPrice(
      List<Day> days, BigDecimal total, DashboardCurrency currency, FxRate rate) {
    var costs = new HashMap<String, BigDecimal>();
    var tokens = new HashMap<String, BigInteger>();
    BigInteger allTokens = BigInteger.ZERO;
    for (Day day : days) {
      allTokens = allTokens.add(day.totalTokens());
      for (Map.Entry<String, BigDecimal> model : day.models().entrySet()) {
        if (model.getKey().equals(UsageChartsFactory.NOT_REPORTED)) continue;
        costs.merge(model.getKey(), model.getValue(), BigDecimal::add);
      }
      day.modelTokens().forEach((name, value) -> tokens.merge(name, value, BigInteger::add));
    }
    var prices = new LinkedHashMap<String, BigDecimal>();
    costs.entrySet().stream()
        .filter(entry -> tokens.getOrDefault(entry.getKey(), BigInteger.ZERO).signum() > 0)
        .sorted(
            Map.Entry.<String, BigDecimal>comparingByValue()
                .reversed()
                .thenComparing(Map.Entry.comparingByKey()))
        .limit(PRICE_MODELS_MAX)
        .forEach(entry ->
            prices.put(
                entry.getKey(),
                entry
                    .getValue()
                    .multiply(MILLION)
                    .divide(new BigDecimal(tokens.get(entry.getKey())), 8, RoundingMode.HALF_UP)));
    BigDecimal maximum =
        prices.values().stream().max(Comparator.naturalOrder()).orElse(BigDecimal.ZERO);
    var rows = new ArrayList<BarRow>();
    for (Map.Entry<String, BigDecimal> price : prices.entrySet()) {
      rows.add(
          new BarRow(
              price.getKey(),
              meter(price.getValue(), maximum),
              DashboardFactory.money(price.getValue(), currency, rate),
              List.of(),
              UsageChartsFactory.exact(price.getValue())));
    }
    BigDecimal average =
        allTokens.signum() == 0
            ? BigDecimal.ZERO
            : total.multiply(MILLION).divide(new BigDecimal(allTokens), 8, RoundingMode.HALF_UP);
    return new BarList(
        List.of("Model", "Cost per 1M tokens"),
        rows,
        List.of(UsageChartsFactory.money("Team average", average, currency, rate)));
  }

  private static LineChart cacheReuse(List<Day> days, Bucket bucket, UsageRange range) {
    var read = new TreeMap<LocalDate, BigInteger>();
    var input = new TreeMap<LocalDate, BigInteger>();
    for (LocalDate start = bucket.start(range.from());
        !start.isAfter(range.to());
        start = bucket.next(start)) {
      read.put(start, BigInteger.ZERO);
      input.put(start, BigInteger.ZERO);
    }
    BigInteger allRead = BigInteger.ZERO;
    BigInteger allInput = BigInteger.ZERO;
    for (Day day : days) {
      LocalDate start = bucket.start(day.date());
      BigInteger side = day.input().add(day.cacheCreation()).add(day.cacheRead());
      read.merge(start, day.cacheRead(), BigInteger::add);
      input.merge(start, side, BigInteger::add);
      allRead = allRead.add(day.cacheRead());
      allInput = allInput.add(side);
    }
    int count = read.size();
    var path = new StringBuilder();
    int index = 0;
    boolean open = false;
    for (LocalDate start : read.keySet()) {
      BigInteger side = input.get(start);
      if (side.signum() == 0) {
        open = false;
      } else {
        BigDecimal share =
            new BigDecimal(read.get(start))
                .multiply(HUNDRED)
                .divide(new BigDecimal(side), 8, RoundingMode.HALF_UP);
        path.append(open ? 'L' : 'M')
            .append(x(index + 0.5, count))
            .append(' ')
            .append(percentY(share));
        open = true;
      }
      index++;
    }
    var ticks = new ArrayList<SvgTick>();
    for (int value = 0; value <= 100; value += 25) {
      ticks.add(tick(Double.parseDouble(percentY(BigDecimal.valueOf(value))), value + "%"));
    }
    String overall =
        allInput.signum() == 0
            ? "–"
            : new BigDecimal(allRead)
                    .multiply(HUNDRED)
                    .divide(new BigDecimal(allInput), 1, RoundingMode.HALF_UP)
                    .toPlainString()
                + "%";
    return new LineChart(
        number(WIDTH),
        number(HEIGHT),
        ticks,
        xLabels(new ArrayList<>(read.keySet()), bucket, false),
        path.isEmpty()
            ? List.of()
            : List.of(new Line(path.toString(), "Read from cache", "chart-line series-4")),
        List.of(new Kpi("Across the range", overall, "")));
  }

  private static SessionInsights sessions(
      List<Row> sessionRows,
      Map<UserKey, ZoneId> zones,
      UsageRange range,
      BigDecimal dailyTotal,
      DashboardCurrency currency,
      FxRate rate) {
    var costs = new ArrayList<BigDecimal>();
    long[][] heat = new long[7][24];
    BigDecimal[][] heatCost = new BigDecimal[7][24];
    for (BigDecimal[] row : heatCost) java.util.Arrays.fill(row, BigDecimal.ZERO);
    for (Row row : sessionRows) {
      Instant last = lastActivity(row);
      if (last == null) continue;
      ZonedDateTime local = last.atZone(zones.getOrDefault(row.owner(), ZoneOffset.UTC));
      if (!range.contains(local.toLocalDate())) continue;
      BigDecimal cost = UsageUpload.nonnegativeDecimal(row.data(), "totalCost");
      if (cost.signum() == 0) continue;
      costs.add(cost);
      int weekday = local.getDayOfWeek().getValue() - 1;
      heat[weekday][local.getHour()]++;
      heatCost[weekday][local.getHour()] = heatCost[weekday][local.getHour()].add(cost);
    }
    costs.sort(Comparator.naturalOrder());
    BigDecimal total = sum(costs);
    String coverage;
    CoverageLevel level;
    if (dailyTotal.signum() == 0) {
      coverage = "No daily usage in this range to compare sessions with.";
      level = total.signum() == 0 ? CoverageLevel.CONSISTENT : CoverageLevel.WARNING;
    } else {
      BigDecimal share = total.multiply(HUNDRED).divide(dailyTotal, 1, RoundingMode.HALF_UP);
      coverage =
          "Sessions add up to "
              + DashboardFactory.money(total, currency, rate)
              + " of "
              + DashboardFactory.money(dailyTotal, currency, rate)
              + " daily cost ("
              + share.toPlainString()
              + "%). Sessions count on the day of their last activity, so ranges can differ"
              + " slightly; large gaps mean incomplete or inconsistent session reports.";
      BigDecimal distance = share.subtract(HUNDRED).abs();
      level =
          distance.compareTo(HUNDRED.subtract(COVERAGE_WARNING_PERCENT)) > 0
              ? CoverageLevel.WARNING
              : CoverageLevel.CONSISTENT;
    }
    return new SessionInsights(
        coverage,
        level,
        List.of(
            UsageChartsFactory.money("Session total", total, currency, rate),
            new Kpi("Sessions", Integer.toString(costs.size()), "")),
        sizes(costs, SESSION_EDGES, "sessions", currency, rate),
        concentration(costs, "sessions"),
        heatmap(heat, heatCost, currency, rate));
  }

  private static Heatmap heatmap(
      long[][] counts, BigDecimal[][] costs, DashboardCurrency currency, FxRate rate) {
    BigDecimal maximum = BigDecimal.ZERO;
    for (BigDecimal[] row : costs) for (BigDecimal value : row) maximum = maximum.max(value);
    double width = 960;
    double left = 40;
    double cell = (width - left) / 24;
    double rowHeight = 20;
    var cells = new ArrayList<HeatCell>();
    var rowLabels = new ArrayList<SvgLabel>();
    BigDecimal busiest = BigDecimal.ZERO;
    String busiestLabel = "–";
    for (int day = 0; day < 7; day++) {
      String dayName = DayOfWeek.of(day + 1).getDisplayName(TextStyle.SHORT, Locale.ENGLISH);
      rowLabels.add(new SvgLabel(number(0), number(day * rowHeight + 14), dayName));
      for (int hour = 0; hour < 24; hour++) {
        BigDecimal value = costs[day][hour];
        double ratio = UsageChartsFactory.ratio(value, maximum);
        cells.add(
            new HeatCell(
                number(left + hour * cell),
                number(day * rowHeight),
                number(cell - 1.5),
                number(rowHeight - 1.5),
                value.signum() == 0 ? "0.04" : number2(0.12 + 0.88 * Math.sqrt(ratio)),
                dayName
                    + " "
                    + String.format(Locale.ROOT, "%02d:00", hour)
                    + " · "
                    + DashboardFactory.money(value, currency, rate)
                    + " · "
                    + counts[day][hour]
                    + " sessions"));
        if (value.compareTo(busiest) > 0) {
          busiest = value;
          busiestLabel = dayName + " " + String.format(Locale.ROOT, "%02d:00", hour);
        }
      }
    }
    var columnLabels = new ArrayList<SvgLabel>();
    for (int hour = 0; hour < 24; hour += 3) {
      columnLabels.add(
          new SvgLabel(
              number(left + hour * cell + cell / 2),
              number(7 * rowHeight + 14),
              String.format(Locale.ROOT, "%02d", hour)));
    }
    return new Heatmap(
        number(width),
        number(7 * rowHeight + 20),
        cells,
        rowLabels,
        columnLabels,
        List.of(
            new Kpi("Busiest hour", busiestLabel, ""),
            UsageChartsFactory.money("Its cost", busiest, currency, rate)));
  }

  private static Instant lastActivity(Row row) {
    if (!(row.data().get("metadata") instanceof Map<?, ?> metadata)
        || !(metadata.get("lastActivity") instanceof String text)
        || text.length() > 64) {
      return null;
    }
    try {
      return Instant.parse(text);
    } catch (DateTimeParseException exception) {
      return null;
    }
  }

  static ZoneId zone(String clientTimeZone) {
    try {
      return ZoneId.of(clientTimeZone);
    } catch (DateTimeException exception) {
      return ZoneOffset.UTC;
    }
  }

  private static java.util.SortedMap<LocalDate, BigDecimal> bucketCosts(
      List<Day> days, Bucket bucket, UsageRange range) {
    var result = new TreeMap<LocalDate, BigDecimal>();
    for (LocalDate start = bucket.start(range.from());
        !start.isAfter(range.to());
        start = bucket.next(start)) {
      result.put(start, BigDecimal.ZERO);
    }
    for (Day day : days) result.merge(bucket.start(day.date()), day.cost(), BigDecimal::add);
    return result;
  }

  private static List<SvgTick> moneyTicks(
      BigDecimal maximum, DashboardCurrency currency, FxRate rate) {
    var ticks = new ArrayList<SvgTick>();
    double plotHeight = HEIGHT - PAD_TOP - PAD_BOTTOM;
    for (int tick = 0; tick <= Y_TICKS; tick++) {
      BigDecimal value =
          maximum
              .multiply(BigDecimal.valueOf(tick))
              .divide(BigDecimal.valueOf(Y_TICKS), 8, RoundingMode.HALF_UP);
      ticks.add(
          tick(
              HEIGHT - PAD_BOTTOM - plotHeight * tick / Y_TICKS,
              UsageChartsFactory.compactMoney(value, currency, rate)));
    }
    return ticks;
  }

  private static SvgTick tick(double y, String label) {
    return new SvgTick(
        number(y),
        number(y + 4),
        number(PAD_LEFT),
        number(WIDTH - PAD_RIGHT),
        number(PAD_LEFT - 6),
        label);
  }

  /// Labels at bucket centres, or at bucket boundaries for cumulative charts.
  private static List<SvgLabel> xLabels(List<LocalDate> starts, Bucket bucket, boolean boundaries) {
    var labels = new ArrayList<SvgLabel>();
    int count = starts.size();
    int step = Math.max(1, (count + X_LABELS_MAX - 1) / X_LABELS_MAX);
    for (int index = 0; index < count; index += step) {
      labels.add(
          new SvgLabel(
              x(boundaries ? index : index + 0.5, count),
              number(HEIGHT - 6),
              bucket.label(starts.get(index))));
    }
    return labels;
  }

  private static String x(double position, int count) {
    return number(PAD_LEFT + (WIDTH - PAD_LEFT - PAD_RIGHT) * position / Math.max(count, 1));
  }

  private static String y(BigDecimal value, BigDecimal maximum) {
    return number(
        HEIGHT
            - PAD_BOTTOM
            - (HEIGHT - PAD_TOP - PAD_BOTTOM) * UsageChartsFactory.ratio(value, maximum));
  }

  private static String percentX(int percent) {
    return number(PAD_LEFT + (WIDTH - PAD_LEFT - PAD_RIGHT) * percent / 100.0);
  }

  private static String percentY(BigDecimal percent) {
    return y(percent, HUNDRED);
  }

  private static String meter(BigDecimal value, BigDecimal maximum) {
    if (value.signum() == 0 || maximum.signum() == 0) return "0";
    return value
        .multiply(HUNDRED)
        .divide(maximum, 1, RoundingMode.HALF_UP)
        .max(BigDecimal.ONE)
        .stripTrailingZeros()
        .toPlainString();
  }

  private static String percent(BigDecimal value, BigDecimal total) {
    if (total.signum() == 0) return "0%";
    return value.multiply(HUNDRED).divide(total, 1, RoundingMode.HALF_UP).toPlainString() + "%";
  }

  /// Nearest-rank percentile of an ascending list.
  private static BigDecimal quantile(List<BigDecimal> sorted, int percentile) {
    int rank = (int) Math.ceil(sorted.size() * percentile / 100.0);
    return sorted.get(Math.max(rank, 1) - 1);
  }

  private static String periodLabel(String periodName, LocalDate start) {
    return switch (periodName) {
      case "week" -> "Week of " + WEEK.format(start);
      case "month" -> MONTH.format(start);
      default -> Integer.toString(start.getYear());
    };
  }

  private static String capitalized(String value) {
    return value.substring(0, 1).toUpperCase(Locale.ROOT) + value.substring(1);
  }

  private static BigDecimal remainder(Map<String, BigDecimal> costs, java.util.Set<String> shown) {
    return costs.entrySet().stream()
        .filter(entry -> !shown.contains(entry.getKey()))
        .map(Map.Entry::getValue)
        .reduce(BigDecimal.ZERO, BigDecimal::add);
  }

  private static BigDecimal sum(java.util.Collection<BigDecimal> values) {
    return values.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
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

  private static BigInteger whole(Object value) {
    if (!(value instanceof Number number)) return BigInteger.ZERO;
    try {
      BigInteger result = new BigDecimal(number.toString()).toBigIntegerExact();
      return result.signum() < 0 ? BigInteger.ZERO : result;
    } catch (ArithmeticException | NumberFormatException exception) {
      return BigInteger.ZERO;
    }
  }

  private static String number(double value) {
    return UsageChartsFactory.number(value);
  }

  private static String number2(double value) {
    return BigDecimal.valueOf(value).round(new MathContext(2)).stripTrailingZeros().toPlainString();
  }
}
