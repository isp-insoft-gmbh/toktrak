package toktrak.tests;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import org.junit.jupiter.api.Test;
import toktrak.http.HttpSupport;

final class BodyLimitTest {
  @Test
  void given_limitAboveGlobalCeiling_when_readingBody_then_rejectsLimit() {
    var body = new ByteArrayInputStream(new byte[0]);
    var exception =
        assertThrows(
            IllegalArgumentException.class,
            () -> HttpSupport.readLimited(body, 5 * 1024 * 1024 + 1));
    assertEquals("limit must be 0..5242880 bytes", exception.getMessage());
  }

  @Test
  void given_boundaryLimitsAndExactBodies_when_readingBody_then_returnsBodies() throws Exception {
    assertArrayEquals(
        new byte[0], HttpSupport.readLimited(new ByteArrayInputStream(new byte[0]), 0));
    assertArrayEquals(
        new byte[] {1, 2, 3},
        HttpSupport.readLimited(new ByteArrayInputStream(new byte[] {1, 2, 3}), 3));
    assertArrayEquals(
        new byte[0],
        HttpSupport.readLimited(
            new ByteArrayInputStream(new byte[0]), HttpSupport.MAX_REQUEST_BODY_BYTES));
  }

  @Test
  void given_negativeLimit_when_readingBody_then_rejectsLimit() {
    var exception =
        assertThrows(
            IllegalArgumentException.class,
            () -> HttpSupport.readLimited(new ByteArrayInputStream(new byte[0]), -1));
    assertEquals("limit must be 0..5242880 bytes", exception.getMessage());
  }

  @Test
  void given_zeroProgressStream_when_readingBody_then_throwsIOException() {
    var input =
        new InputStream() {
          @Override
          public int read() {
            return 0;
          }

          @Override
          public int read(byte[] bytes) {
            assertNotNull(bytes);
            return 0;
          }
        };
    var exception =
        assertThrows(java.io.IOException.class, () -> HttpSupport.readLimited(input, 1));
    assertEquals("request body read made no progress", exception.getMessage());
  }

  @Test
  void given_bodyAboveCallerLimit_when_readingBody_then_rejectsBody() throws Exception {
    var body = new ByteArrayInputStream(new byte[1025]);
    var exception =
        assertThrows(IllegalArgumentException.class, () -> HttpSupport.readLimited(body, 1024));
    assertEquals("request body exceeds 1024 bytes", exception.getMessage());
  }
}
