package toktrak.http;

import io.jstach.jstache.JStache;
import java.util.Objects;

@JStache(path = "created-token.mustache")
public record CreatedTokenView(BaseView base, String plaintext, String clipboardUrl) {
  public CreatedTokenView {
    Objects.requireNonNull(base, "base");
    Objects.requireNonNull(plaintext, "plaintext");
    Objects.requireNonNull(clipboardUrl, "clipboardUrl");
    if (!plaintext.matches("tt_[A-Za-z0-9_-]{43}")) {
      throw new IllegalArgumentException("plaintext is invalid");
    }
    if (!clipboardUrl.matches("/assets/clipboard\\.[0-9a-f]{32}\\.js")) {
      throw new IllegalArgumentException("clipboardUrl is invalid");
    }
  }
}
