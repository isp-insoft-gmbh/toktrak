package toktrak.http;

import java.util.List;
import java.util.Objects;
import toktrak.http.UsageCharts.ColumnChart;
import toktrak.http.UsageCharts.Kpi;
import toktrak.http.UsageCharts.LegendItem;
import toktrak.http.UsageCharts.SvgLabel;
import toktrak.http.UsageCharts.SvgRect;
import toktrak.http.UsageCharts.SvgTick;

/// Range-scoped charts beyond daily cost by tool. Daily figures reconcile with the range total;
/// session figures reconcile with their own session total and carry a coverage note.
record UsageInsights(
    LineChart cumulative,
    ColumnChart activePeople,
    BarList personDaySizes,
    LineChart personDayConcentration,
    PeopleTable people,
    StackedRows modelMix,
    BarList tokenPrice,
    LineChart cacheReuse,
    SessionInsights sessions) {
  UsageInsights {
    Objects.requireNonNull(cumulative, "cumulative");
    Objects.requireNonNull(activePeople, "activePeople");
    Objects.requireNonNull(personDaySizes, "personDaySizes");
    Objects.requireNonNull(personDayConcentration, "personDayConcentration");
    Objects.requireNonNull(people, "people");
    Objects.requireNonNull(modelMix, "modelMix");
    Objects.requireNonNull(tokenPrice, "tokenPrice");
    Objects.requireNonNull(cacheReuse, "cacheReuse");
    Objects.requireNonNull(sessions, "sessions");
  }

  /// One or more lines on shared axes; \`lineClass\` selects stroke styling in CSS.
  record LineChart(
      String width,
      String height,
      List<SvgTick> yTicks,
      List<SvgLabel> xLabels,
      List<Line> lines,
      List<Kpi> stats) {
    LineChart {
      yTicks = List.copyOf(yTicks);
      xLabels = List.copyOf(xLabels);
      lines = List.copyOf(lines);
      stats = List.copyOf(stats);
    }
  }

  record Line(String path, String label, String lineClass) {
    Line {
      if (!lineClass.matches("chart-line series-[1-6]|chart-reference")) {
        throw new IllegalArgumentException("lineClass is invalid");
      }
    }
  }

  /// Horizontal rows with one or two proportional meters each.
  record BarList(List<String> headings, List<BarRow> rows, List<Kpi> stats) {
    BarList {
      headings = List.copyOf(headings);
      rows = List.copyOf(rows);
      stats = List.copyOf(stats);
    }
  }

  record BarRow(
      String label, String firstWidth, String firstValue, List<Meter> second, String usd) {
    BarRow {
      second = List.copyOf(second);
      assert second.size() <= 1;
    }
  }

  record Meter(String width, String value) {}

  record PeopleTable(List<PersonRow> rows, List<Kpi> stats) {
    PeopleTable {
      rows = List.copyOf(rows);
      stats = List.copyOf(stats);
    }
  }

  record PersonRow(
      String name,
      List<SvgRect> spark,
      List<SparkLine> grid,
      String total,
      String usd,
      String share,
      String activeDays,
      String typical,
      String perDay) {
    PersonRow {
      spark = List.copyOf(spark);
      grid = List.copyOf(grid);
    }
  }

  /// A reference line in a person's trend, such as $10 on the shared log scale.
  record SparkLine(String y, String label) {}

  /// Rows of 100% stacked segments, one row per period.
  record StackedRows(
      String periodName, List<StackedRow> rows, List<LegendItem> legend, List<Kpi> stats) {
    StackedRows {
      rows = List.copyOf(rows);
      legend = List.copyOf(legend);
      stats = List.copyOf(stats);
    }
  }

  record StackedRow(String period, String usd, List<Segment> segments) {
    StackedRow {
      segments = List.copyOf(segments);
    }
  }

  record Segment(String x, String width, String seriesClass, String title, String usd) {
    Segment {
      if (!seriesClass.matches("series-[1-6]")) {
        throw new IllegalArgumentException("seriesClass is invalid");
      }
    }
  }

  record SessionInsights(
      String coverage,
      toktrak.charts.Coverage.Level level,
      List<Kpi> stats,
      BarList sizes,
      LineChart concentration,
      Heatmap rhythm) {
    SessionInsights {
      Objects.requireNonNull(coverage, "coverage");
      Objects.requireNonNull(level, "level");
      stats = List.copyOf(stats);
    }

    boolean warning() {
      return level == toktrak.charts.Coverage.Level.WARNING;
    }
  }

  record Heatmap(
      String width,
      String height,
      List<HeatCell> cells,
      List<SvgLabel> rowLabels,
      List<SvgLabel> columnLabels,
      List<Kpi> stats) {
    Heatmap {
      cells = List.copyOf(cells);
      rowLabels = List.copyOf(rowLabels);
      columnLabels = List.copyOf(columnLabels);
      stats = List.copyOf(stats);
    }
  }

  record HeatCell(String x, String y, String width, String height, String opacity, String title) {}
}
