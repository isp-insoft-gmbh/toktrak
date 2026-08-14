package toktrak.http;

import java.util.Objects;

public record BaseView(String title, String stylesheetUrl, boolean development) {
  public BaseView {
    Objects.requireNonNull(title, "title");
    Objects.requireNonNull(stylesheetUrl, "stylesheetUrl");
    if (title.isBlank() || title.length() > 128) {
      throw new IllegalArgumentException("title is invalid");
    }
    if (!stylesheetUrl.matches("/assets/main\\.[0-9a-f]{32}\\.css")) {
      throw new IllegalArgumentException("stylesheetUrl is invalid");
    }
  }
}
