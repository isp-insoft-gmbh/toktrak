package toktrak.http;

import io.jstach.jstache.JStache;
import java.util.List;
import java.util.Objects;

@JStache(path = "tokens.mustache")
public record TokenListView(
    BaseView base,
    String csrf,
    List<TokenRow> tokens,
    int page,
    boolean hasPrevious,
    String previousUrl,
    boolean hasNext,
    String nextUrl,
    String platformUrl) {
  private static final int TOKENS_MAX = 100;

  public TokenListView {
    Objects.requireNonNull(base, "base");
    Objects.requireNonNull(csrf, "csrf");
    Objects.requireNonNull(tokens, "tokens");
    Objects.requireNonNull(previousUrl, "previousUrl");
    Objects.requireNonNull(nextUrl, "nextUrl");
    Objects.requireNonNull(platformUrl, "platformUrl");
    if (csrf.isBlank() || csrf.length() > 256)
      throw new IllegalArgumentException("csrf is invalid");
    tokens = List.copyOf(tokens);
    if (tokens.size() > TOKENS_MAX) throw new IllegalArgumentException("tokens exceed 100 rows");
    if (page < 1) throw new IllegalArgumentException("page is invalid");
    requirePageUrl(previousUrl, hasPrevious, "previousUrl");
    requirePageUrl(nextUrl, hasNext, "nextUrl");
    if (!platformUrl.matches("/assets/platform\\.[0-9a-f]{32}\\.js")) {
      throw new IllegalArgumentException("platformUrl is invalid");
    }
  }

  private static void requirePageUrl(String value, boolean present, String name) {
    if (present != !value.isEmpty()
        || (!value.isEmpty() && !value.matches("/tokens\\?page=[1-9][0-9]*"))) {
      throw new IllegalArgumentException(name + " is invalid");
    }
  }

  public record TokenRow(
      String label,
      String id,
      String status,
      boolean hasLastUsed,
      String lastUsed,
      boolean active) {
    public TokenRow {
      Objects.requireNonNull(label, "label");
      Objects.requireNonNull(id, "id");
      Objects.requireNonNull(status, "status");
      Objects.requireNonNull(lastUsed, "lastUsed");
      if (label.isBlank() || label.length() > 128) {
        throw new IllegalArgumentException("label is invalid");
      }
      if (!id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
        throw new IllegalArgumentException("id is invalid");
      }
      if (!status.equals(active ? "active" : "revoked")) {
        throw new IllegalArgumentException("status is invalid");
      }
      if (hasLastUsed != !lastUsed.isEmpty() || lastUsed.length() > 64) {
        throw new IllegalArgumentException("lastUsed is invalid");
      }
    }
  }
}
