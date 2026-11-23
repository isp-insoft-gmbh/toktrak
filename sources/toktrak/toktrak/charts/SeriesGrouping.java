package toktrak.charts;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/// The costliest names shown as their own series; when there are more names than series, the last
/// series holds the remainder. Grouping is positional, so a name that looks like the remainder
/// label is still its own series.
public record SeriesGrouping(List<String> shown, Remainder remainder) {
  private static final int SERIES_MAX = 64;

  public enum Remainder {
    NONE,
    GROUPED
  }

  public SeriesGrouping {
    shown = List.copyOf(shown);
    Objects.requireNonNull(remainder, "remainder");
    if (new HashSet<>(shown).size() != shown.size()) {
      throw new IllegalArgumentException("shown names must be unique");
    }
  }

  /// Orders names by descending cost, then name, and keeps at most `seriesMax` series.
  public static SeriesGrouping of(Map<String, BigDecimal> totals, int seriesMax) {
    Objects.requireNonNull(totals, "totals");
    if (seriesMax < 2 || seriesMax > SERIES_MAX) {
      throw new IllegalArgumentException("seriesMax must be 2.." + SERIES_MAX);
    }
    List<String> ordered =
        totals.entrySet().stream()
            .sorted(
                Map.Entry.<String, BigDecimal>comparingByValue()
                    .reversed()
                    .thenComparing(Map.Entry.comparingByKey()))
            .map(Map.Entry::getKey)
            .toList();
    if (ordered.size() <= seriesMax) return new SeriesGrouping(ordered, Remainder.NONE);
    return new SeriesGrouping(ordered.subList(0, seriesMax - 1), Remainder.GROUPED);
  }

  public int size() {
    return shown.size() + (remainder == Remainder.GROUPED ? 1 : 0);
  }

  /// Cost per series in series order; the remainder series sums every name not shown.
  public List<BigDecimal> costs(Map<String, BigDecimal> values) {
    Objects.requireNonNull(values, "values");
    var result = new ArrayList<BigDecimal>(size());
    for (String name : shown) result.add(values.getOrDefault(name, BigDecimal.ZERO));
    var known = new HashSet<String>(shown);
    BigDecimal other = BigDecimal.ZERO;
    for (Map.Entry<String, BigDecimal> entry : values.entrySet()) {
      if (known.contains(entry.getKey())) continue;
      if (remainder == Remainder.NONE) {
        throw new IllegalArgumentException("name has no series: " + entry.getKey());
      }
      other = other.add(entry.getValue());
    }
    if (remainder == Remainder.GROUPED) result.add(other);
    assert Statistics.sum(result).compareTo(Statistics.sum(values.values())) == 0;
    return List.copyOf(result);
  }

  /// Index of the series that draws `name`.
  public int indexOf(String name) {
    int index = shown.indexOf(Objects.requireNonNull(name, "name"));
    if (index >= 0) return index;
    if (remainder == Remainder.GROUPED) return shown.size();
    throw new IllegalArgumentException("name has no series: " + name);
  }
}
