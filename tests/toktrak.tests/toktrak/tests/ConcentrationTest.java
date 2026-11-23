package toktrak.tests;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import toktrak.charts.Concentration;

final class ConcentrationTest {
  @Test
  void given_skewedCosts_when_takingTopShares_then_roundsItemCountUp() {
    List<BigDecimal> costs = List.of(d("1"), d("1"), d("1"), d("7"));
    assertEquals(0, d("0").compareTo(Concentration.topShare(costs, 0)));
    assertEquals(0, d("70").compareTo(Concentration.topShare(costs, 1)));
    assertEquals(0, d("70").compareTo(Concentration.topShare(costs, 25)));
    assertEquals(0, d("80").compareTo(Concentration.topShare(costs, 26)));
    assertEquals(0, d("100").compareTo(Concentration.topShare(costs, 100)));
    assertEquals(0, d("90").compareTo(Concentration.topShare(List.of(d("1"), d("2"), d("7")), 50)));
  }

  @Test
  void given_zeroOrInvalidInput_when_takingTopShare_then_returnsZeroOrRejects() {
    assertEquals(0, Concentration.topShare(List.of(d("0"), d("0")), 50).signum());
    assertEquals(0, Concentration.topShare(List.of(), 50).signum());
    assertThrows(IllegalArgumentException.class, () -> Concentration.topShare(List.of(), 101));
    assertThrows(IllegalArgumentException.class, () -> Concentration.topShare(List.of(), -1));
    assertThrows(
        IllegalArgumentException.class, () -> Concentration.topShare(List.of(d("2"), d("1")), 50));
  }

  private static BigDecimal d(String value) {
    return new BigDecimal(value);
  }
}
