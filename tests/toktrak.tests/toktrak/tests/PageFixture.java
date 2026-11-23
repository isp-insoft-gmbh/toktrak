package toktrak.tests;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import toktrak.auth.OidcClient;
import toktrak.health.HealthState;
import toktrak.http.Assets;
import toktrak.http.BaseView.CurrentPage;
import toktrak.http.BaseView.RuntimeMode;
import toktrak.http.DashboardCurrency;
import toktrak.http.Pages;
import toktrak.projection.Projection;
import toktrak.store.EventEnvelope;
import toktrak.store.EventLog;
import toktrak.store.EventTypes;
import toktrak.usage.UsageUpload;

/// Renders dashboard pages straight from stored events, the way the server does but without
/// starting it. Page checks stay fast, which matters because mutation testing reruns them for many
/// mutants. `PageFixtureTest` proves the pages match what the server serves.
final class PageFixture {
  private static final String ISSUER = "urn:toktrak:development";
  private static final String VIEWER = "viewer";

  private final Path events;
  private final Instant now;
  private final HealthState health = new HealthState();
  private final Assets assets = Assets.load();
  private Projection projection;

  private PageFixture(Path events, Instant now) {
    this.events = events;
    this.now = now;
  }

  /// A copy of `corpus` in `directory`, rendered at `clock`.
  static PageFixture corpus(Path directory, Path corpus, String clock) throws Exception {
    Files.createDirectories(directory);
    Path events = directory.resolve("events.ndjson");
    Files.copy(corpus, events);
    return new PageFixture(events, Instant.parse(clock));
  }

  /// Only the signed-in development viewer, rendered at the shared test clock.
  static PageFixture viewer(Path directory) throws Exception {
    Files.createDirectories(directory);
    Path events = directory.resolve("events.ndjson");
    var fixture = new PageFixture(events, Instant.parse(UsageFixture.CLOCK));
    fixture.append(
        EventEnvelope.create(
            EventTypes.IDENTITY_USER_AUTHENTICATED,
            fixture.now,
            VIEWER,
            Map.of(
                "issuer", ISSUER,
                "subject", VIEWER,
                "email", "viewer@development.invalid",
                "displayName", "Development Viewer",
                "color", "#a8dadc")));
    return fixture;
  }

  /// One upload by the viewer, admitted like the tracker API admits it and received now.
  void upload(List<String> dailyRows, List<String> sessionRows) throws Exception {
    uploadBody(UsageFixture.uploadBody(dailyRows, sessionRows));
  }

  /// One upload with a complete tracker request body, such as one that also holds blocks.
  void uploadBody(String body) throws Exception {
    UsageUpload upload = UsageUpload.parse(body.getBytes(StandardCharsets.UTF_8), now);
    append(
        EventEnvelope.create(
            EventTypes.USAGE_UPLOADED,
            now,
            VIEWER,
            Map.of("issuer", ISSUER, "subject", VIEWER, "upload", upload.data())));
  }

  void upload(String... dailyRows) throws Exception {
    upload(List.of(dailyRows), List.of());
  }

  /// Renders as a server whose event writes failed, such as one started with `--fail-writes`.
  void degradeWrites() {
    health.degrade("writes_failed");
  }

  String overview() {
    Projection current = projection();
    DashboardCurrency currency = DashboardCurrency.USD;
    return new String(
        Pages.overview(
            current,
            health,
            Pages.dashboardBase(
                assets,
                RuntimeMode.DEVELOPMENT,
                "dev",
                "Overview · TokTrak",
                "/",
                CurrentPage.OVERVIEW,
                currency),
            signedInName(current),
            currency,
            now),
        StandardCharsets.UTF_8);
  }

  /// The visualizations page for a query string such as `range=year&year=2026`; null selects the
  /// default range.
  String visualizations(String query) {
    Projection current = projection();
    DashboardCurrency currency = DashboardCurrency.USD;
    Map<String, String> fields = query == null ? Map.of() : OidcClient.parseForm(query);
    var range =
        Pages.visualizationsRange(current, fields, now.atZone(ZoneOffset.UTC).toLocalDate());
    return new String(
        Pages.visualizations(
            current,
            health,
            Pages.dashboardBase(
                assets,
                RuntimeMode.DEVELOPMENT,
                "dev",
                "Visualizations · TokTrak",
                Pages.visualizationsPath(range),
                CurrentPage.VISUALIZATIONS,
                currency),
            signedInName(current),
            range,
            currency,
            now),
        StandardCharsets.UTF_8);
  }

  Instant now() {
    return now;
  }

  private void append(EventEnvelope event) {
    try (EventLog log = EventLog.open(events)) {
      log.appendAndFsync(event);
    }
    projection = null;
  }

  Projection projection() {
    if (projection == null) {
      var replayed = Projection.empty();
      try (EventLog log = EventLog.open(events)) {
        log.replay(replayed::apply);
      }
      projection = replayed;
    }
    return projection;
  }

  private static String signedInName(Projection projection) {
    return projection.users().stream()
        .filter(user -> user.key().equals(new Projection.UserKey(ISSUER, VIEWER)))
        .findFirst()
        .orElseThrow()
        .displayName();
  }
}
