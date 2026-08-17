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
    PageLink previous,
    PageLink next,
    String platformUrl) {
  private static final int TOKENS_MAX = 100;

  public TokenListView {
    Objects.requireNonNull(base, "base");
    Objects.requireNonNull(csrf, "csrf");
    Objects.requireNonNull(tokens, "tokens");
    Objects.requireNonNull(previous, "previous");
    Objects.requireNonNull(next, "next");
    Objects.requireNonNull(platformUrl, "platformUrl");
    if (csrf.isBlank() || csrf.length() > 256) {
      throw new IllegalArgumentException("csrf is invalid");
    }
    tokens = List.copyOf(tokens);
    if (tokens.size() > TOKENS_MAX) throw new IllegalArgumentException("tokens exceed 100 rows");
    if (page < 1) throw new IllegalArgumentException("page is invalid");
    if (!platformUrl.matches("/assets/platform\\.[0-9a-f]{32}\\.js")) {
      throw new IllegalArgumentException("platformUrl is invalid");
    }
  }

  public boolean hasPrevious() {
    return previous.available();
  }

  public String previousUrl() {
    return previous.url();
  }

  public boolean hasNext() {
    return next.available();
  }

  public String nextUrl() {
    return next.url();
  }

  public record PageLink(PageLinkState state, String url) {
    public PageLink {
      Objects.requireNonNull(state, "state");
      Objects.requireNonNull(url, "url");
      if (state == PageLinkState.AVAILABLE) {
        if (!url.matches("/tokens\\?page=[1-9][0-9]*")) {
          throw new IllegalArgumentException("page link is invalid");
        }
      } else if (!url.isEmpty()) {
        throw new IllegalArgumentException("page link is disabled");
      }
    }

    public static PageLink available(String url) {
      return new PageLink(PageLinkState.AVAILABLE, url);
    }

    public static PageLink unavailable() {
      return new PageLink(PageLinkState.UNAVAILABLE, "");
    }

    public boolean available() {
      return state == PageLinkState.AVAILABLE;
    }
  }

  public enum PageLinkState {
    AVAILABLE,
    UNAVAILABLE
  }

  public record TokenRow(String label, String id, TokenState state, LastUsage lastUsage) {
    public TokenRow {
      Objects.requireNonNull(label, "label");
      Objects.requireNonNull(id, "id");
      Objects.requireNonNull(state, "state");
      Objects.requireNonNull(lastUsage, "lastUsage");
      if (label.isBlank() || label.length() > 128) {
        throw new IllegalArgumentException("label is invalid");
      }
      if (!id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
        throw new IllegalArgumentException("id is invalid");
      }
    }

    public String status() {
      return state.status();
    }

    public boolean hasLastUsed() {
      return lastUsage.present();
    }

    public String lastUsed() {
      return lastUsage.value();
    }

    public boolean active() {
      return state == TokenState.ACTIVE;
    }
  }

  public enum TokenState {
    ACTIVE("active"),
    REVOKED("revoked");

    private final String status;

    TokenState(String status) {
      this.status = status;
    }

    public String status() {
      return status;
    }
  }

  public record LastUsage(LastUsageState state, String value) {
    public LastUsage {
      Objects.requireNonNull(state, "state");
      Objects.requireNonNull(value, "value");
      if (state == LastUsageState.USED) {
        if (value.isEmpty() || value.length() > 64) {
          throw new IllegalArgumentException("lastUsage is invalid");
        }
      } else if (!value.isEmpty()) {
        throw new IllegalArgumentException("lastUsage is disabled");
      }
    }

    public static LastUsage at(String value) {
      return new LastUsage(LastUsageState.USED, value);
    }

    public static LastUsage never() {
      return new LastUsage(LastUsageState.NEVER, "");
    }

    public boolean present() {
      return state == LastUsageState.USED;
    }
  }

  public enum LastUsageState {
    USED,
    NEVER
  }
}
