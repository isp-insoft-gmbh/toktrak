import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/// The single run report, rendered once and written to every available sink.
///
/// One renderer, several sinks: a local terminal, a job summary, and the collapsed block in a pull
/// request all show the same facts. That is what makes "a local run looks like a hosted run" true
/// rather than aspirational.
final class Report {
  /// Markers delimiting the orchestrator's block in a pull-request body. Everything outside them is
  /// human-owned and is never rewritten.
  static final String BEGIN = "<!-- golem:begin -->";

  static final String END = "<!-- golem:end -->";

  /// How much of the agent's closing message the report carries. The rest stays in the artifacts: a
  /// job summary is capped, and a pull request is read by people.
  private static final int NARRATIVE_LIMIT = 4000;

  private static final String REPORT_FILE = "report.md";
  private static final String RAW_RESULT_FILE = "agent-result.json";
  private static final String SUMMARY_VARIABLE = "GITHUB_STEP_SUMMARY";
  private static final int THOUSAND = 1000;

  private final String golem;
  private final Outcome outcome;
  private final Map<String, String> facts = new LinkedHashMap<>();
  private String narrative = "";

  Report(String golem, Outcome outcome) {
    assert golem != null && !golem.isBlank() : "a report belongs to a golem";
    assert outcome != null : "a report always states an outcome";
    this.golem = golem;
    this.outcome = outcome;
  }

  Report fact(String key, String value) {
    assert key != null && !key.isBlank() : "a fact needs a name";
    if (value != null && !value.isBlank()) {
      facts.put(key, value);
    }
    return this;
  }

  Report narrative(String text) {
    this.narrative = text == null ? "" : text.strip();
    return this;
  }

  /// Only a completed Claude result supplies structured usage; zeros without one are not facts.
  Report usage(Golem.Harness harness, Agent.Result agent) {
    assert harness != null && agent != null : "usage needs its harness and result";
    if (!agent.hasUsage(harness)) return fact("usage", "not reported by harness");
    return fact("turns", Integer.toString(agent.turns()))
        .fact(
            "tokens", Report.tokens(agent.inputTokens(), agent.outputTokens(), agent.cacheTokens()))
        .fact("cost", String.format(Locale.ROOT, "$%.2f (estimate)", agent.costUsd()));
  }

  /// The one line worth seeing while everything else is collapsed.
  String headline() {
    var reason = outcome.reason();
    return "golem-" + golem + " · " + outcome.label() + (reason.isBlank() ? "" : " · " + reason);
  }

  /// The full report, for operator surfaces: terminal and job summary.
  String markdown() {
    var out = new StringBuilder();
    out.append("## ")
        .append(headline())
        .append(System.lineSeparator())
        .append(System.lineSeparator());
    appendTable(out);
    if (!narrative.isEmpty()) {
      out.append(System.lineSeparator())
          .append("<details><summary>agent narrative</summary>")
          .append(System.lineSeparator())
          .append(System.lineSeparator())
          .append(truncate(narrative))
          .append(System.lineSeparator())
          .append(System.lineSeparator())
          .append("</details>")
          .append(System.lineSeparator());
    }
    return out.toString();
  }

  /// The collapsed block appended to a pull-request body.
  ///
  /// One block, last, with a summary line carrying the few facts worth seeing while collapsed. The
  /// prose above belongs to the golem and to whoever edited it afterwards.
  String pullRequestBlock() {
    var out = new StringBuilder();
    out.append(BEGIN).append(System.lineSeparator());
    out.append("<details><summary>")
        .append(headline())
        .append("</summary>")
        .append(System.lineSeparator())
        .append(System.lineSeparator());
    appendTable(out);
    out.append(System.lineSeparator()).append("</details>").append(System.lineSeparator());
    out.append(END);
    return out.toString();
  }

  /// Replaces the orchestrator's block in a body, preserving everything else.
  ///
  /// Repeated runs must not reorder, wrap, or rewrite the prose, so a previous block is cut out and
  /// a fresh one is appended at the end.
  String mergeIntoBody(String body) {
    var text = body == null ? "" : body;
    var begin = text.indexOf(BEGIN);
    if (begin >= 0) {
      var end = text.indexOf(END, begin);
      text =
          end >= 0
              ? text.substring(0, begin) + text.substring(end + END.length())
              : text.substring(0, begin);
    }
    var merged =
        text.stripTrailing()
            + System.lineSeparator()
            + System.lineSeparator()
            + pullRequestBlock()
            + System.lineSeparator();
    assert merged.indexOf(BEGIN) == merged.lastIndexOf(BEGIN)
        : "a body carries at most one golem block";
    return merged;
  }

  /// Plain text for a terminal, where a Markdown table is noise.
  String plain() {
    var out = new StringBuilder();
    facts.forEach((key, value) -> out.append(String.format("  %-16s %s%n", key, value)));
    out.append(System.lineSeparator()).append("outcome  ").append(outcome.label());
    if (!outcome.reason().isBlank()) {
      out.append("  ").append(outcome.reason());
    }
    return out.toString();
  }

  /// Appends the report to the job summary when the host provides one.
  void writeJobSummary() {
    var target = System.getenv(SUMMARY_VARIABLE);
    if (target == null || target.isBlank()) {
      return;
    }
    Text.append(Path.of(target), markdown() + System.lineSeparator());
  }

  /// Stores the report of a run that never reached its agent.
  void writeArtifacts(Run run) {
    assert run != null : "writing artifacts needs a run";
    Text.write(run.directory().resolve(REPORT_FILE), markdown());
  }

  /// Stores the report and the raw material a human may need after the fact.
  void writeArtifacts(Run run, Agent.Result agent) {
    assert agent != null : "this overload is for runs that reached the agent";
    writeArtifacts(run);
    Text.write(run.directory().resolve(RAW_RESULT_FILE), agent.raw());
  }

  /// The one-line annotation a run page shows without expanding anything.
  Optional<String> notice() {
    return Run.Surface.detect().isActions()
        ? Optional.of("::notice title=Golem::" + headline())
        : Optional.empty();
  }

  /// Renders a duration the way a person reads one.
  static String duration(Duration duration) {
    assert duration != null && !duration.isNegative() : "a duration is required";
    var seconds = duration.toSeconds();
    var minutes = seconds / 60;
    return minutes > 0 ? minutes + "m " + seconds % 60 + "s" : seconds + "s";
  }

  static String tokens(long input, long output, long cache) {
    assert input >= 0 && output >= 0 && cache >= 0 : "token counts cannot be negative";
    var parts = new ArrayList<String>();
    parts.add(compact(input) + " in");
    parts.add(compact(output) + " out");
    if (cache > 0) {
      parts.add(compact(cache) + " cache");
    }
    return String.join(" · ", parts);
  }

  private void appendTable(StringBuilder out) {
    out.append("| | |").append(System.lineSeparator());
    out.append("|---|---|").append(System.lineSeparator());
    facts.forEach(
        (key, value) ->
            out.append("| ")
                .append(key)
                .append(" | ")
                .append(value)
                .append(" |")
                .append(System.lineSeparator()));
  }

  private static String compact(long value) {
    return value >= THOUSAND
        ? String.format(Locale.ROOT, "%.1fk", value / (double) THOUSAND)
        : Long.toString(value);
  }

  private static String truncate(String text) {
    return text.length() <= NARRATIVE_LIMIT
        ? text
        : text.substring(0, NARRATIVE_LIMIT) + System.lineSeparator() + "[truncated]";
  }
}
