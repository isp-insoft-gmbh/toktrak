package toktrak.http;

import java.util.List;
import java.util.Objects;

record DashboardSnapshot(
    String period,
    String cost,
    String tokens,
    int activeUsers,
    String averageCost,
    String fx,
    DashboardHealth health,
    ReportCompleteness reportCompleteness,
    List<DashboardUserRow> leaderboard,
    List<DashboardIngestionRow> ingestion,
    List<DashboardMetricRow> tokenTypes,
    List<DashboardMetricRow> models,
    List<DashboardMetricRow> sources,
    List<DashboardMetricRow> rhythm,
    List<DashboardTrendRow> trends,
    List<DashboardUserRow> userStreams,
    List<DashboardSpikeRow> spikes,
    List<DashboardSessionRow> sessions,
    List<DashboardBlockRow> blocks) {
  DashboardSnapshot {
    Objects.requireNonNull(period, "period");
    Objects.requireNonNull(cost, "cost");
    Objects.requireNonNull(tokens, "tokens");
    Objects.requireNonNull(averageCost, "averageCost");
    Objects.requireNonNull(fx, "fx");
    Objects.requireNonNull(health, "health");
    Objects.requireNonNull(reportCompleteness, "reportCompleteness");
    leaderboard = List.copyOf(leaderboard);
    ingestion = List.copyOf(ingestion);
    tokenTypes = List.copyOf(tokenTypes);
    models = List.copyOf(models);
    sources = List.copyOf(sources);
    rhythm = List.copyOf(rhythm);
    trends = List.copyOf(trends);
    userStreams = List.copyOf(userStreams);
    spikes = List.copyOf(spikes);
    sessions = List.copyOf(sessions);
    blocks = List.copyOf(blocks);
  }

  boolean degraded() {
    return health == DashboardHealth.DEGRADED;
  }

  boolean partial() {
    return reportCompleteness == ReportCompleteness.PARTIAL;
  }

  enum DashboardHealth {
    HEALTHY,
    DEGRADED
  }

  enum ReportCompleteness {
    COMPLETE,
    PARTIAL
  }

  record DashboardMetricRow(String label, String value, String detail, String bar) {}

  record DashboardTrendRow(String date, String cost, String tokens, String bar) {}

  record DashboardUserRow(
      int rank,
      String initials,
      String name,
      String colorClass,
      String cost,
      String tokens,
      String bar) {
    DashboardUserRow {
      if (!colorClass.matches("color-[1-8]")) {
        throw new IllegalArgumentException("colorClass is invalid");
      }
    }
  }

  record DashboardIngestionRow(
      String initials,
      String name,
      String colorClass,
      String status,
      String statusClass,
      String generatedAt,
      String detail) {
    DashboardIngestionRow {
      if (!colorClass.matches("color-[1-8]") || !statusClass.matches("status-(?:ok|warn)")) {
        throw new IllegalArgumentException("ingestion class is invalid");
      }
    }
  }

  record DashboardSpikeRow(String date, String user, String cost, String tokens) {}

  record DashboardSessionRow(
      String user,
      String source,
      String project,
      String models,
      String cost,
      String tokens,
      String lastActivity) {}

  record DashboardBlockRow(
      String start, String models, String cost, String tokens, String entries) {}
}
