package toktrak.tests;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import toktrak.charts.LogScale;

final class LogScaleTest {
  @Test
  void given_fourDecades_when_mappingCosts_then_eachDecadeTakesAQuarter() {
    var scale = new LogScale(d("0.1"), d("1000"));
    assertEquals(0, scale.fraction(d("0")));
    assertEquals(0, scale.fraction(d("0.1")));
    assertEquals(0.25, scale.fraction(d("1")), 1e-12);
    assertEquals(0.5, scale.fraction(d("10")), 1e-12);
    assertEquals(1, scale.fraction(d("1000")), 1e-12);
    assertEquals(1, scale.fraction(d("5000")));
  }

  @Test
  void given_maximumAtOrBelowFloor_when_mapping_then_anyCostAboveFloorFills() {
    var scale = new LogScale(d("0.1"), d("0.05"));
    assertEquals(1, scale.fraction(d("0.2")));
    assertEquals(0, scale.fraction(d("0.1")));
  }

  @Test
  void given_floorsAndMaximums_when_listingDecades_then_returnsPowersAboveFloorUpToMaximum() {
    assertEquals(
        List.of("1", "10", "100", "1000"), plain(new LogScale(d("0.1"), d("1000")).decades()));
    assertEquals(List.of("1", "10"), plain(new LogScale(d("0.3"), d("99")).decades()));
    assertEquals(List.of("10"), plain(new LogScale(d("2"), d("25")).decades()));
    assertEquals(List.of(), plain(new LogScale(d("1"), d("1")).decades()));
    assertEquals(List.of(), plain(new LogScale(d("0.1"), d("0.5")).decades()));
    // At most sixteen reference lines, however wide the range.
    assertEquals(16, new LogScale(d("1E-20"), d("1E+20")).decades().size());
  }

  @Test
  void given_invalidBounds_when_creatingScale_then_rejectsThem() {
    assertThrows(IllegalArgumentException.class, () -> new LogScale(d("0"), d("1")));
    assertThrows(IllegalArgumentException.class, () -> new LogScale(d("0.1"), d("-1")));
  }

  private static List<String> plain(List<BigDecimal> values) {
    return values.stream().map(BigDecimal::toPlainString).toList();
  }

  private static BigDecimal d(String value) {
    return new BigDecimal(value);
  }
}
