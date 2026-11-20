package toktrak.http;

import java.util.List;
import java.util.Objects;

/// Server-rendered chart models for one usage range. Geometry is precomputed so templates only
/// place escaped attribute values; colors come from CSS series classes.
record UsageCharts(
    String rangeLabel,
    String rangeFrom,
    String rangeTo,
    String calendarDays,
    List<RangeOption> rangeOptions,
    List<Kpi> kpis,
    ColumnChart dailyCost) {
  UsageCharts {
    Objects.requireNonNull(rangeLabel, "rangeLabel");
    Objects.requireNonNull(rangeFrom, "rangeFrom");
    Objects.requireNonNull(rangeTo, "rangeTo");
    Objects.requireNonNull(calendarDays, "calendarDays");
    rangeOptions = List.copyOf(rangeOptions);
    kpis = List.copyOf(kpis);
    Objects.requireNonNull(dailyCost, "dailyCost");
  }

  enum Selection {
    CURRENT,
    OTHER
  }

  record RangeOption(String label, String url, Selection selection) {
    RangeOption {
      Objects.requireNonNull(label, "label");
      Objects.requireNonNull(url, "url");
      Objects.requireNonNull(selection, "selection");
    }

    boolean current() {
      return selection == Selection.CURRENT;
    }
  }

  record Kpi(String label, String value) {}

  /// Stacked columns over time buckets with optional trailing-average line.
  record ColumnChart(
      String width,
      String height,
      String bucketName,
      String description,
      List<SvgRect> columns,
      List<SvgTick> yTicks,
      List<SvgLabel> xLabels,
      List<SvgPath> averages,
      List<LegendItem> legend,
      List<Kpi> stats,
      List<DataRow> rows) {
    ColumnChart {
      columns = List.copyOf(columns);
      yTicks = List.copyOf(yTicks);
      xLabels = List.copyOf(xLabels);
      averages = List.copyOf(averages);
      legend = List.copyOf(legend);
      stats = List.copyOf(stats);
      rows = List.copyOf(rows);
    }
  }

  record SvgRect(
      String x, String y, String width, String height, String seriesClass, String title) {
    SvgRect {
      if (!seriesClass.matches("series-[1-6]")) {
        throw new IllegalArgumentException("seriesClass is invalid");
      }
    }
  }

  record SvgTick(String y, String labelY, String x1, String x2, String labelX, String label) {}

  record SvgLabel(String x, String y, String label) {}

  record SvgPath(String path, String label) {}

  record LegendItem(String seriesClass, String label, String value) {
    LegendItem {
      if (!seriesClass.matches("series-[1-6]")) {
        throw new IllegalArgumentException("seriesClass is invalid");
      }
    }
  }

  record DataRow(String bucket, String value) {}
}
