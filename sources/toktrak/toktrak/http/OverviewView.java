package toktrak.http;

import io.jstach.jstache.JStache;
import java.util.Objects;

@JStache(path = "overview.mustache")
record OverviewView(
    BaseView base, long revision, String signedInName, DashboardSnapshot dashboard) {
  OverviewView {
    Objects.requireNonNull(base, "base");
    Objects.requireNonNull(signedInName, "signedInName");
    Objects.requireNonNull(dashboard, "dashboard");
  }
}
