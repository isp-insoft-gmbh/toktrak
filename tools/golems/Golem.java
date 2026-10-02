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

/// One golem: its declared frontmatter and its task instructions.
///
/// The frontmatter block is read by `java.util.Properties`, so key and value splitting, escaping,
/// and encoding are the platform's problem rather than a hand-written parser's. What Properties
/// deliberately tolerates is rejected first: a blank line, a comment, a duplicate key, or a line
/// continuation would otherwise let a golem mean something other than it appears to.
///
/// Strictness matters here because nobody is watching. A misread golem runs at the wrong time, on
/// the wrong machine, or without its budget.
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
  /// activation, and triggers name forge events only. Neither is listed inside the other.
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
