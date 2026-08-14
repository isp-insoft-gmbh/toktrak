package toktrak.http;

import io.jstach.jstache.JStache;
import java.util.Objects;

@JStache(path = "home.mustache")
public record HomeView(BaseView base, boolean signedIn) {
  public HomeView {
    Objects.requireNonNull(base, "base");
  }
}
