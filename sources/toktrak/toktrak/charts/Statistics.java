package toktrak.charts;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/// Exact statistics over costs. Divisions round half-up to {@link #SCALE} decimal places.
public final class Statistics {
  /// Decimal places kept by every division.
  public static final int SCALE = 8;

  private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
  private static final BigDecimal MILLION = BigDecimal.valueOf(1_000_000);

  private Statistics() {}

  public static BigDecimal sum(Collection<BigDecimal> values) {
    Objects.requireNonNull(values, "values");
    BigDecimal total = BigDecimal.ZERO;
    for (BigDecimal value : values) total = total.add(Objects.requireNonNull(value, "value"));
    return total;
  }

  /// The middle value, or the mean of the two middle values, of a non-empty ascending list.
  public static BigDecimal median(List<BigDecimal> ascending) {
    requireAscending(ascending);
    if (ascending.isEmpty()) throw new IllegalArgumentException("median of no values");
    int middle = ascending.size() / 2;
    if (ascending.size() % 2 == 1) return ascending.get(middle);
    return ascending
        .get(middle - 1)
        .add(ascending.get(middle))
        .divide(BigDecimal.TWO, SCALE, RoundingMode.HALF_UP);
  }

  /// Nearest-rank percentile of a non-empty ascending list; `percentile` is 1 to 100.
  public static BigDecimal quantile(List<BigDecimal> ascending, int percentile) {
    requireAscending(ascending);
    if (ascending.isEmpty()) throw new IllegalArgumentException("quantile of no values");
    if (percentile < 1 || percentile > 100) {
      throw new IllegalArgumentException("percentile must be 1..100: " + percentile);
    }
    long rank = ((long) ascending.size() * percentile + 99) / 100;
    assert rank >= 1 && rank <= ascending.size();
    return ascending.get((int) rank - 1);
  }

  /// `total` divided by `count`, or zero when there is nothing to divide by.
  public static BigDecimal perUnit(BigDecimal total, long count) {
    Objects.requireNonNull(total, "total");
    if (count < 0) throw new IllegalArgumentException("count must not be negative: " + count);
    if (count == 0) return BigDecimal.ZERO;
    return total.divide(BigDecimal.valueOf(count), SCALE, RoundingMode.HALF_UP);
  }

  /// `part` as a percentage of `whole` rounded to `scale` places, or zero for an empty whole.
  public static BigDecimal percent(BigDecimal part, BigDecimal whole, int scale) {
    Objects.requireNonNull(part, "part");
    Objects.requireNonNull(whole, "whole");
    if (scale < 0 || scale > SCALE) {
      throw new IllegalArgumentException("scale must be 0.." + SCALE + ": " + scale);
    }
    if (whole.signum() == 0) return BigDecimal.ZERO.setScale(scale);
    return part.multiply(HUNDRED).divide(whole, scale, RoundingMode.HALF_UP);
  }

  /// Cost per million tokens, or zero when no tokens were used.
  public static BigDecimal perMillion(BigDecimal cost, BigInteger tokens) {
    Objects.requireNonNull(cost, "cost");
    Objects.requireNonNull(tokens, "tokens");
    if (tokens.signum() < 0) throw new IllegalArgumentException("tokens must not be negative");
    if (tokens.signum() == 0) return BigDecimal.ZERO;
    return cost.multiply(MILLION).divide(new BigDecimal(tokens), SCALE, RoundingMode.HALF_UP);
  }

  /// Bounded by the caller's list size; rejects nulls and descending neighbours.
  static void requireAscending(List<BigDecimal> values) {
    Objects.requireNonNull(values, "values");
    BigDecimal previous = null;
    for (BigDecimal value : values) {
      Objects.requireNonNull(value, "value");
      if (previous != null && previous.compareTo(value) > 0) {
        throw new IllegalArgumentException("values must be ascending");
      }
      previous = value;
    }
  }
}
