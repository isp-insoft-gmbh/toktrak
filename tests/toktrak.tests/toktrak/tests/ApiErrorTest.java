package toktrak.tests;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import toktrak.http.ApiError;

final class ApiErrorTest {
  @Test
  void given_fieldsAtTheirLimits_when_creatingError_then_serializesThem() {
    var error = new ApiError("c".repeat(64), "m".repeat(512), "r".repeat(64));
    assertTrue(error.json().contains("\"code\":\"" + "c".repeat(64) + "\""), error.json());
  }

  @Test
  void given_blankOrOversizedField_when_creatingError_then_namesTheField() {
    assertRejected("code exceeds 64 characters", "c".repeat(65), "m", "r");
    assertRejected("code is required", " ", "m", "r");
    assertRejected("message exceeds 512 characters", "c", "m".repeat(513), "r");
    assertRejected("message is required", "c", "", "r");
    assertRejected("requestId exceeds 64 characters", "c", "m", "r".repeat(65));
    assertRejected("requestId is required", "c", "m", " ");
  }

  private static void assertRejected(String message, String code, String text, String requestId) {
    var exception =
        assertThrows(IllegalArgumentException.class, () -> new ApiError(code, text, requestId));
    assertEquals(message, exception.getMessage());
  }
}
