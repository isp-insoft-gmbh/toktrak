package toktrak.tests;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import toktrak.charts.SizeBins;
import toktrak.charts.SizeBins.Bin;

final class SizeBinsTest {
  private static final List<BigDecimal> EDGES = List.of(d("0"), d("0.3"), d("1"), d("1000"));

  @Test
  void given_costsOnEveryEdge_when_binning_then_lowerEdgesAreInclusiveAndLastBinIsOpen() {
    List<Bin> bins =
        SizeBins.of(
            List.of(d("0"), d("0.29"), d("0.3"), d("1"), d("999"), d("1000"), d("5000")), EDGES);
    assertEquals(
        List.of(
            new Bin(d("0"), d("0.3"), 2, d("0.29")),
            new Bin(d("0.3"), d("1"), 1, d("0.3")),
            new Bin(d("1"), d("1000"), 2, d("1000")),
            new Bin(d("1000"), null, 2, d("6000"))),
        bins);
  }

  @Test
  void given_noCosts_when_binning_then_returnsEveryRangeEmpty() {
    List<Bin> bins = SizeBins.of(List.of(), EDGES);
    assertEquals(4, bins.size());
    assertTrue(bins.stream().allMatch(bin -> bin.count() == 0 && bin.cost().signum() == 0));
  }

  @Test
  void given_invalidEdgesOrCosts_when_binning_then_rejectsInput() {
    assertThrows(IllegalArgumentException.class, () -> SizeBins.of(List.of(), List.of(d("0"))));
    assertThrows(
        IllegalArgumentException.class, () -> SizeBins.of(List.of(), List.of(d("1"), d("2"))));
    assertThrows(
        IllegalArgumentException.class,
        () -> SizeBins.of(List.of(), List.of(d("0"), d("2"), d("2"))));
    assertThrows(
        IllegalArgumentException.class,
        () -> SizeBins.of(List.of(d("4")), List.of(d("0"), d("5"), d("3"))));
    assertThrows(IllegalArgumentException.class, () -> SizeBins.of(List.of(d("-1")), EDGES));
    assertEquals(2, SizeBins.of(List.of(), List.of(d("0"), d("1"))).size());
    assertEquals(32, SizeBins.of(List.of(), edges(32)).size());
    assertThrows(IllegalArgumentException.class, () -> SizeBins.of(List.of(), edges(33)));
    assertThrows(IllegalArgumentException.class, () -> new Bin(d("1"), d("1"), 0, d("0")));
    assertThrows(IllegalArgumentException.class, () -> new Bin(d("0"), null, -1, d("0")));
  }

  private static List<BigDecimal> edges(int count) {
    return java.util.stream.IntStream.range(0, count).mapToObj(BigDecimal::valueOf).toList();
  }

  private static BigDecimal d(String value) {
    return new BigDecimal(value);
  }
}
