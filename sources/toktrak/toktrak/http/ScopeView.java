package toktrak.http;

import io.jstach.jstache.JStache;
import java.util.Objects;

@JStache(path = "scope.mustache")
record ScopeView(BaseView base, String signedInName) {
  ScopeView {
    Objects.requireNonNull(base, "base");
    Objects.requireNonNull(signedInName, "signedInName");
  }
}
