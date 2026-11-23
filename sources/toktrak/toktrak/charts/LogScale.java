package toktrak.charts;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/// Maps costs onto 0..1 with base-10 logarithms between `floor` and `maximum`, so values that
/// differ by orders of magnitude stay visible on one shared scale. Values at or below the floor map
/// to zero; the maximum maps to one.
public record LogScale(BigDecimal floor, BigDecimal maximum) {
  private static final int DECADES_MAX = 16;

  public LogScale {
    Objects.requireNonNull(floor, "floor");
    Objects.requireNonNull(maximum, "maximum");
    if (floor.signum() <= 0) throw new IllegalArgumentException("floor must be positive");
    if (maximum.signum() < 0) throw new IllegalArgumentException("maximum must not be negative");
  }

  public double fraction(BigDecimal value) {
    Objects.requireNonNull(value, "value");
    if (value.compareTo(floor) <= 0) return 0;
    // A maximum at or below the floor leaves no span, so any value above the floor fills.
    double span =
        Math.max(
            Math.log10(maximum.doubleValue()) - Math.log10(floor.doubleValue()), Double.MIN_NORMAL);
    double position = Math.log10(value.doubleValue()) - Math.log10(floor.doubleValue());
    double result = Math.min(1, position / span);
    assert result > 0 && result <= 1;
    return result;
  }

  /// Powers of ten above the floor and not above the maximum, for reference lines.
  public List<BigDecimal> decades() {
    var result = new ArrayList<BigDecimal>();
    // The power of ten at or below the floor, then step up past it.
    BigDecimal decade = BigDecimal.ONE.scaleByPowerOfTen(floor.precision() - floor.scale() - 1);
    while (decade.compareTo(floor) <= 0) decade = decade.movePointRight(1);
    while (decade.compareTo(maximum) <= 0 && result.size() < DECADES_MAX) {
      result.add(decade.stripTrailingZeros());
      decade = decade.movePointRight(1);
    }
    return List.copyOf(result);
  }
}
