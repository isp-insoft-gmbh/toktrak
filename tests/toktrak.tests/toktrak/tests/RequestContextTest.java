package toktrak.tests;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import toktrak.http.RequestContext;

final class RequestContextTest {
  @Test
  void given_fieldsAtTheirLimits_when_creatingContext_then_acceptsThem() {
    var context =
        new RequestContext(
            "r".repeat(64),
            "m".repeat(32),
            "/".repeat(2_048),
            "u".repeat(128),
            "t".repeat(128),
            "d".repeat(16));
    assertEquals(2_048, context.path().length());
    assertNull(new RequestContext("r", "GET", "/", null, null, "dev").userId());
  }

  @Test
  void given_blankOrOversizedField_when_creatingContext_then_namesTheField() {
    assertRejected(
        "requestId exceeds 64 characters", "r".repeat(65), "GET", "/", null, null, "dev");
    assertRejected("requestId is required", " ", "GET", "/", null, null, "dev");
    assertRejected("method exceeds 32 characters", "r", "m".repeat(33), "/", null, null, "dev");
    assertRejected("method is required", "r", "", "/", null, null, "dev");
    assertRejected(
        "path exceeds 2048 characters", "r", "GET", "/".repeat(2_049), null, null, "dev");
    assertRejected("path is required", "r", "GET", " ", null, null, "dev");
    assertRejected("userId exceeds 128 characters", "r", "GET", "/", "u".repeat(129), null, "dev");
    assertRejected("userId is required", "r", "GET", "/", " ", null, "dev");
    assertRejected("tokenId exceeds 128 characters", "r", "GET", "/", null, "t".repeat(129), "dev");
    assertRejected("tokenId is required", "r", "GET", "/", null, " ", "dev");
    assertRejected("mode exceeds 16 characters", "r", "GET", "/", null, null, "d".repeat(17));
    assertRejected("mode is required", "r", "GET", "/", null, null, " ");
  }

  private static void assertRejected(
      String message,
      String requestId,
      String method,
      String path,
      String userId,
      String tokenId,
      String mode) {
    var exception =
        assertThrows(
            IllegalArgumentException.class,
            () -> new RequestContext(requestId, method, path, userId, tokenId, mode));
    assertEquals(message, exception.getMessage());
  }
}
