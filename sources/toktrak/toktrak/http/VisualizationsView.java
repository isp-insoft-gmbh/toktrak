package toktrak.http;

import io.jstach.jstache.JStache;
import java.util.Objects;

@JStache(path = "visualizations.mustache")
record VisualizationsView(
    BaseView base, long revision, String signedInName, DashboardSnapshot dashboard) {
  VisualizationsView {
    Objects.requireNonNull(base, "base");
    Objects.requireNonNull(signedInName, "signedInName");
    Objects.requireNonNull(dashboard, "dashboard");
  }
}
