package toktrak.charts;

import java.math.BigDecimal;
import java.util.Objects;

/// How well one total covers a reference total, such as session cost against daily cost.
/// `sharePercent` has one decimal place and is zero when there is no reference.
public record Coverage(Basis basis, BigDecimal sharePercent, Level level) {
  public enum Basis {
    NO_REFERENCE,
    REFERENCE
  }

  public enum Level {
    CONSISTENT,
    WARNING
  }

  public Coverage {
    Objects.requireNonNull(basis, "basis");
    Objects.requireNonNull(sharePercent, "sharePercent");
    Objects.requireNonNull(level, "level");
  }

  /// Warns when the share is further than `tolerancePercent` from 100%, or when there is cost but
  /// no reference to compare it with.
  public static Coverage of(BigDecimal covered, BigDecimal reference, BigDecimal tolerancePercent) {
    Objects.requireNonNull(covered, "covered");
    Objects.requireNonNull(reference, "reference");
    Objects.requireNonNull(tolerancePercent, "tolerancePercent");
    if (covered.signum() < 0 || reference.signum() < 0 || tolerancePercent.signum() < 0) {
      throw new IllegalArgumentException("coverage inputs must not be negative");
    }
    if (reference.signum() == 0) {
      return new Coverage(
          Basis.NO_REFERENCE,
          BigDecimal.ZERO.setScale(1),
          covered.signum() == 0 ? Level.CONSISTENT : Level.WARNING);
    }
    BigDecimal share = Statistics.percent(covered, reference, 1);
    BigDecimal distance = share.subtract(BigDecimal.valueOf(100)).abs();
    return new Coverage(
        Basis.REFERENCE,
        share,
        distance.compareTo(tolerancePercent) > 0 ? Level.WARNING : Level.CONSISTENT);
  }
}
