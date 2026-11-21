package toktrak.charts;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/// Groups costs into size ranges: how often each size occurs and how much cost it adds.
public final class SizeBins {
  private static final int EDGES_MAX = 32;

  private SizeBins() {}

  /// One size range `[lower, upper)`; the last range has no upper edge.
  public record Bin(BigDecimal lower, BigDecimal upper, long count, BigDecimal cost) {
    public Bin {
      Objects.requireNonNull(lower, "lower");
      Objects.requireNonNull(cost, "cost");
      if (upper != null && upper.compareTo(lower) <= 0) {
        throw new IllegalArgumentException("upper must exceed lower");
      }
      if (count < 0 || cost.signum() < 0) throw new IllegalArgumentException("negative bin");
    }
  }

  /// `edges` start at zero and strictly ascend; every cost must be non-negative.
  public static List<Bin> of(Collection<BigDecimal> costs, List<BigDecimal> edges) {
    Objects.requireNonNull(costs, "costs");
    Objects.requireNonNull(edges, "edges");
    if (edges.size() < 2 || edges.size() > EDGES_MAX) {
      throw new IllegalArgumentException("edges must have 2.." + EDGES_MAX + " entries");
    }
    // Each Bin rejects an upper edge at or below its lower edge, so edges must strictly ascend.
    if (edges.getFirst().signum() != 0) throw new IllegalArgumentException("edges start at 0");
    int bins = edges.size();
    long[] counts = new long[bins];
    BigDecimal[] sums = new BigDecimal[bins];
    java.util.Arrays.fill(sums, BigDecimal.ZERO);
    for (BigDecimal cost : costs) {
      Objects.requireNonNull(cost, "cost");
      if (cost.signum() < 0) throw new IllegalArgumentException("cost must not be negative");
      int bin = bins - 1;
      while (cost.compareTo(edges.get(bin)) < 0) bin--;
      assert bin >= 0;
      counts[bin] = Math.addExact(counts[bin], 1);
      sums[bin] = sums[bin].add(cost);
    }
    var result = new ArrayList<Bin>(bins);
    for (int bin = 0; bin < bins; bin++) {
      result.add(
          new Bin(
              edges.get(bin), bin + 1 < bins ? edges.get(bin + 1) : null, counts[bin], sums[bin]));
    }
    assert result.stream().mapToLong(Bin::count).sum() == costs.size();
    return List.copyOf(result);
  }
}
