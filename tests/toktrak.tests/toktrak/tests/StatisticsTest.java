package toktrak.tests;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import toktrak.charts.Statistics;

final class StatisticsTest {
  @Test
  void given_costs_when_summing_then_addsExactlyAndRejectsNulls() {
    assertEquals(new BigDecimal("3.3"), Statistics.sum(List.of(d("1.1"), d("2.2"))));
    assertEquals(BigDecimal.ZERO, Statistics.sum(List.of()));
    var withNull = new ArrayList<BigDecimal>();
    withNull.add(null);
    assertThrows(NullPointerException.class, () -> Statistics.sum(withNull));
  }

  @Test
  void given_ascendingCosts_when_takingMedian_then_picksMiddleOrAveragesPair() {
    assertEquals(d("2"), Statistics.median(List.of(d("1"), d("2"), d("9"))));
    assertEquals(0, d("0.15").compareTo(Statistics.median(List.of(d("0.1"), d("0.2")))));
    // Half a hundred-millionth rounds up at eight places.
    assertEquals(d("0.00000001"), Statistics.median(List.of(d("0"), d("0.00000001"))));
    assertThrows(IllegalArgumentException.class, () -> Statistics.median(List.of()));
    assertThrows(IllegalArgumentException.class, () -> Statistics.median(List.of(d("2"), d("1"))));
  }

  @Test
  void given_tenCosts_when_takingNearestRankQuantiles_then_roundsRankUp() {
    var values = new ArrayList<BigDecimal>();
    for (int value = 1; value <= 10; value++) values.add(BigDecimal.valueOf(value));
    assertEquals(d("1"), Statistics.quantile(values, 1));
    assertEquals(d("9"), Statistics.quantile(values, 90));
    assertEquals(d("10"), Statistics.quantile(values, 91));
    assertEquals(d("10"), Statistics.quantile(values, 100));
    List<BigDecimal> three = List.of(d("1"), d("2"), d("3"));
    assertEquals(d("1"), Statistics.quantile(three, 33));
    assertEquals(d("2"), Statistics.quantile(three, 34));
    assertThrows(IllegalArgumentException.class, () -> Statistics.quantile(values, 0));
    assertThrows(IllegalArgumentException.class, () -> Statistics.quantile(values, 101));
    assertThrows(IllegalArgumentException.class, () -> Statistics.quantile(List.of(), 50));
    assertThrows(
        IllegalArgumentException.class, () -> Statistics.quantile(List.of(d("2"), d("1")), 50));
  }

  @Test
  void given_totalsAndCounts_when_dividing_then_roundsHalfUpToEightPlacesOrReturnsZero() {
    assertEquals(d("2.50000000"), Statistics.perUnit(d("10"), 4));
    assertEquals(d("0.66666667"), Statistics.perUnit(d("2"), 3));
    assertEquals(BigDecimal.ZERO, Statistics.perUnit(d("5"), 0));
    assertThrows(IllegalArgumentException.class, () -> Statistics.perUnit(d("5"), -1));
  }

  @Test
  void given_partsOfWholes_when_takingPercent_then_roundsToScaleAndHandlesEmptyWhole() {
    assertEquals(d("33.3"), Statistics.percent(d("1"), d("3"), 1));
    assertEquals(d("67"), Statistics.percent(d("2"), d("3"), 0));
    assertEquals(d("0.0"), Statistics.percent(d("1"), BigDecimal.ZERO, 1));
    assertEquals(d("100.00000000"), Statistics.percent(d("7"), d("7"), Statistics.SCALE));
    assertThrows(IllegalArgumentException.class, () -> Statistics.percent(d("1"), d("2"), -1));
    assertThrows(
        IllegalArgumentException.class,
        () -> Statistics.percent(d("1"), d("2"), Statistics.SCALE + 1));
  }

  @Test
  void given_costAndTokens_when_pricingPerMillion_then_scalesOrReturnsZero() {
    assertEquals(d("0.50000000"), Statistics.perMillion(d("2"), BigInteger.valueOf(4_000_000)));
    assertEquals(d("3000000.00000000"), Statistics.perMillion(d("3"), BigInteger.ONE));
    assertEquals(BigDecimal.ZERO, Statistics.perMillion(d("2"), BigInteger.ZERO));
    assertThrows(
        IllegalArgumentException.class,
        () -> Statistics.perMillion(d("2"), BigInteger.ONE.negate()));
  }

  private static BigDecimal d(String value) {
    return new BigDecimal(value);
  }
}
