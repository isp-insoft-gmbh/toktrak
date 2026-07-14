package toktrak.http;

import java.util.Map;
import toktrak.json.Json;

public record ApiError(String code, String message, String requestId) {
  public String json() {
    return Json.write(Map.of("error", Map.of("code", code, "message", message, "requestId", requestId)));
  }
}
