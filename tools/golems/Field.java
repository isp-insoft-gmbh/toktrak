/// The frontmatter fields a golem may declare.
///
/// One declaration feeds three consumers: the parser accepts exactly these keys, the help text
/// describes exactly these keys, and the self-check walks exactly these keys. A field added here is
/// documented and validated by construction, which is the point: a key that parses but is
/// undocumented is a trap.
enum Field {
  SCHEDULE("schedule", "daily, or a weekday list such as [mon, thu]", "not time-driven"),
  TRIGGERS("triggers", "forge events such as [pull_request_review]", "none"),
  BRANCH("branch", "the guarded branch this golem owns", "normal mode"),
  HARNESS("harness", "pi, claude, or codex", "claude"),
  OS("os", "runner label", Golem.DEFAULT_OS),
  TIMEOUT("timeout", "agent process budget, with unit: 90s, 45m, 2h", Golem.DEFAULT_TIMEOUT),
  TURNS("turns", "Claude turn cap, positive integer", "unset"),
  MODEL("model", "model identifier for the chosen harness", Golem.DEFAULT_MODEL),
  EFFORT("effort", "effort level for the chosen harness", Golem.DEFAULT_EFFORT),
  VERIFY("verify", "command the orchestrator runs before publishing", "none");

  private final String key;
  private final String description;
  private final String fallback;

  Field(String key, String description, String fallback) {
    this.key = key;
    this.description = description;
    this.fallback = fallback;
  }

  String key() {
    return key;
  }

  /// One help line, aligned so the table reads as a table.
  String helpLine() {
    return String.format("  %-10s %-48s default: %s", key, description, fallback);
  }

  static boolean known(String key) {
    assert key != null : "a key is required";
    for (var field : values()) {
      if (field.key.equals(key)) {
        return true;
      }
    }
    return false;
  }

  /// The help text block describing every field.
  static String help() {
    var out = new StringBuilder();
    for (var field : values()) {
      out.append(field.helpLine()).append(System.lineSeparator());
    }
    return out.toString();
  }
}
