package toktrak.tests;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import toktrak.charts.Coverage;
import toktrak.charts.Coverage.Basis;
import toktrak.charts.Coverage.Level;

final class CoverageTest {
  private static final BigDecimal TOLERANCE = new BigDecimal("2");

  @Test
  void given_sharesAroundTolerance_when_measuring_then_warnsOnlyBeyondIt() {
    assertEquals(new Coverage(Basis.REFERENCE, d("98.0"), Level.CONSISTENT), of("98", "100"));
    assertEquals(new Coverage(Basis.REFERENCE, d("97.9"), Level.WARNING), of("97.9", "100"));
    assertEquals(new Coverage(Basis.REFERENCE, d("102.0"), Level.CONSISTENT), of("102", "100"));
    assertEquals(new Coverage(Basis.REFERENCE, d("102.1"), Level.WARNING), of("102.1", "100"));
    // The share rounds half-up to one place before the comparison.
    assertEquals(Level.CONSISTENT, of("97.95", "100").level());
    assertEquals(Level.WARNING, of("97.94", "100").level());
  }

  @Test
  void given_noReference_when_measuring_then_warnsOnlyAboutUnexplainedCost() {
    assertEquals(new Coverage(Basis.NO_REFERENCE, d("0.0"), Level.WARNING), of("5", "0"));
    assertEquals(new Coverage(Basis.NO_REFERENCE, d("0.0"), Level.CONSISTENT), of("0", "0"));
  }

  @Test
  void given_zeroTolerance_when_measuring_then_onlyExactCoverageIsConsistent() {
    assertEquals(Level.CONSISTENT, Coverage.of(d("100"), d("100"), BigDecimal.ZERO).level());
    assertEquals(Level.WARNING, Coverage.of(d("99.9"), d("100"), BigDecimal.ZERO).level());
    assertEquals(
        new Coverage(Basis.REFERENCE, d("0.0"), Level.WARNING),
        Coverage.of(BigDecimal.ZERO, d("100"), TOLERANCE));
  }

  @Test
  void given_negativeInput_when_measuring_then_rejectsIt() {
    assertThrows(IllegalArgumentException.class, () -> of("-1", "1"));
    assertThrows(IllegalArgumentException.class, () -> of("1", "-1"));
    assertThrows(IllegalArgumentException.class, () -> Coverage.of(d("1"), d("1"), d("-1")));
  }

  private static Coverage of(String covered, String reference) {
    return Coverage.of(d(covered), d(reference), TOLERANCE);
  }

  private static BigDecimal d(String value) {
    return new BigDecimal(value);
  }
}
