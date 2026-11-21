package toktrak.charts;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/// Splits one reported row cost across the parts that reported it, such as tools or models.
public final class Attribution {
  /// Name of the cost that no part explains.
  public static final String NOT_REPORTED = "Not reported";

  /// Reported parts may disagree with the row by this much from floating-point rounding.
  private static final BigDecimal ROUNDING_SLACK = new BigDecimal("0.000001");

  private Attribution() {}

  /// Splits `rowCost` so the result always sums exactly to it. Parts within the rounding slack of
  /// the row absorb noise into the largest part; parts that exceed the row by more are ignored in
  /// favour of `fallback` when named, else {@link #NOT_REPORTED}.
  public static Map<String, BigDecimal> split(
      Map<String, BigDecimal> reported, BigDecimal rowCost, String fallback) {
    Objects.requireNonNull(reported, "reported");
    Objects.requireNonNull(rowCost, "rowCost");
    if (rowCost.signum() < 0) throw new IllegalArgumentException("rowCost must not be negative");
    BigDecimal reportedTotal = Statistics.sum(reported.values());
    var result = new LinkedHashMap<String, BigDecimal>();
    BigDecimal attributed = BigDecimal.ZERO;
    if (reportedTotal.subtract(rowCost).compareTo(ROUNDING_SLACK) <= 0) {
      for (Map.Entry<String, BigDecimal> part : reported.entrySet()) {
        BigDecimal cost = part.getValue().min(rowCost.subtract(attributed));
        if (cost.signum() <= 0) continue;
        result.put(part.getKey(), cost);
        attributed = attributed.add(cost);
      }
    }
    if (result.isEmpty() && fallback != null && rowCost.signum() > 0) {
      result.put(fallback, rowCost);
      attributed = rowCost;
    }
    BigDecimal unattributed = rowCost.subtract(attributed);
    assert unattributed.signum() >= 0;
    if (unattributed.signum() > 0) {
      if (!result.isEmpty() && unattributed.compareTo(ROUNDING_SLACK) <= 0) {
        String largest =
            result.entrySet().stream().max(Map.Entry.comparingByValue()).orElseThrow().getKey();
        result.merge(largest, unattributed, BigDecimal::add);
      } else {
        result.merge(NOT_REPORTED, unattributed, BigDecimal::add);
      }
    }
    assert Statistics.sum(result.values()).compareTo(rowCost) == 0;
    return result;
  }
}
