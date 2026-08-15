package toktrak.http;

import io.jstach.jstache.JStache;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

@JStache(path = "created-token.mustache")
public record CreatedTokenView(
    BaseView base,
    String plaintext,
    String script,
    String scriptSha256,
    String clipboardUrl,
    String platformUrl) {
  public CreatedTokenView {
    Objects.requireNonNull(base, "base");
    Objects.requireNonNull(plaintext, "plaintext");
    Objects.requireNonNull(script, "script");
    Objects.requireNonNull(scriptSha256, "scriptSha256");
    Objects.requireNonNull(clipboardUrl, "clipboardUrl");
    Objects.requireNonNull(platformUrl, "platformUrl");
    if (!plaintext.matches("tt_[A-Za-z0-9_-]{43}")) {
      throw new IllegalArgumentException("plaintext is invalid");
    }
    if (script.isBlank()
        || script.getBytes(StandardCharsets.UTF_8).length > 512 * 1024
        || !script.contains(plaintext)) {
      throw new IllegalArgumentException("script is invalid");
    }
    if (!scriptSha256.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException("scriptSha256 is invalid");
    }
    if (!clipboardUrl.matches("/assets/clipboard\\.[0-9a-f]{32}\\.js")) {
      throw new IllegalArgumentException("clipboardUrl is invalid");
    }
    if (!platformUrl.matches("/assets/platform\\.[0-9a-f]{32}\\.js")) {
      throw new IllegalArgumentException("platformUrl is invalid");
    }
  }
}
