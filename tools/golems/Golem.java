import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Properties;
import java.util.Set;

/// A named maintenance task: strict frontmatter plus Markdown instructions for one agent.
///
/// {@link Golems} discovers these files by name under `.golems/`; `_golems.md` supplies shared
/// policy but is never itself selected. Define the wake-up, harness, budget, checks, and optional
/// PR branch in the file rather than in runner-specific CLI arguments. For example,
/// `.golems/perf.md` could contain:
///
/// ```markdown
/// ---
/// schedule: [mon, thu]
/// harness: pi
/// model: openai-codex/gpt-6.1-sol
/// effort: high
/// branch: golem/perf
/// os: ubuntu-26.04
/// timeout: 45m
/// verify: mise run verify
/// ---
/// # Performance maintenance
///
/// Inspect measurements; make one reviewable improvement or report no change.
/// ```
///
/// This runs on matching **UTC** schedule ticks only when the host workflow is enabled. `schedule:
/// daily` is also valid; omitting `schedule` makes the task not time-driven. `triggers:
/// [pull_request_review]` selects that forge event independently of the schedule, but declaring a
/// trigger does **not** register the event in `.github/workflows/golem.yml`. A task with neither
/// activation is manual-only: select it with `workflow_dispatch` plus `--golem <name>`. `timeout`
/// bounds the agent process, not preparation or verification; verification has a separate 30-minute
/// limit, and the hosted job has its own timeout. `turns` is currently enforced only for Claude; Pi
/// and Codex have the agent time budget but no turn cap.
///
/// | Field | Meaning when omitted |
/// | --- | --- |
/// | `harness` | Claude |
/// | `os` | `ubuntu-latest` |
/// | `model`, `effort` | `sonnet`, `medium` |
/// | `timeout` | `30m` |
/// | `turns`, `verify` | No Claude turn cap, no repository check |
/// | `branch` | Normal mode: no PR publication, no detached candidate |
///
/// The model default is Claude-specific; declare a supported model when choosing Pi or Codex.
/// `turns` must be positive; `timeout` requires a unit (`90s`, `45m`, `2h`). Refer to the live
/// field list in {@link Field} or `Golems --help` when adding a task. A declared `verify` command
/// is a whitespace-separated argument list, not a shell script; the orchestrator runs it for clean,
/// committed changes before publication.
///
/// The frontmatter block is read by {@link java.util.Properties}, so key and value splitting,
/// escaping, and encoding are delegated to the JDK rather than a custom parser. Before loading,
/// validation rejects blank lines, comments, duplicate or unknown keys, and line continuations that
/// Properties would silently accept. Strictness matters when nobody is watching: a misread
/// declaration could run on the wrong day, host, or budget.
///
/// ### API note
///
/// The task body is a prompt, not a trusted source of permissions. The engine protocol and shared
/// policy still limit what the agent may edit or publish.
///
/// @see Golems
/// @see Field
record Golem(
    String name,
    Path file,
    Schedule schedule,
    List<String> triggers,
    Optional<String> branch,
    Harness harness,
    String os,
    Duration timeout,
    OptionalInt turns,
    String model,
    String effort,
    Optional<String> verify,
    String body) {
  static final String DEFAULT_OS = "ubuntu-latest";
  static final String DEFAULT_TIMEOUT = "30m";
  static final String DEFAULT_MODEL = "sonnet";

  enum Harness {
    PI,
    CLAUDE,
    CODEX;

    static Harness of(String text) {
      if (text == null) return CLAUDE;
      try {
        return valueOf(text.toUpperCase(Locale.ROOT));
      } catch (IllegalArgumentException invalid) {
        throw new IllegalArgumentException("unknown harness: " + text);
      }
    }

    String cli() {
      return name().toLowerCase(Locale.ROOT);
    }
  }

  static final String DEFAULT_EFFORT = "medium";

  /// Directory holding the golems of a repository.
  static final String DIRECTORY = ".golems";

  /// Shared repository policy, which is not a golem and must never be selected as one.
  private static final String SHARED_POLICY_PREFIX = "_";

  private static final String MARKDOWN_SUFFIX = ".md";

  /// The repository's own policy file, appended to every system prompt.
  ///
  /// Its name carries the prefix that keeps it out of discovery, so the rule and the file cannot
  /// drift apart.
  private static final String SHARED_POLICY = SHARED_POLICY_PREFIX + "golems" + MARKDOWN_SUFFIX;

  private static final String FENCE = "---";
  private static final String DAILY = "daily";

  Golem {
    assert name != null && !name.isBlank() : "a golem needs a name";
    assert body != null && !body.isBlank() : "a golem needs instructions";
    assert timeout != null && !timeout.isNegative() && !timeout.isZero()
        : "a golem needs a positive budget";
  }

  /// When a golem wakes up on a timer. Sealed so every activation is handled explicitly and a new
  /// kind cannot be forgotten at a call site.
  sealed interface Schedule {
    /// The golem is not time-driven. It may still be event-driven, or manual.
    record Never() implements Schedule {}

    /// Every nightly tick.
    record Daily() implements Schedule {}

    /// Only these UTC weekdays.
    record Weekdays(Set<DayOfWeek> days) implements Schedule {
      public Weekdays {
        assert days != null && !days.isEmpty() : "a weekday schedule needs at least one day";
      }
    }

    default boolean matches(DayOfWeek day) {
      assert day != null : "a day is required";
      return switch (this) {
        case Never _ -> false;
        case Daily _ -> true;
        case Weekdays weekdays -> weekdays.days().contains(day);
      };
    }

    default boolean declared() {
      return switch (this) {
        case Never _ -> false;
        case Daily _, Weekdays _ -> true;
      };
    }

    default String describe() {
      return switch (this) {
        case Never _ -> "none";
        case Daily _ -> DAILY;
        case Weekdays weekdays ->
            weekdays.days().stream().map(Golem::shortDay).sorted().toList().toString();
      };
    }
  }

  /// Reads every golem of a repository, ordered by name so selection and matrix emission are
  /// reproducible.
  static List<Golem> discover(Path root) {
    assert root != null : "a repository root is required";
    var directory = root.resolve(DIRECTORY);
    if (!Files.isDirectory(directory)) {
      throw new IllegalStateException("no " + DIRECTORY + " directory in " + root.toAbsolutePath());
    }
    try (var entries = Files.list(directory)) {
      var golems =
          entries
              .filter(path -> path.getFileName().toString().endsWith(MARKDOWN_SUFFIX))
              .filter(path -> !path.getFileName().toString().startsWith(SHARED_POLICY_PREFIX))
              .sorted(Comparator.comparing(path -> path.getFileName().toString()))
              .map(Golem::read)
              .toList();
      assert golems.stream().map(Golem::name).distinct().count() == golems.size()
          : "golem names are file names";
      return golems;
    } catch (IOException failure) {
      throw new UncheckedIOException("cannot list " + directory, failure);
    }
  }

  /// Where a repository states the behavior all of its golems share.
  static Path sharedPolicy(Path root) {
    assert root != null : "a repository root is required";
    return root.resolve(DIRECTORY).resolve(SHARED_POLICY);
  }

  static Golem require(Path root, String name) {
    assert root != null && name != null : "a repository root and a name are required";
    return discover(root).stream()
        .filter(golem -> golem.name().equals(name))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("unknown golem: " + name));
  }

  /// Parses one golem file: a fenced frontmatter block, then the task body.
  ///
  /// @param file task Markdown path; its basename becomes the golem name
  /// @return validated task with defaults filled in
  /// @throws IllegalArgumentException if frontmatter or instructions are invalid
  static Golem read(Path file) {
    assert file != null : "a file is required";
    var lines = Text.read(file).split("\r?\n", -1);
    if (lines.length == 0 || !lines[0].strip().equals(FENCE)) {
      throw reject(file, "file must start with a " + FENCE + " frontmatter block");
    }
    var end = closingFence(file, lines);
    var declared = validate(file, lines, end);
    var properties = load(file, String.join(System.lineSeparator(), declared));
    var body =
        String.join(System.lineSeparator(), List.of(lines).subList(end + 1, lines.length)).strip();
    if (body.isEmpty()) {
      throw reject(file, "task instructions are empty");
    }

    var name = file.getFileName().toString().replaceFirst("\\" + MARKDOWN_SUFFIX + "$", "");
    var golem =
        new Golem(
            name,
            file,
            parseSchedule(file, value(properties, Field.SCHEDULE)),
            parseList(value(properties, Field.TRIGGERS)),
            Optional.ofNullable(value(properties, Field.BRANCH)),
            Harness.of(value(properties, Field.HARNESS)),
            Optional.ofNullable(value(properties, Field.OS)).orElse(DEFAULT_OS),
            parseTimeout(
                file,
                Optional.ofNullable(value(properties, Field.TIMEOUT)).orElse(DEFAULT_TIMEOUT)),
            parseTurns(file, value(properties, Field.TURNS)),
            Optional.ofNullable(value(properties, Field.MODEL)).orElse(DEFAULT_MODEL),
            Optional.ofNullable(value(properties, Field.EFFORT)).orElse(DEFAULT_EFFORT),
            Optional.ofNullable(value(properties, Field.VERIFY)),
            body);
    assert golem.name().equals(name) : "the golem is named after its file";
    return golem;
  }

  /// Whether this golem should run for the given wake-up.
  ///
  /// Schedule and triggers are orthogonal activations: the presence of a schedule is the time
  /// activation, and triggers name forge events only. Neither is listed inside the other; manual
  /// dispatch selection is handled by {@link Golems}, not this method.
  ///
  /// {@snippet lang="java" :
  /// var task = Golem.require(Path.of("."), "perf");
  /// var monday = ZonedDateTime.parse("2026-10-05T09:17:00Z");
  /// boolean wakesToday = task.due("schedule", monday);
  /// }
  ///
  /// @param event `schedule` or a forge event name
  /// @param tick instant whose UTC day the caller selected
  /// @return whether the declaration matches this wake-up
  boolean due(String event, ZonedDateTime tick) {
    assert event != null && !event.isBlank() : "an event is required";
    assert tick != null : "a tick is required";
    return Event.SCHEDULE.is(event)
        ? schedule.matches(tick.getDayOfWeek())
        : triggers.contains(event);
  }

  /// Why this golem is or is not due, printed for every golem so a skipped golem is never a silent
  /// one.
  String reason(String event, ZonedDateTime tick) {
    assert event != null && tick != null : "an event and a tick are required";
    if (Event.SCHEDULE.is(event)) {
      if (!schedule.declared()) {
        return "skip: no schedule";
      }
      return schedule.matches(tick.getDayOfWeek())
          ? "due: schedule " + schedule.describe()
          : "skip: schedule "
              + schedule.describe()
              + " does not match "
              + shortDay(tick.getDayOfWeek());
    }
    if (triggers.isEmpty()) {
      return "skip: no triggers";
    }
    return triggers.contains(event) ? "due: trigger " + event : "skip: triggers " + triggers;
  }

  /// One entry of the workflow matrix.
  ///
  /// The budget is emitted in minutes because that is the unit of GitHub's `timeout-minutes`, and
  /// an undeclared turn cap is emitted as null rather than as a made-up number.
  String toMatrixEntry() {
    var entry = new java.util.LinkedHashMap<String, Json>();
    entry.put("golem", new Json.StringValue(name));
    entry.put("os", new Json.StringValue(os));
    entry.put("harness", new Json.StringValue(harness.cli()));
    entry.put("model", new Json.StringValue(model));
    entry.put("effort", new Json.StringValue(effort));
    entry.put("timeout", new Json.NumberValue(java.math.BigDecimal.valueOf(timeout.toMinutes())));
    entry.put(
        "turns",
        turns.isPresent()
            ? new Json.NumberValue(java.math.BigDecimal.valueOf(turns.getAsInt()))
            : Json.NullValue.INSTANCE);
    return Json.encode(new Json.ObjectValue(entry));
  }

  /// The wake-ups the orchestrator understands. `schedule` is the only one it interprets itself;
  /// every other value is a forge event name.
  enum Event {
    SCHEDULE("schedule"),
    DISPATCH("workflow_dispatch");

    private final String name;

    Event(String name) {
      this.name = name;
    }

    boolean is(String event) {
      return name.equals(event);
    }
  }

  private static int closingFence(Path file, String[] lines) {
    for (var index = 1; index < lines.length; index++) {
      if (lines[index].strip().equals(FENCE)) {
        return index;
      }
    }
    throw reject(file, "frontmatter block is never closed");
  }

  /// Rejects what a properties file would otherwise silently accept.
  private static List<String> validate(Path file, String[] lines, int end) {
    var declared = new ArrayList<String>();
    var keys = new LinkedHashSet<String>();
    for (var index = 1; index < end; index++) {
      var line = lines[index];
      var position = " at line " + (index + 1);
      if (line.isBlank()) {
        throw reject(file, "blank line in frontmatter" + position);
      }
      if (line.stripLeading().startsWith("#") || line.stripLeading().startsWith("!")) {
        throw reject(file, "comment in frontmatter" + position);
      }
      if (line.stripTrailing().endsWith("\\")) {
        throw reject(file, "line continuation in frontmatter" + position);
      }
      var separator = line.indexOf(':');
      if (separator <= 0) {
        throw reject(file, "not a key: value pair" + position);
      }
      var key = line.substring(0, separator).strip();
      if (!Field.known(key)) {
        throw reject(file, "unknown key '" + key + "'; supported keys are " + keys());
      }
      if (!keys.add(key)) {
        throw reject(file, "duplicate key '" + key + "'");
      }
      if (line.substring(separator + 1).isBlank()) {
        throw reject(file, "empty value for '" + key + "'");
      }
      declared.add(line);
    }
    return declared;
  }

  private static Properties load(Path file, String text) {
    var properties = new Properties();
    try (var reader = new StringReader(text)) {
      properties.load(reader);
    } catch (IOException failure) {
      throw reject(file, "frontmatter is unreadable: " + failure.getMessage());
    }
    return properties;
  }

  private static String value(Properties properties, Field field) {
    var value = properties.getProperty(field.key());
    return value == null ? null : value.strip();
  }

  private static List<String> keys() {
    return Arrays.stream(Field.values()).map(Field::key).sorted().toList();
  }

  private static Schedule parseSchedule(Path file, String value) {
    if (value == null) {
      return new Schedule.Never();
    }
    if (value.equalsIgnoreCase(DAILY)) {
      return new Schedule.Daily();
    }
    var days = new LinkedHashSet<DayOfWeek>();
    for (var token : parseList(value)) {
      days.add(parseDay(file, token));
    }
    if (days.isEmpty()) {
      throw reject(file, "schedule must be '" + DAILY + "' or a weekday list such as [mon, thu]");
    }
    return new Schedule.Weekdays(Set.copyOf(days));
  }

  private static DayOfWeek parseDay(Path file, String token) {
    return switch (token.toLowerCase(Locale.ROOT)) {
      case "mon" -> DayOfWeek.MONDAY;
      case "tue" -> DayOfWeek.TUESDAY;
      case "wed" -> DayOfWeek.WEDNESDAY;
      case "thu" -> DayOfWeek.THURSDAY;
      case "fri" -> DayOfWeek.FRIDAY;
      case "sat" -> DayOfWeek.SATURDAY;
      case "sun" -> DayOfWeek.SUNDAY;
      default ->
          throw reject(
              file, "unknown weekday '" + token + "'; use mon, tue, wed, thu, fri, sat, or sun");
    };
  }

  /// Parses `90s`, `45m`, or `2h`.
  ///
  /// The unit is mandatory because GitHub's own field is minutes, so a bare number would silently
  /// inherit a unit the author never chose.
  private static Duration parseTimeout(Path file, String value) {
    assert value != null : "a timeout is required, defaulted by the caller";
    var amount = value.substring(0, value.length() - 1);
    var unit = value.charAt(value.length() - 1);
    try {
      var count = Long.parseLong(amount);
      if (count <= 0) {
        throw reject(file, "timeout must be positive");
      }
      return switch (unit) {
        case 's' -> Duration.ofSeconds(count);
        case 'm' -> Duration.ofMinutes(count);
        case 'h' -> Duration.ofHours(count);
        default -> throw reject(file, "timeout needs a unit suffix: 90s, 45m, or 2h");
      };
    } catch (NumberFormatException malformed) {
      throw reject(file, "timeout needs a unit suffix: 90s, 45m, or 2h");
    }
  }

  private static OptionalInt parseTurns(Path file, String value) {
    if (value == null) {
      return OptionalInt.empty();
    }
    try {
      var turns = Integer.parseInt(value);
      if (turns <= 0) {
        throw reject(file, "turns must be a positive integer");
      }
      return OptionalInt.of(turns);
    } catch (NumberFormatException malformed) {
      throw reject(file, "turns must be a positive integer");
    }
  }

  /// Accepts `[a, b]` and a bare scalar alike, so one value needs no brackets and several need no
  /// quoting.
  private static List<String> parseList(String value) {
    if (value == null) {
      return List.of();
    }
    var inner =
        value.startsWith("[") && value.endsWith("]")
            ? value.substring(1, value.length() - 1)
            : value;
    var items = new ArrayList<String>();
    for (var token : inner.split(",")) {
      var trimmed = token.strip();
      if (!trimmed.isEmpty()) {
        items.add(trimmed);
      }
    }
    return List.copyOf(items);
  }

  private static String shortDay(DayOfWeek day) {
    return day.name().substring(0, 3).toLowerCase(Locale.ROOT);
  }

  private static IllegalArgumentException reject(Path file, String message) {
    return new IllegalArgumentException(file + ": " + message);
  }
}
