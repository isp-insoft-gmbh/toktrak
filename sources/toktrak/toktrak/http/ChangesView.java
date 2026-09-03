package toktrak.http;

import io.jstach.jstache.JStache;
import java.util.Objects;

@JStache(path = "changes.mustache")
public record ChangesView(BaseView base, Changelog changelog) {
  public ChangesView {
    Objects.requireNonNull(base, "base");
    Objects.requireNonNull(changelog, "changelog");
  }
}
