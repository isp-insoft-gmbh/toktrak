package toktrak.http;

import java.util.Map;
import java.util.Objects;
import toktrak.json.Json;

public record ApiError(String code, String message, String requestId) {
  private static final int CODE_CHARACTERS_MAX = 64;
  private static final int MESSAGE_CHARACTERS_MAX = 512;
  private static final int REQUEST_ID_CHARACTERS_MAX = 64;

  public ApiError {
    requireText(code, CODE_CHARACTERS_MAX, "code");
    requireText(message, MESSAGE_CHARACTERS_MAX, "message");
    requireText(requestId, REQUEST_ID_CHARACTERS_MAX, "requestId");
  }

  public String json() {
    String value =
        Json.write(
            Map.of("error", Map.of("code", code, "message", message, "requestId", requestId)));
    assert !value.isBlank();
    return value;
  }

  private static void requireText(String value, int charactersMax, String name) {
    Objects.requireNonNull(value, name);
    if (value.isBlank()) throw new IllegalArgumentException(name + " is required");
    if (value.length() > charactersMax) {
      throw new IllegalArgumentException(name + " exceeds " + charactersMax + " characters");
    }
  }
}
