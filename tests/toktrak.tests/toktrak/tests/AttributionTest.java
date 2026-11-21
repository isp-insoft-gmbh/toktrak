package toktrak.tests;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import toktrak.charts.Attribution;

final class AttributionTest {
  @Test
  void given_partsMatchingRow_when_splitting_then_keepsThemExactly() {
    assertEquals(
        Map.of("a", d("4"), "b", d("6")),
        Attribution.split(parts("a", "4", "b", "6"), d("10"), null));
  }

  @Test
  void given_roundingNoise_when_splitting_then_capsOvershootAndFoldsUndershootIntoLargest() {
    assertEquals(
        Map.of("a", d("4"), "b", d("6")),
        Attribution.split(parts("a", "4", "b", "6.0000001"), d("10"), null));
    assertEquals(
        Map.of("a", d("4"), "b", d("6.0000005")),
        Attribution.split(parts("a", "4", "b", "6"), d("10.0000005"), null));
  }

  @Test
  void given_materialDisagreement_when_splitting_then_usesFallbackOrNotReported() {
    assertEquals(
        Map.of("pi", d("10")), Attribution.split(parts("a", "4", "b", "7"), d("10"), "pi"));
    assertEquals(
        Map.of(Attribution.NOT_REPORTED, d("10")),
        Attribution.split(parts("a", "4", "b", "7"), d("10"), null));
    assertEquals(
        Map.of("a", d("4"), Attribution.NOT_REPORTED, d("6")),
        Attribution.split(parts("a", "4"), d("10"), "pi"));
  }

  @Test
  void given_emptyOrInvalidRow_when_splitting_then_returnsNothingOrRejects() {
    assertEquals(Map.of(), Attribution.split(parts(), BigDecimal.ZERO, "pi"));
    assertThrows(IllegalArgumentException.class, () -> Attribution.split(parts(), d("-1"), null));
  }

  private static Map<String, BigDecimal> parts(String... namesAndCosts) {
    var result = new LinkedHashMap<String, BigDecimal>();
    for (int index = 0; index < namesAndCosts.length; index += 2) {
      result.put(namesAndCosts[index], d(namesAndCosts[index + 1]));
    }
    return result;
  }

  private static BigDecimal d(String value) {
    return new BigDecimal(value);
  }
}
