package toktrak.tests;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import toktrak.charts.SeriesGrouping;
import toktrak.charts.SeriesGrouping.Remainder;

final class SeriesGroupingTest {
  @Test
  void given_fewNames_when_grouping_then_ordersByCostThenNameWithoutRemainder() {
    var grouping = SeriesGrouping.of(Map.of("b", d("5"), "a", d("5"), "c", d("1")), 6);
    assertEquals(new SeriesGrouping(List.of("a", "b", "c"), Remainder.NONE), grouping);
    assertEquals(3, grouping.size());
    assertEquals(List.of(d("1"), d("0"), d("2")), grouping.costs(Map.of("a", d("1"), "c", d("2"))));
    assertEquals(1, grouping.indexOf("b"));
    assertThrows(IllegalArgumentException.class, () -> grouping.indexOf("z"));
    assertThrows(IllegalArgumentException.class, () -> grouping.costs(Map.of("z", d("1"))));
  }

  @Test
  void given_moreNamesThanSeries_when_grouping_then_lastSeriesHoldsRemainderByPosition() {
    var totals = new LinkedHashMap<String, BigDecimal>();
    totals.put("Other models", d("70"));
    for (int index = 1; index <= 6; index++) totals.put("m" + index, BigDecimal.valueOf(index));
    var grouping = SeriesGrouping.of(totals, 6);
    assertEquals(
        new SeriesGrouping(List.of("Other models", "m6", "m5", "m4", "m3"), Remainder.GROUPED),
        grouping);
    assertEquals(6, grouping.size());
    assertEquals(0, grouping.indexOf("Other models"));
    assertEquals(5, grouping.indexOf("m1"));
    assertEquals(List.of(d("70"), d("6"), d("5"), d("4"), d("3"), d("3")), grouping.costs(totals));
  }

  @Test
  void given_invalidLimitsOrNames_when_grouping_then_rejectsThem() {
    assertThrows(IllegalArgumentException.class, () -> SeriesGrouping.of(Map.of(), 1));
    assertEquals(
        new SeriesGrouping(List.of("a"), Remainder.GROUPED),
        SeriesGrouping.of(Map.of("a", d("3"), "b", d("2"), "c", d("1")), 2));
    assertEquals(0, SeriesGrouping.of(Map.of(), 64).size());
    assertThrows(IllegalArgumentException.class, () -> SeriesGrouping.of(Map.of(), 65));
    assertThrows(
        IllegalArgumentException.class,
        () -> new SeriesGrouping(List.of("a", "a"), Remainder.NONE));
  }

  private static BigDecimal d(String value) {
    return new BigDecimal(value);
  }
}
