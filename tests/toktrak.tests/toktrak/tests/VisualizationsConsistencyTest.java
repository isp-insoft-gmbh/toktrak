package toktrak.tests;

import static org.junit.jupiter.api.Assertions.*;
import static toktrak.tests.UsageFixture.daily;
import static toktrak.tests.UsageFixture.session;

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

/// Every money figure on the visualizations page must reconcile, for any range. Daily-based figures
/// reconcile exactly with the range total; session figures with the session total, and the coverage
/// note states their ratio. Every displayed money stat must equal its exact canonical USD value
/// (`data-usd` or `data-session-usd`) rounded half-up to cents.
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
  private static final Pattern BAR_ROW =
      Pattern.compile("<div class=\"bar-list-row\" role=\"row\" data-usd=\"([^\"]+)\">");
  private static final Pattern PERSON_TOTAL =
      Pattern.compile("<td data-usd=\"([^\"]+)\">([^<]+)</td>");
  private static final Pattern STACKED_ROW =
      Pattern.compile("<div class=\"stacked-row\" data-usd=\"([^\"]+)\">(.*?)</div>");
  private static final Pattern SEGMENT =
      Pattern.compile("<rect class=\"series-\\d\"[^>]* data-usd=\"([^\"]+)\">");
  private static final Pattern MODEL = Pattern.compile("<li data-model-usd=\"([^\"]+)\">");
  private static final Pattern SESSION_KPI =
      Pattern.compile("<dt>([^<]+)</dt><dd data-session-usd=\"([^\"]*)\">([^<]+)</dd>");
  private static final String INSIGHTS = "aria-labelledby=\"cumulative-heading\"";
  // Each test renders at most about 40 pages so it stays well inside the 5s per-test limit,
  // which also applies under PIT's instrumentation.
  private static final int RANDOM_RANGES = 30;
  private static final int RANDOM_SPLITS = 12;
  private static final LocalDate FIRST = LocalDate.parse("2025-10-01");
  private static final LocalDate LAST = LocalDate.parse("2026-08-31");
  private static final int RANDOM_ROWS = 160;
  private static final int RANDOM_SESSIONS = 60;
  private static final String[] MODELS = {
    "claude-opus-4-8", "gpt-5.5", "[pi] gpt-5.5", "gemini-3", "glm-5", "kimi-k3", "Other models"
  };

  @TempDir java.nio.file.Path directory;

  @Test
  void given_developmentCorpus_when_openingCalendarRanges_then_everyFigureReconciles()
      throws Exception {
    try (var usage = corpus("corpus-calendar")) {
      calendar(usage);
      Page month = Page.of(usage.visualizations(null).replaceAll(">\\s+<", "><"));
      assertEquals(
          new BigDecimal(attribute(usage.overview(), "id=\"kpi-cost\"[^>]*data-usd=\"([^\"]+)\"")),
          month.total(),
          "Overview and the default visualizations month disagree");
    }
  }

  @Test
  void given_developmentCorpus_when_openingRandomRanges_then_everyFigureReconciles()
      throws Exception {
    try (var usage = corpus("corpus-random")) {
      randomRanges(usage, 11);
    }
  }

  @Test
  void given_developmentCorpus_when_splittingRanges_then_partsAddUpToWhole() throws Exception {
    try (var usage = corpus("corpus-splits")) {
      splits(usage, 12);
    }
  }

  @Test
  void given_randomUploadsWithToolNoise_when_openingCalendarRanges_then_everyFigureReconciles()
      throws Exception {
    try (var usage = randomUploads("random-calendar")) {
      calendar(usage);
    }
  }

  @Test
  void given_randomUploadsWithToolNoise_when_openingRandomRanges_then_everyFigureReconciles()
      throws Exception {
    try (var usage = randomUploads("random-random")) {
      randomRanges(usage, 13);
    }
  }

  @Test
  void given_randomUploadsWithToolNoise_when_splittingRanges_then_partsAddUpToWhole()
      throws Exception {
    try (var usage = randomUploads("random-splits")) {
      splits(usage, 14);
    }
  }

  private UsageFixture corpus(String name) throws Exception {
    return new UsageFixture(directory.resolve(name), List.of("--corpus", "tests/corpus/dev.jsonl"));
  }

  /// Three overlapping uploads with a fixed seed, so every test sees the same data.
  private UsageFixture randomUploads(String name) throws Exception {
    var random = new Random(20_260_715L);
    var usage = new UsageFixture(directory.resolve(name));
    for (int upload = 0; upload < 3; upload++) {
      var rows = new java.util.TreeMap<LocalDate, String>();
      for (int row = 0; row < RANDOM_ROWS; row++) {
        LocalDate date = LocalDate.parse("2025-11-01").plusDays(random.nextInt(250));
        rows.put(date, randomRow(random, date));
      }
      var sessions = new ArrayList<String>();
      for (int row = 0; row < RANDOM_SESSIONS; row++) {
        sessions.add(
            session(
                "s-" + upload + "-" + row,
                BigDecimal.valueOf(random.nextLong(3_000_000_000_000L), 10).toPlainString(),
                LocalDate.parse("2025-11-01")
                        .plusDays(random.nextInt(250))
                        .atTime(random.nextInt(24), random.nextInt(60))
                        .toString()
                    + ":00Z"));
      }
      // Later uploads overwrite earlier rows for the same day; every view must follow.
      usage.upload(List.copyOf(rows.values()), sessions);
    }
    return usage;
  }

  /// Every month and year in the window, plus all time; months add up to their year.
  private static void calendar(UsageFixture usage) throws Exception {
    var months = new java.util.HashMap<YearMonth, BigDecimal>();
    for (YearMonth month = YearMonth.from(FIRST);
        !month.isAfter(YearMonth.from(LAST));
        month = month.plusMonths(1)) {
      months.put(month, check(usage, "range=month&month=" + month).total());
    }
    for (int year = FIRST.getYear(); year <= LAST.getYear(); year++) {
      Page whole = check(usage, "range=year&year=" + year);
      BigDecimal monthSum = BigDecimal.ZERO;
      for (int month = 1; month <= 12; month++) {
        YearMonth key = YearMonth.of(year, month);
        BigDecimal monthTotal = months.get(key);
        if (monthTotal == null) monthTotal = check(usage, "range=month&month=" + key).total();
        monthSum = monthSum.add(monthTotal);
      }
      assertEquals(0, whole.total().compareTo(monthSum), "months of " + year + " != year");
    }
    check(usage, "range=all");
  }

  private static void randomRanges(UsageFixture usage, int seed) throws Exception {
    var random = new Random(seed);
    long span = ChronoUnit.DAYS.between(FIRST, LAST);
    for (int index = 0; index < RANDOM_RANGES; index++) {
      LocalDate from = FIRST.plusDays(random.nextLong(span + 1));
      LocalDate to = from.plusDays(random.nextLong(ChronoUnit.DAYS.between(from, LAST) + 1));
      check(usage, custom(from, to));
    }
  }

  private static void splits(UsageFixture usage, int seed) throws Exception {
    var random = new Random(seed);
    long span = ChronoUnit.DAYS.between(FIRST, LAST);
    for (int index = 0; index < RANDOM_SPLITS; index++) {
      LocalDate from = FIRST.plusDays(random.nextLong(span));
      LocalDate to = from.plusDays(1 + random.nextLong(ChronoUnit.DAYS.between(from, LAST)));
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
    // Compact once: the regular expressions below then scan one string without line breaks.
    String compact = usage.visualizations(query).replaceAll(">\\s+<", "><");
    Page page = Page.of(compact);
    checkInsights(compact, page.total(), query);
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

  /// Daily-based insight cards reconcile exactly with the range total; session cards with the
  /// session total. Daily against session is only a coverage note.
  private static void checkInsights(String compact, BigDecimal total, String query) {
    String cumulative = section(compact, "cumulative-heading", "people-active-heading");
    assertEquals(0, total.compareTo(kpi(cumulative, "Total")), query + ": cumulative");

    String sizes = section(compact, "sizes-heading", "concentration-heading");
    assertEquals(0, total.compareTo(sum(exact(BAR_ROW, sizes))), query + ": person-day bins");
    assertEquals(0, total.compareTo(kpi(sizes, "Total")), query + ": person-day total");

    String people = section(compact, "per-person-heading", "model-mix-heading");
    var personTotals = new ArrayList<BigDecimal>();
    Matcher person = PERSON_TOTAL.matcher(people);
    while (person.find()) personTotals.add(money(person.group(1), person.group(2), "person"));
    assertEquals(0, total.compareTo(sum(personTotals)), query + ": people");
    assertEquals(0, total.compareTo(kpi(people, "Total")), query + ": people total");

    String mix = section(compact, "model-mix-heading", "token-price-heading");
    assertEquals(0, total.compareTo(sum(exact(MODEL, mix))), query + ": model legend");
    var periods = new ArrayList<BigDecimal>();
    Matcher period = STACKED_ROW.matcher(mix);
    while (period.find()) {
      BigDecimal periodTotal = new BigDecimal(period.group(1));
      assertEquals(
          0, periodTotal.compareTo(sum(exact(SEGMENT, period.group(2)))), query + ": segments");
      periods.add(periodTotal);
    }
    assertEquals(0, total.compareTo(sum(periods)), query + ": model periods");
    assertEquals(0, total.compareTo(kpi(mix, "Total")), query + ": model total");

    String sessions = section(compact, "sessions-heading", null);
    BigDecimal sessionTotal = null;
    Matcher sessionKpi = SESSION_KPI.matcher(sessions);
    while (sessionKpi.find()) {
      if (sessionKpi.group(1).equals("Session total")) {
        sessionTotal = money(sessionKpi.group(2), sessionKpi.group(3), "Session total");
      }
    }
    assertNotNull(sessionTotal, query + ": session total");
    String sessionSizes =
        section(compact, "session-sizes-heading", "session-concentration-heading");
    assertEquals(
        0, sessionTotal.compareTo(sum(exact(BAR_ROW, sessionSizes))), query + ": session bins");
    assertEquals(0, sessionTotal.compareTo(kpi(sessionSizes, "Total")), query + ": session sizes");
    checkCoverage(sessions, total, sessionTotal, query);

    String insights = compact.substring(compact.indexOf(INSIGHTS));
    Matcher stat = KPI.matcher(insights);
    while (stat.find()) {
      if (!stat.group(2).isEmpty())
        money(stat.group(2), stat.group(3), query + ": " + stat.group(1));
    }
    Matcher sessionStat = SESSION_KPI.matcher(insights);
    while (sessionStat.find()) {
      if (!sessionStat.group(2).isEmpty()) {
        money(sessionStat.group(2), sessionStat.group(3), query + ": " + sessionStat.group(1));
      }
    }
  }

  /// The only place daily and session totals meet: a note with their ratio, warning beyond 2%.
  private static void checkCoverage(
      String sessions, BigDecimal total, BigDecimal sessionTotal, String query) {
    boolean warned = sessions.contains("<strong>Session data incomplete</strong>");
    if (total.signum() == 0) {
      assertTrue(sessions.contains("No daily usage in this range"), query);
      assertEquals(sessionTotal.signum() != 0, warned, query + ": coverage level");
      return;
    }
    BigDecimal share =
        sessionTotal.multiply(BigDecimal.valueOf(100)).divide(total, 1, RoundingMode.HALF_UP);
    assertTrue(
        sessions.contains(
            "Sessions add up to "
                + shown(sessionTotal)
                + " of "
                + shown(total)
                + " daily cost ("
                + share.toPlainString()
                + "%)"),
        query + ": coverage note\n" + sessions);
    assertEquals(
        share.subtract(BigDecimal.valueOf(100)).abs().compareTo(BigDecimal.valueOf(2)) > 0,
        warned,
        query + ": coverage level " + share);
  }

  private static String shown(BigDecimal usd) {
    var format = new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(Locale.US));
    format.setRoundingMode(RoundingMode.HALF_UP);
    return "$" + format.format(usd);
  }

  private static String section(String html, String fromId, String toId) {
    int from = html.indexOf("id=\"" + fromId + "\"");
    int to = toId == null ? html.length() : html.indexOf("id=\"" + toId + "\"", from);
    assertTrue(from >= 0 && to > from, fromId + "\n" + html);
    return html.substring(from, to);
  }

  /// The exact value of one labelled stat, after checking its displayed rounding.
  private static BigDecimal kpi(String html, String label) {
    Matcher kpi = KPI.matcher(html);
    while (kpi.find()) {
      if (kpi.group(1).equals(label)) return money(kpi.group(2), kpi.group(3), label);
    }
    throw new AssertionError("missing stat " + label + "\n" + html);
  }

  private static List<BigDecimal> exact(Pattern pattern, String html) {
    return pattern.matcher(html).results().map(match -> new BigDecimal(match.group(1))).toList();
  }

  private static String custom(LocalDate from, LocalDate to) {
    return "range=custom&from=" + from + "&to=" + to;
  }

  private static String randomRow(Random random, LocalDate date) {
    BigDecimal cost =
        BigDecimal.valueOf(random.nextLong(5_000_000_000_000L), 10 + random.nextInt(4));
    String[] tools = {"claude", "codex", "pi", "opencode"};
    return withModels(
        random,
        cost,
        switch (random.nextInt(5)) {
          // No tool costs, one named tool.
          case 0 ->
              daily(
                  date.toString(),
                  cost.toPlainString(),
                  "",
                  "\"" + tools[random.nextInt(4)] + "\"");
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
        });
  }

  /// Adds model costs that usually split the row exactly, sometimes overshoot it, and may use a
  /// model literally named like the model-mix remainder group.
  private static String withModels(Random random, BigDecimal cost, String row) {
    int count = random.nextInt(5);
    if (count == 0) return row;
    var models = new ArrayList<String>();
    BigDecimal remaining = cost;
    for (int index = 0; index < count; index++) {
      BigDecimal part =
          index == count - 1
              ? remaining
              : remaining.multiply(BigDecimal.valueOf(random.nextInt(100), 2));
      remaining = remaining.subtract(part);
      models.add(model(MODELS[random.nextInt(MODELS.length)], part));
    }
    if (random.nextInt(6) == 0) models.add(model(MODELS[0], BigDecimal.ONE));
    return row.substring(0, row.length() - 1)
        + ",\"modelBreakdowns\":["
        + String.join(",", models)
        + "]}";
  }

  private static String model(String name, BigDecimal cost) {
    return "{\"modelName\":\""
        + name
        + "\",\"cost\":"
        + cost.toPlainString()
        + ",\"inputTokens\":1,\"outputTokens\":1,\"cacheCreationTokens\":0,"
        + "\"cacheReadTokens\":3}";
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
    /// `html` must already have the whitespace between tags removed.
    static Page of(String html) {
      int insights = html.indexOf(INSIGHTS);
      assertTrue(insights >= 0, "insight cards missing\n" + html);
      String compact = html.substring(0, insights);
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
