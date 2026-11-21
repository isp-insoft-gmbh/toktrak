package toktrak.charts;

import java.math.BigDecimal;
import java.util.List;

/// How much of the total the costliest items cause.
public final class Concentration {
  private Concentration() {}

  /// Percentage of the total caused by the costliest `percent`% of an ascending list, rounding the
  /// item count up; zero when the total is zero.
  public static BigDecimal topShare(List<BigDecimal> ascending, int percent) {
    Statistics.requireAscending(ascending);
    if (percent < 0 || percent > 100) {
      throw new IllegalArgumentException("percent must be 0..100: " + percent);
    }
    int size = ascending.size();
    int items = (int) (((long) size * percent + 99) / 100);
    assert items >= 0 && items <= size;
    BigDecimal total = Statistics.sum(ascending);
    BigDecimal top = Statistics.sum(ascending.subList(size - items, size));
    return Statistics.percent(top, total, Statistics.SCALE);
  }
}
