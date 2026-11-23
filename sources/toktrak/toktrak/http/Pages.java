package toktrak.http;

import io.jstach.jstachio.Template;
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import toktrak.health.HealthState;
import toktrak.http.BaseView.Currency;
import toktrak.http.BaseView.CurrencySwitch;
import toktrak.http.BaseView.CurrentPage;
import toktrak.http.BaseView.RuntimeMode;
import toktrak.projection.Projection;
import toktrak.projection.Projection.User;
import toktrak.projection.Projection.UserKey;
import toktrak.usage.UsageProjection.Row;
import toktrak.usage.UsageUpload.Report;

/// Renders the signed-in dashboard pages from a projection, independent of HTTP, so tests can check
/// page content without starting a server.
public final class Pages {
  private Pages() {}

  /// The page frame: asset URLs, runtime mode, navigation and the currency switch.
  public static BaseView base(
      Assets assets,
      RuntimeMode runtimeMode,
      String version,
      String title,
      CurrentPage currentPage,
      CurrencySwitch currencyControl) {
    Objects.requireNonNull(assets, "assets");
    Objects.requireNonNull(title, "title");
    Objects.requireNonNull(currentPage, "currentPage");
    Objects.requireNonNull(currencyControl, "currencyControl");
    return new BaseView(
        title,
        assets.publicUrl("main.css"),
        assets.publicUrl("datastar.js"),
        assets.publicUrl("dashboard.js"),
        assets.publicUrl("favicon.svg"),
        assets.publicUrl("logo-wordmark.svg"),
        assets.publicUrl("logo-wordmark-dark.svg"),
        assets.publicUrl("logo-lockup.svg"),
        assets.publicUrl("logo-lockup-dark.svg"),
        runtimeMode,
        currentPage,
        currencyControl,
        version);
  }

  /// A dashboard page frame whose currency switch returns to `path` in the other currency.
  public static BaseView dashboardBase(
      Assets assets,
      RuntimeMode runtimeMode,
      String version,
      String title,
      String path,
      CurrentPage currentPage,
      DashboardCurrency currency) {
    Objects.requireNonNull(path, "path");
    Objects.requireNonNull(currency, "currency");
    assert path.equals("/")
        || path.equals("/scope")
        || (path.startsWith(UsageChartsFactory.PATH + "?") && path.endsWith("&"));
    DashboardCurrency alternative =
        currency == DashboardCurrency.USD ? DashboardCurrency.EUR : DashboardCurrency.USD;
    String separator = path.endsWith("&") ? "" : "?";
    return base(
        assets,
        runtimeMode,
        version,
        title,
        currentPage,
        CurrencySwitch.enabled(
            Currency.from(currency),
            path + separator + "currency=" + alternative,
            Currency.from(alternative)));
  }

  /// The visualizations path for `range`, ready for one more query field.
  public static String visualizationsPath(UsageRange range) {
    return UsageChartsFactory.PATH + "?" + Objects.requireNonNull(range, "range").query() + "&";
  }

  /// EUR needs an exchange rate; without one, pages fall back to canonical USD.
  public static DashboardCurrency effectiveCurrency(
      Projection projection, DashboardCurrency currency) {
    Objects.requireNonNull(projection, "projection");
    Objects.requireNonNull(currency, "currency");
    return currency == DashboardCurrency.EUR && projection.fxRate().isEmpty()
        ? DashboardCurrency.USD
        : currency;
  }

  public static byte[] overview(
      Projection projection,
      HealthState health,
      BaseView base,
      String signedInName,
      DashboardCurrency currency,
      Instant now) {
    Objects.requireNonNull(projection, "projection");
    var view =
        new OverviewView(
            base,
            projection.revision(),
            signedInName,
            DashboardFactory.create(projection, health, currency, now));
    return render(
        OverviewViewRenderer.of(),
        view,
        "overview.mustache",
        "OverviewView",
        "OverviewViewRenderer");
  }

  /// The range that visualization query `fields` select; absent fields select the month of the
  /// latest usage on or before `today`.
  public static UsageRange visualizationsRange(
      Projection projection, Map<String, String> fields, LocalDate today) {
    Objects.requireNonNull(projection, "projection");
    Objects.requireNonNull(today, "today");
    List<Row> daily = projection.usageRows(Report.DAILY);
    LocalDate earliest =
        daily.stream()
            .map(row -> LocalDate.parse(row.firstKey()))
            .min(LocalDate::compareTo)
            .orElse(today);
    // Future-dated uploads must not move the default month or the all-time range end.
    LocalDate latest =
        daily.stream()
            .map(row -> LocalDate.parse(row.firstKey()))
            .filter(date -> !date.isAfter(today))
            .max(LocalDate::compareTo)
            .orElse(today);
    return UsageRange.parse(fields, earliest.isAfter(latest) ? latest : earliest, latest);
  }

  public static byte[] visualizations(
      Projection projection,
      HealthState health,
      BaseView base,
      String signedInName,
      UsageRange range,
      DashboardCurrency currency,
      Instant now) {
    Objects.requireNonNull(projection, "projection");
    Objects.requireNonNull(range, "range");
    Objects.requireNonNull(now, "now");
    List<Row> daily = projection.usageRows(Report.DAILY);
    LocalDate today = now.atZone(ZoneOffset.UTC).toLocalDate();
    var view =
        new VisualizationsView(
            base,
            projection.revision(),
            signedInName,
            DashboardFactory.create(projection, health, currency, now),
            UsageChartsFactory.create(
                daily, range, today, currency, projection.fxRate().orElse(null)),
            insights(projection, daily, range, today, currency));
    return render(
        VisualizationsViewRenderer.of(),
        view,
        "visualizations.mustache",
        "VisualizationsView",
        "VisualizationsViewRenderer");
  }

  private static UsageInsights insights(
      Projection projection,
      List<Row> daily,
      UsageRange range,
      LocalDate today,
      DashboardCurrency currency) {
    var users = new HashMap<UserKey, User>();
    for (var user : projection.users()) users.put(user.key(), user);
    var zones = new HashMap<UserKey, ZoneId>();
    for (var ingestion : projection.ingestion()) {
      zones.put(ingestion.owner(), UsageInsightsFactory.zone(ingestion.clientTimeZone()));
    }
    return UsageInsightsFactory.create(
        daily,
        projection.usageRows(Report.SESSION),
        users,
        zones,
        range,
        today,
        currency,
        projection.fxRate().orElse(null));
  }

  static <T> byte[] render(
      Template.EncodedTemplate<T> renderer,
      T model,
      String templateName,
      String modelName,
      String rendererName) {
    try {
      return HttpSupport.renderEncoded(renderer, model);
    } catch (HttpSupport.EncodedHtmlTooLargeException exception) {
      throw new RenderFailure(templateName, modelName, rendererName, "output_limit", exception);
    } catch (IOException exception) {
      throw new RenderFailure(templateName, modelName, rendererName, "renderer_failure", exception);
    }
  }
}
