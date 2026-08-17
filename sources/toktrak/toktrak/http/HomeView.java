package toktrak.http;

import io.jstach.jstache.JStache;
import java.util.Objects;

@JStache(path = "home.mustache")
public record HomeView(BaseView base, SessionState sessionState) {
  public HomeView {
    Objects.requireNonNull(base, "base");
    Objects.requireNonNull(sessionState, "sessionState");
  }

  public boolean signedIn() {
    return sessionState == SessionState.SIGNED_IN;
  }

  public enum SessionState {
    SIGNED_IN,
    SIGNED_OUT
  }
}
