package toktrak.tests;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import org.junit.jupiter.api.Test;
import toktrak.http.HttpSupport;

final class BodyLimitTest {
  @Test
  void rejectsCallerLimitAboveGlobalCeiling() {
    var body = new ByteArrayInputStream(new byte[0]);
    var exception =
        assertThrows(
            IllegalArgumentException.class,
            () -> HttpSupport.readLimited(body, 5 * 1024 * 1024 + 1));
    assertEquals("limit must be 0..5242880 bytes", exception.getMessage());
  }

  @Test
  void rejectsBodyAboveLimit() throws Exception {
    var body = new ByteArrayInputStream(new byte[1025]);
    var exception =
        assertThrows(IllegalArgumentException.class, () -> HttpSupport.readLimited(body, 1024));
    assertEquals("request body exceeds 1024 bytes", exception.getMessage());
  }
}
