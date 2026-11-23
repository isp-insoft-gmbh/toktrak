package toktrak.http;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.Year;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/// Inclusive calendar-date range shared by every chart on a dashboard page.
public record UsageRange(UsageRange.Kind kind, LocalDate from, LocalDate to) {
  static final Set<String> QUERY_FIELDS = Set.of("range", "month", "year", "from", "to");
  private static final long DAYS_MAX = 3_660;
  private static final int YEAR_MIN = 2000;
  private static final int YEAR_MAX = 9999;
  private static final DateTimeFormatter DAY =
      DateTimeFormatter.ofPattern("d MMM uuuu", Locale.ENGLISH);
  private static final DateTimeFormatter MONTH =
      DateTimeFormatter.ofPattern("MMMM uuuu", Locale.ENGLISH);

  public enum Kind {
    MONTH,
    YEAR,
    ALL,
    CUSTOM
  }

  public UsageRange {
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(from, "from");
    Objects.requireNonNull(to, "to");
    if (from.isAfter(to)) throw new IllegalArgumentException("range starts after it ends");
    if (ChronoUnit.DAYS.between(from, to) >= DAYS_MAX) {
      throw new IllegalArgumentException("range exceeds " + DAYS_MAX + " days");
    }
    if (from.getYear() < YEAR_MIN || to.getYear() > YEAR_MAX) {
      throw new IllegalArgumentException("range is outside supported years");
    }
  }

  /// Parses range query fields; absent fields select the month of the latest usage. `earliest` and
  /// `latest` are the first and last dates with usage, or `today` when none exist.
  static UsageRange parse(Map<String, String> fields, LocalDate earliest, LocalDate latest) {
    Objects.requireNonNull(fields, "fields");
    Objects.requireNonNull(earliest, "earliest");
    Objects.requireNonNull(latest, "latest");
    assert !earliest.isAfter(latest);
    String kind = fields.get("range");
    try {
      if (kind == null) {
        requireOnly(fields, Set.of());
        return month(YearMonth.from(latest));
      }
      return switch (kind) {
        case "month" -> {
          requireOnly(fields, Set.of("range", "month"));
          yield month(YearMonth.parse(required(fields, "month")));
        }
        case "year" -> {
          requireOnly(fields, Set.of("range", "year"));
          int year = Integer.parseInt(required(fields, "year"));
          if (year < YEAR_MIN || year > YEAR_MAX) throw new IllegalArgumentException("year");
          yield new UsageRange(Kind.YEAR, LocalDate.of(year, 1, 1), LocalDate.of(year, 12, 31));
        }
        case "all" -> {
          requireOnly(fields, Set.of("range"));
          // Uploaded periods are unbounded; keep the newest span this range type can represent.
          LocalDate floor = latest.minusDays(DAYS_MAX - 1);
          LocalDate from = earliest.isBefore(floor) ? floor : earliest;
          LocalDate minimum = LocalDate.of(YEAR_MIN, 1, 1);
          yield new UsageRange(Kind.ALL, from.isBefore(minimum) ? minimum : from, latest);
        }
        case "custom" -> {
          requireOnly(fields, Set.of("range", "from", "to"));
          yield new UsageRange(
              Kind.CUSTOM,
              LocalDate.parse(required(fields, "from")),
              LocalDate.parse(required(fields, "to")));
        }
        default -> throw new IllegalArgumentException("range kind");
      };
    } catch (DateTimeException | NumberFormatException exception) {
      throw new IllegalArgumentException("range query is invalid", exception);
    }
  }

  static UsageRange month(YearMonth month) {
    Objects.requireNonNull(month, "month");
    return new UsageRange(Kind.MONTH, month.atDay(1), month.atEndOfMonth());
  }

  boolean contains(LocalDate date) {
    return !date.isBefore(from) && !date.isAfter(to);
  }

  long days() {
    return ChronoUnit.DAYS.between(from, to) + 1;
  }

  /// Query string that reproduces this range, without the leading `?`.
  String query() {
    return switch (kind) {
      case MONTH -> "range=month&month=" + YearMonth.from(from);
      case YEAR -> "range=year&year=" + from.getYear();
      case ALL -> "range=all";
      case CUSTOM -> "range=custom&from=" + from + "&to=" + to;
    };
  }

  String label() {
    return switch (kind) {
      case MONTH -> MONTH.format(from);
      case YEAR -> Year.from(from).toString();
      case ALL, CUSTOM -> DAY.format(from) + " – " + DAY.format(to);
    };
  }

  private static void requireOnly(Map<String, String> fields, Set<String> allowed) {
    for (String name : fields.keySet()) {
      if (!allowed.contains(name)) throw new IllegalArgumentException("unexpected " + name);
    }
  }

  private static String required(Map<String, String> fields, String name) {
    String value = fields.get(name);
    if (value == null || value.isBlank() || value.length() > 10) {
      throw new IllegalArgumentException(name + " is required");
    }
    return value;
  }
}
