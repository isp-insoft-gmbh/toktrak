package toktrak.tests;

import static org.junit.jupiter.api.Assertions.*;
import static toktrak.tests.UsageFixture.daily;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/// Every figure on the visualizations page must reconcile with every other figure, for any range.
/// The checks compare exact canonical USD values (`data-usd`) and the rounding of each display.
final class VisualizationsConsistencyTest {
  private static final Pattern KPI =
      Pattern.compile("<dt>([^<]+)</dt><dd data-usd=\"([^\"]*)\">([^<]+)</dd>");
  private static final Pattern LEGEND =
      Pattern.compile(
          "<li data-usd=\"([^\"]+)\"><span class=\"swatch series-\\d\" aria-hidden=\"true\"></span>"
              + "[^<]+ <strong>([^<]+)</strong>");
  private static final Pattern ROW =
      Pattern.compile("<tr data-usd=\"([^\"]+)\"><td>[^<]+</td><td>([^<]+)</td>");
  private static final Pattern RECT =
      Pattern.compile(
          "<rect class=\"series-\\d\"[^>]* data-usd=\"([^\"]+)\"><title>[^<]+ · ([^<]+)</title>");
  private static final Pattern DAYS = Pattern.compile("(\\d+) of (\\d+)");
  private static final int RANDOM_RANGES = 120;
  private static final int RANDOM_SPLITS = 80;
  private static final int RANDOM_ROWS = 160;

  @TempDir java.nio.file.Path directory;

  @Test
  void given_developmentCorpus_when_openingManyRanges_then_everyFigureReconciles()
      throws Exception {
    try (var usage =
        new UsageFixture(
            directory.resolve("corpus"), List.of("--corpus", "tests/corpus/dev.jsonl"))) {
      checkRanges(usage, LocalDate.parse("2025-10-01"), LocalDate.parse("2026-08-31"), 11);

      Page month = Page.of(usage.visualizations(null));
      assertEquals(
          new BigDecimal(attribute(usage.overview(), "id=\"kpi-cost\"[^>]*data-usd=\"([^\"]+)\"")),
          month.total(),
          "Overview and the default visualizations month disagree");
    }
  }

  @Test
  void given_randomUploadsWithToolNoise_when_openingManyRanges_then_everyFigureReconciles()
      throws Exception {
    var random = new Random(20_260_715L);
    try (var usage = new UsageFixture(directory.resolve("random"))) {
      for (int upload = 0; upload < 3; upload++) {
        var rows = new java.util.TreeMap<LocalDate, String>();
        for (int row = 0; row < RANDOM_ROWS; row++) {
          LocalDate date = LocalDate.parse("2025-11-01").plusDays(random.nextInt(250));
          rows.put(date, randomRow(random, date));
        }
        // Later uploads overwrite earlier rows for the same day; every view must follow.
        usage.upload(rows.values().toArray(String[]::new));
      }
      checkRanges(usage, LocalDate.parse("2025-10-01"), LocalDate.parse("2026-08-31"), 11);
    }
  }

  private static void checkRanges(UsageFixture usage, LocalDate first, LocalDate last, int seed)
      throws Exception {
    var random = new Random(seed);
    long span = ChronoUnit.DAYS.between(first, last);

    for (YearMonth month = YearMonth.from(first);
        !month.isAfter(YearMonth.from(last));
        month = month.plusMonths(1)) {
      check(usage, "range=month&month=" + month);
    }
    for (int year = first.getYear(); year <= last.getYear(); year++) {
      Page whole = check(usage, "range=year&year=" + year);
      BigDecimal months = BigDecimal.ZERO;
      for (int month = 1; month <= 12; month++) {
        months = months.add(check(usage, "range=month&month=" + YearMonth.of(year, month)).total());
      }
      assertEquals(0, whole.total().compareTo(months), "months of " + year + " != year");
    }
    check(usage, "range=all");

    for (int index = 0; index < RANDOM_RANGES; index++) {
      LocalDate from = first.plusDays(random.nextLong(span + 1));
      LocalDate to = from.plusDays(random.nextLong(ChronoUnit.DAYS.between(from, last) + 1));
      check(usage, custom(from, to));
    }
    for (int index = 0; index < RANDOM_SPLITS; index++) {
      LocalDate from = first.plusDays(random.nextLong(span));
      LocalDate to = from.plusDays(1 + random.nextLong(ChronoUnit.DAYS.between(from, last)));
      LocalDate split = from.plusDays(random.nextLong(ChronoUnit.DAYS.between(from, to)));
      BigDecimal whole = check(usage, custom(from, to)).total();
      BigDecimal parts =
          check(usage, custom(from, split))
              .total()
              .add(check(usage, custom(split.plusDays(1), to)).total());
      assertEquals(0, whole.compareTo(parts), from + ".." + split + ".." + to);
    }
  }

