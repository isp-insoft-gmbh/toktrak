package toktrak.http;

import io.jstach.jstache.JStache;
import java.util.Objects;

@JStache(path = "created-token.mustache")
public record CreatedTokenView(BaseView base, String plaintext) {
  public CreatedTokenView {
    Objects.requireNonNull(base, "base");
    Objects.requireNonNull(plaintext, "plaintext");
    if (!plaintext.matches("tt_[A-Za-z0-9_-]{43}")) {
      throw new IllegalArgumentException("plaintext is invalid");
    }
  }
}