  /// Checks one range in isolation and returns its figures.
  private static Page check(UsageFixture usage, String query) throws Exception {
    Page page = Page.of(usage.visualizations(query));
    assertEquals(0, page.total().compareTo(sum(page.legend())), query + ": tools != total");
    assertEquals(0, page.total().compareTo(sum(page.rows())), query + ": periods != total");
    assertEquals(0, page.total().compareTo(sum(page.columns())), query + ": bars != total");
    assertEquals(0, page.total().compareTo(page.statTotal()), query + ": card total != total");
    assertTrue(page.daysWithUsage() <= page.elapsedDays(), query + ": days with usage");
    BigDecimal expectedPerDay =
        page.elapsedDays() == 0
            ? BigDecimal.ZERO
            : page.total().divide(BigDecimal.valueOf(page.elapsedDays()), 8, RoundingMode.HALF_UP);
    assertEquals(0, expectedPerDay.compareTo(page.perDay()), query + ": per calendar day");
    if (page.total().signum() == 0) assertEquals(0, page.daysWithUsage(), query);
    return page;
  }

  private static String custom(LocalDate from, LocalDate to) {
    return "range=custom&from=" + from + "&to=" + to;
  }

  private static String randomRow(Random random, LocalDate date) {
    BigDecimal cost =
        BigDecimal.valueOf(random.nextLong(5_000_000_000_000L), 10 + random.nextInt(4));
    String[] tools = {"claude", "codex", "pi", "opencode"};
    return switch (random.nextInt(5)) {
      // No tool costs, one named tool.
      case 0 ->
          daily(date.toString(), cost.toPlainString(), "", "\"" + tools[random.nextInt(4)] + "\"");
      // Tool costs that disagree materially with the row.
      case 1 ->
          daily(
              date.toString(),
              cost.toPlainString(),
              agent(tools[random.nextInt(4)], cost.add(BigDecimal.ONE)),
              "");
      default -> {
        int count = 1 + random.nextInt(3);
        var agents = new ArrayList<String>();
        BigDecimal remaining = cost;
        for (int index = 0; index < count; index++) {
          BigDecimal part =
              index == count - 1
                  ? remaining
                  : remaining.multiply(BigDecimal.valueOf(random.nextInt(100), 2));
          remaining = remaining.subtract(part);
          agents.add(agent(tools[index], part));
        }
        // Float-style noise within the rounding slack on some rows.
        if (random.nextBoolean()) {
          agents.add(agent("pi", new BigDecimal("0.000000000000001")));
        }
        yield daily(date.toString(), cost.toPlainString(), String.join(",", agents), "");
      }
    };
  }

  private static String agent(String name, BigDecimal cost) {
    return "{\"agent\":\"" + name + "\",\"totalCost\":" + cost.toPlainString() + "}";
  }

  private static BigDecimal sum(List<BigDecimal> values) {
    return values.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
  }

  private static String attribute(String html, String regex) {
    Matcher matcher = Pattern.compile(regex).matcher(html);
    assertTrue(matcher.find(), html);
    return matcher.group(1);
  }

  /// Each displayed money string must be the exact value rounded half-up to cents.
  private static BigDecimal money(String usd, String shown, String context) {
    BigDecimal value = new BigDecimal(usd);
    var format = new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(Locale.US));
    format.setRoundingMode(RoundingMode.HALF_UP);
    assertEquals(
        "$" + format.format(value),
        (shown.contains(" · ") ? shown.substring(0, shown.indexOf(" · ")) : shown),
        context);
    return value;
  }

  private record Page(
      BigDecimal total,
      BigDecimal perDay,
      long daysWithUsage,
      long elapsedDays,
      BigDecimal statTotal,
      List<BigDecimal> legend,
      List<BigDecimal> rows,
      List<BigDecimal> columns) {
    static Page of(String html) {
      String compact = html.replaceAll(">\\s+<", "><");
      BigDecimal total = null;
      BigDecimal perDay = null;
      BigDecimal statTotal = null;
      long daysWithUsage = -1;
      long elapsedDays = -1;
      Matcher kpi = KPI.matcher(compact);
      while (kpi.find()) {
        switch (kpi.group(1)) {
          case "Estimated cost" -> total = money(kpi.group(2), kpi.group(3), "Estimated cost");
          case "Per calendar day" -> perDay = money(kpi.group(2), kpi.group(3), "Per calendar day");
          case "Total" -> statTotal = money(kpi.group(2), kpi.group(3), "Total");
          case "Days with usage" -> {
            Matcher days = DAYS.matcher(kpi.group(3));
            assertTrue(days.matches(), kpi.group(3));
            daysWithUsage = Long.parseLong(days.group(1));
            elapsedDays = Long.parseLong(days.group(2));
          }
          default -> {
            if (!kpi.group(2).isEmpty()) money(kpi.group(2), kpi.group(3), kpi.group(1));
          }
        }
      }
      assertNotNull(total, html);
      assertNotNull(perDay, html);
      assertNotNull(statTotal, html);
      assertTrue(daysWithUsage >= 0, html);
      return new Page(
          total,
          perDay,
          daysWithUsage,
          elapsedDays,
          statTotal,
          values(LEGEND, compact, "legend"),
          values(ROW, compact, "row"),
          values(RECT, compact, "bar"));
    }

    private static List<BigDecimal> values(Pattern pattern, String html, String context) {
      var result = new ArrayList<BigDecimal>();
      Matcher matcher = pattern.matcher(html);
      while (matcher.find()) result.add(money(matcher.group(1), matcher.group(2), context));
      return List.copyOf(result);
    }
  }
}
