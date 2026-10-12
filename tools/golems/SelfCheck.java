import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.spi.ToolProvider;

/// The orchestrator checking itself.
///
/// Everything here runs without credentials, network access, or a real agent. Golem files are
/// written per check, remotes are local bare repositories, and the agent is `test/FakeClaude.java`,
/// selected through `GOLEM_CLAUDE`.
///
/// Scenario checks drive the real entry point as a subprocess, so the outcome table is exercised
/// through the path a run actually takes rather than through a reimplementation of it.
///
/// It lives beside the orchestrator rather than under `test/` because the source launcher compiles
/// the siblings of the launched file, and these checks call the orchestrator's own types. Fixtures
/// that need no such access stay in `test/`.
///
/// Run from the repository root:
///
/// ```console
/// java tools/golems/SelfCheck.java
/// ```
public final class SelfCheck {
  private static final Path REPOSITORY = Path.of(".").toAbsolutePath().normalize();
  private static final String JAVA =
      Path.of(System.getProperty("java.home"), "bin", "java").toString();

  /// The orchestrator and its fake agent, compiled once for the whole suite.
  ///
  /// Every spawned run used to be launched in source-file mode, which recompiles the orchestrator
  /// from scratch per process. That is the documented way to run it and one check still proves it
  /// works, but paying it seventeen times is feedback-loop cost, not coverage. Compiled on first
  /// use rather than at startup, so a section that never launches the orchestrator never waits for
  /// a compiler.
  private static final class Compiled {
    private static final Path CLASSES = compile();
  }

  static Path classes() {
    return Compiled.CLASSES;
  }

  private static String fakeAgent() {
    return JAVA + " -ea -cp " + classes() + " FakeClaude";
  }

  /// The documented entry point, used where launching it is the point.
  private static final List<String> SOURCE_LAUNCH =
      List.of(JAVA, "-ea", "tools/golems/Golems.java");

  /// The same program, already compiled, used everywhere else.
  private static List<String> compiledLaunch() {
    return List.of(JAVA, "-ea", "-cp", classes().toString(), "Golems");
  }

  /// How many sections the timing summary names. Enough to see the shape, few enough to stay a
  /// summary.
  private static final int SLOWEST_SHOWN = 5;

  /// How many scenarios and how many sections run at once.
  ///
  /// The work is process-bound rather than CPU-bound: a check spends its time waiting for a JVM to
  /// start or for git to fork, and the host serializes process creation anyway. So these sit above
  /// what the machine could compute in parallel, and are capped so a laptop stays usable while the
  /// suite runs.
  private static final int PARALLEL_SCENARIOS =
      Math.min(16, Math.max(4, Runtime.getRuntime().availableProcessors()));

  private static final int PARALLEL_SECTIONS = 6;

  /// What every fixture command is told about itself.
  ///
  /// The identity is carried on the command line so no fixture depends on the machine's git
  /// configuration. The housekeeping is switched off because a throwaway repository is never packed
  /// and never watched: a commit otherwise starts a maintenance process and consults a filesystem
  /// monitor, which is about 200 ms of the 700 ms a fixture commit costs here.
  private static final List<String> FIXTURE_CONFIG =
      List.of(
          "-c",
          "user.email=checks@example.invalid",
          "-c",
          "user.name=checks",
          "-c",
          "gc.auto=0",
          "-c",
          "core.fsmonitor=false");

  /// How long stopping a hung child may take beyond its budget.
  private static final Duration PROMPT_STOP = Duration.ofSeconds(8);

  /// How long one command-line invocation may take. It either answers at once or something is wrong
  /// with the host.
  private static final Duration CLI_BUDGET = Duration.ofMinutes(5);

  /// The section a check belongs to, bound for the duration of that section.
  private static final ScopedValue<Journal> JOURNAL = ScopedValue.newInstance();

  /// One named group of checks.
  ///
  /// Naming the sections as data rather than as a run of statements is what lets the suite time
  /// itself: the cost of a section is the cost of the feedback loop, and an unmeasured loop only
  /// ever gets slower.
  private record Section(String title, Checks checks) {}

  @FunctionalInterface
  private interface Checks {
    void run() throws Exception;
  }

  private static final List<Section> SECTIONS =
      List.of(
          new Section("outcomes as values", SelfCheck::outcomeValues),
          new Section("fields", SelfCheck::fields),
          new Section("processes", SelfCheck::processes),
          new Section("authentication", SelfCheck::authentication),
          new Section("frontmatter", SelfCheck::frontmatter),
          new Section("discovery", SelfCheck::discovery),
          new Section("selection", SelfCheck::selection),
          new Section("invocation", SelfCheck::invocation),
          new Section("paths", SelfCheck::paths),
          new Section("labels", SelfCheck::labels),
          new Section("report", SelfCheck::report),
          new Section("guards", SelfCheck::guards),
          new Section("branches", SelfCheck::branches),
          new Section("remote", SelfCheck::remote),
          new Section("outcomes", SelfCheck::outcomes),
          new Section("command line", SelfCheck::commandLine));

  public static void main(String[] arguments) throws Exception {
    var selected = select(List.of(arguments));
    if (selected.isEmpty()) {
      return;
    }

    // Sections share nothing: each writes its own temporary directories and reads
    // its own repositories. What they do share is the machine's ability to start
    // processes, which is what this suite actually waits on, so they wait on it
    // together.
    var journals = new ArrayList<Journal>();
    try (var pool = Executors.newFixedThreadPool(Math.min(PARALLEL_SECTIONS, selected.size()))) {
      var pending = selected.stream().map(section -> pool.submit(() -> record(section))).toList();
      for (var future : pending) {
        journals.add(future.get());
      }
    }

    var checks = 0;
    var failed = 0;
    for (var journal : journals) {
      journal.print();
      checks += journal.checks();
      failed += journal.failures();
    }

    System.out.println();
    System.out.println(checks + " checks, " + failed + " failed");
    reportTimings(journals);
    if (failed > 0) {
      System.exit(1);
    }
  }

  /// Chooses the sections to run from the command line.
  ///
  /// The whole suite takes about half a minute on a machine that forks slowly, and almost no change
  /// needs the whole suite. Naming one section is the difference between a second and half a
  /// minute, which is the difference between checking while working and checking afterwards.
  private static List<Section> select(List<String> arguments) {
    if (arguments.isEmpty()) {
      return SECTIONS;
    }
    if (arguments.contains("--help") || arguments.contains("--list")) {
      System.out.println("usage: java -ea tools/golems/SelfCheck.java [section...]");
      System.out.println("runs every section when none is named. sections:");
      SECTIONS.forEach(section -> System.out.println("  " + section.title()));
      return List.of();
    }

    // A prefix is enough, because these are typed while iterating: `front` means
    // frontmatter and `out` means both outcome sections.
    var chosen =
        SECTIONS.stream()
            .filter(
                section ->
                    arguments.stream().anyMatch(wanted -> section.title().startsWith(wanted)))
            .toList();
    if (chosen.isEmpty()) {
      System.out.println("no section matches " + arguments + "; try --list");
      System.exit(Outcome.ExitCode.USAGE.code());
    }
    return chosen;
  }

  /// Runs one section with a journal of its own.
  ///
  /// The journal is bound rather than passed, because every check would otherwise have to carry it
  /// as an argument, and a check should read as a statement about the orchestrator rather than as
  /// bookkeeping.
  private static Journal record(Section section) throws Exception {
    var journal = new Journal(section.title());
    var started = System.nanoTime();
    ScopedValue.where(JOURNAL, journal)
        .call(
            () -> {
              section.checks().run();
              return null;
            });
    journal.finished(Duration.ofNanos(System.nanoTime() - started));
    return journal;
  }

  /// What one section observed, kept until every section is done.
  ///
  /// Sections finish out of order, so their lines are collected rather than printed. The order a
  /// reader sees stays the order the suite declares.
  private static final class Journal {
    private final String title;
    private final List<String> lines = new ArrayList<>();
    private int checks;
    private int failures;
    private Duration elapsed = Duration.ZERO;

    private Journal(String title) {
      this.title = title;
    }

    void check(String label, boolean ok) {
      checks++;
      if (!ok) {
        failures++;
      }
      lines.add(String.format("  %-48s %s", label, ok ? "ok" : "FAILED"));
    }

    void note(String text) {
      lines.add("    " + text);
    }

    void finished(Duration duration) {
      elapsed = duration;
    }

    void print() {
      System.out.println();
      System.out.println("── " + title);
      lines.forEach(System.out::println);
    }

    String title() {
      return title;
    }

    Duration elapsed() {
      return elapsed;
    }

    int checks() {
      return checks;
    }

    int failures() {
      return failures;
    }
  }

  /// Prints where the suite spent its time, slowest first.
  ///
  /// This is the benchmark. Sections overlap, so their times add up to more than the wall clock;
  /// what they rank is which section would have to get cheaper for the suite to get faster.
  private static void reportTimings(List<Journal> journals) {
    var slowest =
        journals.stream()
            .map(Journal::elapsed)
            .max(Comparator.naturalOrder())
            .orElse(Duration.ZERO);
    System.out.println("slowest section " + slowest.toMillis() + " ms");
    journals.stream()
        .sorted(Comparator.comparing(Journal::elapsed).reversed())
        .limit(SLOWEST_SHOWN)
        .forEach(
            journal ->
                System.out.printf(
                    "  %-20s %6d ms%n", journal.title(), journal.elapsed().toMillis()));
  }

  /// The outcome is the one value every surface agrees on, so its labels, exit codes, and
  /// publishability are pinned rather than reimplemented per caller.
  private static void outcomeValues() {
    check("changed is labelled", new Outcome.Changed().label().equals("changed"));
    check("no change is labelled", new Outcome.NoChange().label().equals("no_change"));
    check("blocked carries its reason", new Outcome.Blocked("quota").reason().equals("quota"));
    check(
        "incomplete carries its reason",
        new Outcome.Incomplete("budget").reason().equals("budget"));
    check("a finished outcome has no reason", new Outcome.Changed().reason().isBlank());

    check(
        "only changed is publishable",
        new Outcome.Changed().publishable()
            && !new Outcome.NoChange().publishable()
            && !new Outcome.Blocked("x").publishable()
            && !new Outcome.Incomplete("x").publishable());

    check(
        "no change is not a failure", new Outcome.NoChange().exit() == Outcome.ExitCode.COMPLETED);
    check(
        "incomplete is not a failure",
        new Outcome.Incomplete("budget").exit() == Outcome.ExitCode.COMPLETED);
    check("blocked is a failure", new Outcome.Blocked("x").exit() == Outcome.ExitCode.BLOCKED);
    check(
        "exit codes are distinct",
        Outcome.ExitCode.COMPLETED.code() == 0
            && Outcome.ExitCode.BLOCKED.code() == 1
            && Outcome.ExitCode.USAGE.code() == 2);

    check("a blocked state must say why", throwsError(() -> new Outcome.Blocked(" ")));
  }

  /// The frontmatter fields are declared once and feed the parser, the help text, and this check. A
  /// field that parses but is undocumented is a trap, so the three are compared against each other.
  private static void fields() {
    var help = Field.help();
    for (var field : Field.values()) {
      check("documented: " + field.key(), help.contains(field.key()));
      check("recognised: " + field.key(), Field.known(field.key()));
    }
    check("unknown keys are not recognised", !Field.known("whenever"));
    check(
        "defaults are stated",
        help.contains(Golem.DEFAULT_OS) && help.contains(Golem.DEFAULT_TIMEOUT));
  }

  // ----------------------------------------------------------- processes

  /// Output bounds, exit codes, and cancellation are the orchestrator's only defence against a
  /// child that misbehaves.
  private static void processes() throws Exception {
    var ok = Proc.run(List.of(JAVA, "--version"), REPOSITORY, Map.of(), Duration.ofMinutes(1));
    check("captures a successful run", ok.ok() && !ok.out().isBlank());
    check("no stop recorded", ok.stopped() == Proc.Stop.NONE);

    var failed =
        Proc.run(List.of(JAVA, "--nonsense-flag"), REPOSITORY, Map.of(), Duration.ofMinutes(1));
    check("non-zero exit reported", !failed.ok() && failed.exit() != 0);
    check("stderr captured", !failed.err().isBlank());

    var budget = Duration.ofSeconds(3);
    var started = System.nanoTime();
    var hang =
        Proc.run(
            List.of(JAVA, "-cp", classes().toString(), "FakeClaude"),
            REPOSITORY,
            Map.of("GOLEM_FAKE", "hang"),
            budget);
    var elapsed = Duration.ofNanos(System.nanoTime() - started);
    check("timeout is recorded", hang.stopped() == Proc.Stop.TIMEOUT);
    // Stopping a hung child must not cost the full grace period when
    // termination succeeds promptly.
    check(
        "a hung child is stopped promptly",
        elapsed.minus(budget).toSeconds() < PROMPT_STOP.toSeconds());
  }

  // --------------------------------------------------------- frontmatter

  /// Strict parsing is the first guard: a golem that is silently misread runs at the wrong time, on
  /// the wrong machine, or without its budget.
  private static void authentication() throws Exception {
    var home = Files.createTempDirectory("golem-auth-home");
    var cache = home.resolve("cache.bin");
    var auth = home.resolve(".pi/agent/auth.json");
    var seed =
        Base64.getEncoder().encodeToString("{\"fake\":true}".getBytes(StandardCharsets.UTF_8));
    var key = Base64.getEncoder().encodeToString(new byte[32]);
    var env = Map.of("GOLEM_AUTH_SEED", seed, "GOLEM_AUTH_CACHE_KEY", key);
    for (var action : List.of("seed", "encrypt", "decrypt")) {
      var args =
          new ArrayList<>(
              List.of(
                  JAVA,
                  "-ea",
                  "-Duser.home=" + home,
                  "-cp",
                  classes().toString(),
                  "Auth",
                  action,
                  "pi"));
      if (!action.equals("seed")) args.add(cache.toString());
      var builder = new ProcessBuilder(args).directory(REPOSITORY.toFile());
      builder.environment().putAll(env);
      var result = builder.start();
      check(action + " with fake authentication succeeds", result.waitFor() == 0);
      if (action.equals("encrypt")) Files.delete(auth);
    }
    check("decryption restores exact bytes", Files.readString(auth).equals("{\"fake\":true}"));
    var tampered = Files.readAllBytes(cache);
    tampered[tampered.length - 1] ^= 1;
    Files.write(cache, tampered);
    Files.delete(auth);
    var builder =
        new ProcessBuilder(
            JAVA,
            "-ea",
            "-Duser.home=" + home,
            "-cp",
            classes().toString(),
            "Auth",
            "decrypt",
            "pi",
            cache.toString());
    builder.directory(REPOSITORY.toFile()).redirectError(ProcessBuilder.Redirect.DISCARD);
    builder.environment().putAll(env);
    check(
        "tampered authentication is rejected",
        builder.start().waitFor() != 0 && !Files.exists(auth));
  }

  private static void frontmatter() throws Exception {
    rejects("missing frontmatter", "no frontmatter here");
    rejects("unclosed frontmatter", "---\nschedule: daily\n");
    rejects("unknown key", "---\nwhen: daily\n---\nbody");
    rejects("duplicate key", "---\nos: a\nos: b\n---\nbody");
    rejects("empty value", "---\nos:\n---\nbody");
    rejects("blank line", "---\nos: a\n\nmodel: sonnet\n---\nbody");
    rejects("comment", "---\n# note\nos: a\n---\nbody");
    rejects("value without key", "---\njust text\n---\nbody");
    rejects("empty body", "---\nos: a\n---\n");
    rejects("bare timeout", "---\ntimeout: 45\n---\nbody");
    rejects("zero timeout", "---\ntimeout: 0m\n---\nbody");
    rejects("negative timeout", "---\ntimeout: -5m\n---\nbody");
    rejects("unknown timeout unit", "---\ntimeout: 45x\n---\nbody");
    rejects("timeout without amount", "---\ntimeout: m\n---\nbody");
    rejects("zero turns", "---\nturns: 0\n---\nbody");
    rejects("negative turns", "---\nturns: -3\n---\nbody");
    rejects("non-numeric turns", "---\nturns: many\n---\nbody");
    rejects("unknown weekday", "---\nschedule: [funday]\n---\nbody");
    rejects("empty weekday list", "---\nschedule: []\n---\nbody");

    var defaults = parse("---\nschedule: daily\n---\nwork");
    check("default os", "ubuntu-latest".equals(defaults.os()));
    check("default timeout", defaults.timeout().toMinutes() == 30);
    check("default model", "sonnet".equals(defaults.model()));
    check("default effort", "medium".equals(defaults.effort()));
    check("no turn cap by default", defaults.turns().isEmpty());
    check("normal mode by default", defaults.branch().isEmpty());
    check("no verify by default", defaults.verify().isEmpty());

    var full =
        parse(
            "---\ntimeout: 2h\nturns: 12\nbranch: golem/x\nos: windows-latest\n"
                + "model: opus\neffort: high\nverify: git status\n---\nwork");
    check("hour suffix", full.timeout().toHours() == 2);
    check("turn cap parsed", full.turns().orElse(0) == 12);
    check("branch parsed", full.branch().orElse("").equals("golem/x"));
    check("os parsed", "windows-latest".equals(full.os()));
    check("verify parsed", full.verify().orElse("").equals("git status"));

    check("seconds suffix", parse("---\ntimeout: 90s\n---\nb").timeout().toSeconds() == 90);
    check(
        "crlf accepted",
        parse("---\r\nschedule: daily\r\n---\r\n\r\nwork\r\n").schedule()
            instanceof Golem.Schedule.Daily);
    check(
        "spaces in list tolerated",
        parse("---\nschedule: [ mon , thu ]\n---\nb").due("schedule", monday()));
    check(
        "daily is case-insensitive",
        parse("---\nschedule: DAILY\n---\nb").schedule() instanceof Golem.Schedule.Daily);
    check(
        "bare trigger without brackets",
        parse("---\ntriggers: push\n---\nb").due("push", monday()));
    check(
        "non-ascii body survives",
        parse("---\nos: a\n---\nGrundstück prüfen").body().contains("ü"));
    rejects("line continuation", "---\nos: a\\\nmodel: sonnet\n---\nbody");
    rejects("properties comment marker", "---\n! note\nos: a\n---\nbody");
    check(
        "equals is not a separator",
        throwsRuntime(() -> parse("---\nos=ubuntu-latest\n---\nbody")));
    check(
        "whitespace around values is trimmed",
        parse("---\nos:    ubuntu-24.04   \n---\nb").os().equals("ubuntu-24.04"));
  }

  // ----------------------------------------------------------- paths

  /// Every file the run depends on is named by the type that owns it.
  ///
  /// These are the drift traps: preflight refusing to start without the protocol while the agent
  /// reads a different file, or discovery skipping a shared policy file the prompt never appends.
  /// Naming each once is the fix, and these checks are what keeps it named once.
  private static void paths() throws Exception {
    var run = run(Run.Host.TRUSTED, Run.Platform.POSIX);
    check(
        "the protocol lives beside the orchestrator",
        run.protocolFile().startsWith(run.orchestrator()));
    check(
        "the real orchestrator ships one",
        Files.isRegularFile(REPOSITORY.resolve("tools/golems").resolve("protocol.md")));
    check(
        "agent directories are run scoped",
        run.configDirectory().startsWith(run.directory())
            && run.tempDirectory().startsWith(run.directory()));
    check("agent directories are distinct", !run.configDirectory().equals(run.tempDirectory()));

    var root = Files.createTempDirectory("golem-paths");
    var policy = Golem.sharedPolicy(root);
    Files.createDirectories(policy.getParent());
    Files.writeString(policy, "shared policy");
    Files.writeString(policy.getParent().resolve("one.md"), "---\nos: a\n---\nwork");
    check(
        "the shared policy is the file discovery skips",
        Golem.discover(root).stream().map(Golem::name).toList().equals(List.of("one")));

    var file = root.resolve("nested").resolve("note.md");
    check("a missing file reads as empty", Text.readIfPresent(file).isEmpty());
    Text.write(file, "Grundstück");
    check("writing creates the way to the file", Text.read(file).equals("Grundstück"));
    Text.append(file, " geprüft");
    check("appending keeps what was there", Text.read(file).equals("Grundstück geprüft"));
    var fresh = root.resolve("summary.md");
    Text.append(fresh, "first");
    check("appending creates a missing file", Text.read(fresh).equals("first"));
  }

  // ----------------------------------------------------------- labels

  /// Label colors are pinned, not merely plausible.
  ///
  /// A golem's color is derived from its name so that adding a golem needs no decision, which also
  /// means a change to the derivation silently recolors every label in every repository. These
  /// expectations exist to make that change loud.
  private static void labels() {
    var pinned = new LinkedHashMap<String, String>();
    pinned.put("deps", "ad62da");
    pinned.put("reviewer", "629ada");
    pinned.put("a", "627bda");
    pinned.put("docs", "dada62");
    pinned.put("golem", "cdda62");
    pinned.put("zz", "62da97");
    pinned.put("Ümlaut", "62cdda");
    pinned.put("x1", "dacc62");
    pinned.forEach(
        (name, color) ->
            check("color of " + name + " is " + color, Forge.color(name).equals(color)));

    check(
        "colors are six hex digits",
        pinned.keySet().stream().allMatch(name -> Forge.color(name).matches("[0-9a-f]{6}")));
    check("the same name keeps its color", Forge.color("deps").equals(Forge.color("deps")));
    check("adjacent names do not share a color", !Forge.color("deps").equals(Forge.color("depr")));
    check("a name is required", throwsError(() -> Forge.color(" ")));
    var review =
        """
        {"data":{"repository":{"pullRequest":{"reviewThreads":{
          "nodes":[{"isResolved":%s}],
          "pageInfo":{"hasNextPage":%s,"endCursor":%s}
        }}}}}
        """;
    check(
        "resolved review permits publication",
        !Forge.reviewPage(review.formatted(true, false, "null")).more());
    check(
        "unresolved review prevents publication",
        throwsRuntime(() -> Forge.reviewPage(review.formatted(false, false, "null"))));
    check(
        "review pagination carries cursor",
        Forge.reviewPage(review.formatted(true, true, "\"next\"")).cursor().equals("next"));
    check("missing review data fails closed", throwsRuntime(() -> Forge.reviewPage("{}")));
    check(
        "missing cursor fails closed",
        throwsRuntime(() -> Forge.reviewPage(review.formatted(true, true, "null"))));
  }

  // ----------------------------------------------------------- discovery

  /// Discovery must be deterministic and must not mistake shared policy for a golem, or a run would
  /// be selected from a file nobody wrote as a task.
  private static void discovery() throws Exception {
    var root = Files.createTempDirectory("golem-discovery");
    var directory = Files.createDirectories(root.resolve(".golems"));
    Files.writeString(directory.resolve("_golems.md"), "shared policy, not a golem");
    Files.writeString(directory.resolve("notes.txt"), "not markdown");
    Files.writeString(directory.resolve("beta.md"), "---\nos: a\n---\nwork");
    Files.writeString(directory.resolve("alpha.md"), "---\nos: a\n---\nwork");

    var found = Golem.discover(root).stream().map(Golem::name).toList();
    check("shared policy is not a golem", !found.contains("_golems"));
    check("non-markdown ignored", !found.contains("notes"));
    check("discovery is sorted", found.equals(List.of("alpha", "beta")));
    check("name comes from the file", Golem.require(root, "alpha").name().equals("alpha"));
    check("unknown golem rejected", throwsRuntime(() -> Golem.require(root, "gamma")));
    check(
        "missing directory rejected",
        throwsRuntime(() -> Golem.discover(Files.createTempDirectory("golem-empty"))));
  }

  // ----------------------------------------------------------- selection

  /// Schedule and triggers are independent activations. Neither is listed inside the other, and a
  /// golem with neither is manual only.
  private static void selection() throws Exception {
    var weekly = parse("---\nschedule: [mon]\n---\nwork");
    check("weekday match", weekly.due("schedule", monday()));
    check("weekday mismatch", !weekly.due("schedule", thursday()));
    var mondayInPlusFourteen = ZonedDateTime.parse("2026-10-05T00:30:00+14:00");
    check(
        "given_offsetMonday_when_selectingUtcSunday_then_skipMonday",
        !weekly.due("schedule", mondayInPlusFourteen)
            && weekly.reason("schedule", mondayInPlusFourteen).contains("sun"));
    check(
        "given_offsetMonday_when_selectingUtcSunday_then_runSunday",
        parse("---\nschedule: [sun]\n---\nwork").due("schedule", mondayInPlusFourteen));
    check("a schedule is not an event", !weekly.due("pull_request_review", monday()));

    var daily = parse("---\nschedule: daily\n---\nwork");
    check(
        "daily matches any weekday",
        daily.due("schedule", thursday()) && daily.due("schedule", monday()));

    var evented = parse("---\ntriggers: [pull_request_review, issue_comment]\n---\nwork");
    check("first trigger matches", evented.due("pull_request_review", monday()));
    check("second trigger matches", evented.due("issue_comment", monday()));
    check("other events do not", !evented.due("push", monday()));
    check("an event is not a schedule", !evented.due("schedule", monday()));

    var both = parse("---\nschedule: [thu]\ntriggers: [push]\n---\nwork");
    check(
        "both activations coexist", both.due("schedule", thursday()) && both.due("push", monday()));

    var manual = parse("---\nos: ubuntu-latest\n---\nwork");
    check("manual only", !manual.due("schedule", monday()) && !manual.due("push", monday()));
    check("skip is explained", manual.reason("schedule", monday()).startsWith("skip"));
    check("due is explained", weekly.reason("schedule", monday()).startsWith("due"));
    check("mismatch names the day", weekly.reason("schedule", thursday()).contains("thu"));

    var entry = parse("---\nturns: 4\nos: macos-14\ntimeout: 90m\n---\nw").toMatrixEntry();
    check("matrix carries os", entry.contains("\"os\":\"macos-14\""));
    check("matrix carries minutes", entry.contains("\"timeout\":90"));
    check("matrix carries turns", entry.contains("\"turns\":4"));
    check(
        "matrix turns null when undeclared",
        parse("---\nos: a\n---\nw").toMatrixEntry().contains("\"turns\":null"));
  }

  // ---------------------------------------------------------- invocation

  /// The agent invocation encodes several decisions at once: which permission mode a host earns,
  /// which shell a platform uses, and what the agent is told about its run.
  private static void invocation() throws Exception {
    var golem = parse("---\nbranch: golem/x\nturns: 9\nmodel: opus\neffort: high\n---\nwork");
    var prompt = Path.of("system-prompt.md");

    var local = run(Run.Host.TRUSTED, Run.Platform.POSIX);
    var localCommand = Agent.command(golem, prompt, local);
    check("print mode", localCommand.contains("-p") && localCommand.contains("--output-format"));
    check("json output", localCommand.contains("json"));
    check(
        "model and effort passed", localCommand.contains("opus") && localCommand.contains("high"));
    check("turn cap passed", localCommand.contains("--max-turns") && localCommand.contains("9"));
    check(
        "trace id is the session id",
        localCommand.contains("--session-id") && localCommand.contains(local.traceId().toString()));
    check(
        "attribution suppressed",
        localCommand.stream().anyMatch(part -> part.contains("\"commit\":\"\"")));
    check(
        "settings travel with the command",
        localCommand.stream().anyMatch(part -> part.contains("\"env\":{")));
    check(
        "mcp disabled with valid empty config",
        localCommand.contains("--strict-mcp-config")
            && localCommand.contains("{\"mcpServers\":{}}"));
    check("local host asks for nothing", localCommand.contains("dontAsk"));
    check("local host is not bypassed", !localCommand.contains("--dangerously-skip-permissions"));

    var untimed = Agent.command(parse("---\nos: a\n---\nw"), prompt, local);
    check("no turn cap when undeclared", !untimed.contains("--max-turns"));

    var hosted = Agent.command(golem, prompt, run(Run.Host.EPHEMERAL, Run.Platform.POSIX));
    check("ephemeral host runs unattended", hosted.contains("--dangerously-skip-permissions"));
    check("unattended host asks nothing", !hosted.contains("dontAsk"));

    var windows = Agent.command(golem, prompt, run(Run.Host.TRUSTED, Run.Platform.WINDOWS));
    check(
        "windows removes bash", windows.contains("--disallowedTools") && windows.contains("Bash"));
    check(
        "windows allowlists powershell",
        windows.stream().anyMatch(part -> part.startsWith("PowerShell,")));

    var settings = Agent.settings(local);
    for (var key :
        List.of(
            "CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC",
            "CLAUDE_CODE_DISABLE_OFFICIAL_MARKETPLACE_AUTOINSTALL",
            "CLAUDE_CODE_DISABLE_AUTO_MEMORY",
            "CLAUDE_CODE_DISABLE_BUNDLED_SKILLS",
            "CLAUDE_CODE_DISABLE_POLICY_SKILLS",
            "CLAUDE_CODE_DISABLE_BACKGROUND_TASKS",
            "CLAUDE_CODE_DISABLE_CRON",
            "CLAUDE_CODE_DISABLE_FILE_CHECKPOINTING",
            "CLAUDE_CODE_DISABLE_TERMINAL_TITLE",
            "CLAUDE_CODE_DISABLE_GIT_INSTRUCTIONS",
            "CLAUDE_CODE_RETRY_WATCHDOG",
            "CLAUDE_CODE_PROMPT_CACHE_TTL",
            "CLAUDE_BASH_MAINTAIN_PROJECT_WORKING_DIR")) {
      check("settings pin " + key, settings.contains(key));
    }
    check(
        "windows shell is a setting too",
        Agent.settings(run(Run.Host.TRUSTED, Run.Platform.WINDOWS))
            .contains("CLAUDE_CODE_USE_POWERSHELL_TOOL"));
    check("posix needs no shell setting", !settings.contains("POWERSHELL"));

    var environment = Agent.environment(golem, local);
    check(
        "toggles are not left to the host",
        environment.keySet().stream().noneMatch(key -> key.startsWith("CLAUDE_CODE_DISABLE")));
    check(
        "identity is per golem",
        "golem-probe".equals(environment.get("GIT_AUTHOR_NAME"))
            && environment.get("GIT_COMMITTER_NAME").equals(environment.get("GIT_AUTHOR_NAME")));
    check(
        "commit address shared",
        environment.get("GIT_AUTHOR_EMAIL").contains("users.noreply.github.com"));
    check(
        "run facts exported",
        environment.containsKey("GOLEM_NAME")
            && environment.containsKey("GOLEM_WORKSPACE")
            && environment.containsKey("GOLEM_TIMEOUT")
            && environment.containsKey("GOLEM_PR_FILE"));
    check("branch exported in branch mode", "golem/x".equals(environment.get("GOLEM_BRANCH")));
    check(
        "no branch exported in normal mode",
        !Agent.environment(parse("---\nos: a\n---\nw"), local).containsKey("GOLEM_BRANCH"));
    check(
        "config directory is run scoped",
        environment.get("CLAUDE_CONFIG_DIR").startsWith(local.directory().toString()));
    check(
        "temp directory is run scoped",
        environment.get("CLAUDE_CODE_TMPDIR").startsWith(local.directory().toString()));

    decoding(local);
  }

  /// Every field of the CLI result lands where it belongs.
  ///
  /// The fixture gives every column a different value on purpose. Two numbers that swapped places
  /// would still parse, still render, and still look like a plausible run, so the only way to catch
  /// a reordering is to make no two columns agree.
  private static void decoding(Run local) {
    var raw =
        "{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":false,\"num_turns\":3,"
            + "\"total_cost_usd\":0.25,"
            + "\"usage\":{\"input_tokens\":11,\"output_tokens\":22,\"cache_read_input_tokens\":33},"
            + "\"api_error_status\":429,\"result\":\"a narrative\"}";
    var decoded = Agent.interpret(new Proc.Result(0, raw, "", Proc.Stop.NONE), local);
    check("subtype decoded", "success".equals(decoded.subtype()));
    check("error flag decoded", !decoded.isError());
    check("turns decoded", decoded.turns() == 3);
    check("cost decoded", decoded.costUsd() == 0.25);
    check("input tokens decoded", decoded.inputTokens() == 11);
    check("output tokens decoded", decoded.outputTokens() == 22);
    check("cache tokens decoded", decoded.cacheTokens() == 33);
    check("api status decoded", decoded.apiErrorStatus() == 429);
    check("narrative decoded", "a narrative".equals(decoded.narrative()));
    check("raw result kept", decoded.raw().contains("cache_read_input_tokens"));

    check(
        "JSON output round trips",
        Json.parse(Json.encode(Json.parse(raw))).equals(Json.parse(raw)));
    check(
        "escaped Unicode round trips",
        Json.parse(Json.encode(Json.parse("\"\\uD83D\\uDE80\\n\"")))
            .equals(Json.parse("\"\\uD83D\\uDE80\\n\"")));
    check("duplicate JSON keys rejected", throwsRuntime(() -> Json.parse("{\"a\":1,\"a\":2}")));
    check("trailing JSON rejected", throwsRuntime(() -> Json.parse("true false")));
    check("unpaired surrogate rejected", throwsRuntime(() -> Json.parse("\"\\uD83D\"")));
    check(
        "oversized JSON rejected", throwsRuntime(() -> Json.parse(" ".repeat(Json.BYTES_MAX + 1))));

    // Adapted from nst/JSONTestSuite/test_parsing (MIT): y_string_u+2028_line_sep,
    // n_number_0.3e+, and n_string_escaped_ctrl_char_tab. Escaped-key aliases and
    // the depth boundary exercise TokTrak's stricter local limits.
    var separator = "\u2028";
    check(
        "line separator inside JSON string accepted",
        Json.parse("[\"" + separator + "\"]")
            .equals(new Json.ArrayValue(List.of(new Json.StringValue(separator)))));
    check("exponent sign needs a digit", throwsRuntime(() -> Json.parse("[0.3e+]")));
    check(
        "escaped literal tab rejected",
        throwsRuntime(() -> Json.parse("[\"" + "\\" + "\t" + "\"]")));
    check(
        "escaped duplicate key rejected",
        throwsRuntime(() -> Json.parse("{\"a\":0,\"\\u0061\":1}")));
    var deepest = "[".repeat(Json.DEPTH_MAX) + "0" + "]".repeat(Json.DEPTH_MAX);
    check("exact nesting limit accepted", Json.parse(deepest) instanceof Json.ArrayValue);
    check("excess nesting rejected", throwsRuntime(() -> Json.parse("[" + deepest + "]")));

    // A three-byte character, as in JSONTestSuite's y_string_three-byte-utf-8,
    // distinguishes the byte budget from Java's UTF-16 String.length().
    var nearLimit = "€".repeat(Json.BYTES_MAX / 3 - 1);
    var overLimit = nearLimit + "€";
    check(
        "multibyte input within byte budget accepted",
        Json.parse("\"" + nearLimit + "\"").equals(new Json.StringValue(nearLimit)));
    check(
        "multibyte output within byte budget accepted",
        Json.encode(new Json.StringValue(nearLimit)).equals("\"" + nearLimit + "\""));
    check(
        "multibyte input over byte budget rejected",
        throwsRuntime(() -> Json.parse("\"" + overLimit + "\"")));
    check(
        "multibyte output over byte budget rejected",
        throwsRuntime(() -> Json.encode(new Json.StringValue(overLimit))));

    var silent =
        Agent.interpret(
            new Proc.Result(1, "nothing useful", "private-token-value", Proc.Stop.NONE), local);
    check(
        "a run without a result is readable", "none".equals(silent.subtype()) && silent.isError());
    check(
        "a run without a result counts nothing",
        silent.turns() == 0 && silent.costUsd() == 0 && silent.inputTokens() == 0);
    check("stderr withheld from narrative", silent.narrative().contains("stderr withheld"));
    check(
        "stderr withheld from public report",
        !new Report("qa", new Outcome.Blocked("agent failed"))
            .narrative(silent.narrative())
            .markdown()
            .contains("private-token-value"));

    var plain = Agent.interpretPlain(new Proc.Result(0, "finished", "", Proc.Stop.NONE));
    check("text harness response succeeds", plain.succeeded());
    check("text harness preserves response", plain.narrative().equals("finished"));
    check(
        "silent text harness cannot claim success",
        !Agent.interpretPlain(new Proc.Result(0, "", "", Proc.Stop.NONE)).succeeded());
    check(
        "text harness exit failure stays blocked",
        !Agent.interpretPlain(new Proc.Result(1, "finished", "", Proc.Stop.NONE)).succeeded());
  }

  // -------------------------------------------------------------- report

  /// One renderer feeds every sink, and the pull-request body belongs to its prose. Both properties
  /// are easy to break and invisible until someone reads a pull request.
  private static void report() throws Exception {
    check("seconds", "9s".equals(Report.duration(Duration.ofSeconds(9))));
    check("minute boundary", "1m 0s".equals(Report.duration(Duration.ofSeconds(60))));
    check("minutes and seconds", "61m 1s".equals(Report.duration(Duration.ofSeconds(3661))));
    check("tokens below a thousand", Report.tokens(999, 12, 0).startsWith("999 in"));
    check("tokens compacted", Report.tokens(41200, 8100, 180000).contains("41.2k in"));
    check("cache omitted when absent", !Report.tokens(10, 10, 0).contains("cache"));

    var measured =
        new Agent.Result(0, Proc.Stop.NONE, "success", false, 3, 0.25, 11, 22, 33, 0, "", "");
    var claudeUsage =
        new Report("claude", new Outcome.Changed()).usage(Golem.Harness.CLAUDE, measured);
    check("Claude reports measured turns", claudeUsage.markdown().contains("| turns | 3 |"));
    check("Claude reports measured cost", claudeUsage.markdown().contains("$0.25"));
    check(
        "Claude console reports measured usage",
        Golems.agentSummary(Golem.Harness.CLAUDE, measured).contains("3 turns, $0.25"));
    var failed = Agent.Result.none(new Proc.Result(1, "", "", Proc.Stop.NONE), "", "");
    check(
        "failed Claude run reports no usage",
        new Report("qa", new Outcome.Blocked("agent failed"))
            .usage(Golem.Harness.CLAUDE, failed)
            .markdown()
            .contains("not reported by harness"));
    check(
        "failed Claude console reports no usage",
        Golems.agentSummary(Golem.Harness.CLAUDE, failed).endsWith("usage not reported"));
    var piUsage = new Report("pi", new Outcome.Changed()).usage(Golem.Harness.PI, measured);
    check("Pi does not claim measured turns", !piUsage.markdown().contains("| turns |"));
    check("Pi states usage unavailable", piUsage.markdown().contains("not reported by harness"));
    check(
        "Pi console omits fake counters",
        Golems.agentSummary(Golem.Harness.PI, measured).endsWith("usage not reported"));
    var codexUsage =
        new Report("codex", new Outcome.Changed()).usage(Golem.Harness.CODEX, measured);
    check("Codex does not claim measured cost", !codexUsage.markdown().contains("| cost |"));

    var changed = new Report("deps", new Outcome.Changed()).fact("cost", "$0.31");
    check(
        "headline names the golem and outcome", changed.headline().equals("golem-deps · changed"));
    check(
        "reason appears in the headline",
        new Report("deps", new Outcome.Blocked("quota exhausted"))
            .headline()
            .endsWith("quota exhausted"));
    check("blank facts are dropped", !changed.fact("empty", " ").markdown().contains("empty"));
    check("markdown carries the facts", changed.markdown().contains("$0.31"));
    check("plain output ends in the outcome", changed.plain().contains("outcome  changed"));

    var narrative = new Report("deps", new Outcome.Changed()).narrative("n".repeat(10_000));
    check(
        "narrative truncated",
        narrative.markdown().contains("[truncated]") && narrative.markdown().length() < 8000);
    check("narrative collapsed", narrative.markdown().contains("<details>"));

    var prose =
        "Jollyday angehoben."
            + System.lineSeparator()
            + System.lineSeparator()
            + "Edited by a human.";
    var first = changed.mergeIntoBody(prose);
    check("prose stays first", first.startsWith(prose));
    check("block is appended", first.contains(Report.BEGIN) && first.strip().endsWith(Report.END));

    var second = changed.mergeIntoBody(first);
    check("exactly one block", second.indexOf(Report.BEGIN) == second.lastIndexOf(Report.BEGIN));
    check("prose untouched by a second run", second.startsWith(prose));

    var edited = first.replace("Edited by a human.", "Edited again, after the block existed.");
    var third = changed.mergeIntoBody(edited);
    check("later human edits survive", third.contains("Edited again, after the block existed."));

    var leading = changed.pullRequestBlock() + System.lineSeparator() + prose;
    var moved = changed.mergeIntoBody(leading);
    check(
        "a block found first is moved last",
        moved.indexOf("Jollyday") < moved.indexOf(Report.BEGIN));
    check("empty body accepted", changed.mergeIntoBody("").contains(Report.BEGIN));
    check("null body accepted", changed.mergeIntoBody(null).contains(Report.BEGIN));

    var summary = Files.createTempFile("golem-summary", ".md");
    Files.writeString(summary, "");
    check("job summary skipped without the variable", quiet(() -> changed.writeJobSummary()));

    surface();
  }

  /// Where the run thinks it is, decided once.
  ///
  /// Grouping, annotations, and the permission mode all follow from this, and they used to follow
  /// from three separate readings of the same variable. A host that sets it to anything but `true`
  /// is the case that told them apart.
  private static void surface() {
    var declared = System.getenv(Run.Surface.VARIABLE);
    check(
        "the surface follows the variable",
        Run.Surface.detect()
            == ("true".equals(declared) ? Run.Surface.ACTIONS : Run.Surface.TERMINAL));
    check(
        "only a workflow runner is ephemeral",
        Run.Host.of(Run.Surface.ACTIONS, Run.Publication.PUBLISH) == Run.Host.EPHEMERAL);
    check(
        "a local run is never ephemeral",
        Run.Host.of(Run.Surface.ACTIONS, Run.Publication.LOCAL) == Run.Host.TRUSTED);
    check(
        "a terminal is never ephemeral",
        Run.Host.of(Run.Surface.TERMINAL, Run.Publication.PUBLISH) == Run.Host.TRUSTED
            && Run.Host.of(Run.Surface.TERMINAL, Run.Publication.LOCAL) == Run.Host.TRUSTED);
  }

  // -------------------------------------------------------------- guards

  /// The guards decide what may be published, so each one is checked against a real repository
  /// rather than against a mock of one.
  private static void guards() throws Exception {
    var workspace = Scratch.golem().create();
    var git = Git.open(workspace);
    var base = git.head();
    check("a fresh workspace is clean", git.tree() == Git.Tree.CLEAN);
    // One walk answers both questions, and a walk is a process.
    var nothing = git.workSince(base);
    check("no commits yet", nothing.isEmpty());
    check("an empty range stays at the base", nothing.head().equals(base));

    Files.writeString(workspace.resolve("note.txt"), "work in progress");
    check("uncommitted work is seen", git.tree() == Git.Tree.DIRTY);

    commit(workspace, "Add a note");
    check("workspace clean again", git.tree() == Git.Tree.CLEAN);
    var oneCommit = git.workSince(base);
    check("one commit since the base", oneCommit.commits().size() == 1);
    check("commit subject is carried", oneCommit.commits().get(0).contains("Add a note"));
    check("changed file listed", oneCommit.files().contains("note.txt"));
    check("nothing protected touched", oneCommit.violations().isEmpty());
    check("the walk reports the head", git.head().startsWith(oneCommit.head()));

    commit(workspace, "Add another note", "second.txt", "more");
    var twoCommits = git.workSince(base);
    check("two commits since the base", twoCommits.commits().size() == 2);
    check("commits are oldest first", twoCommits.commits().get(0).contains("Add a note"));
    check(
        "files are collected across commits",
        twoCommits.files().containsAll(List.of("note.txt", "second.txt")));

    var smuggled =
        List.of(
            ".golems/smuggled.md", "tools/golems/Smuggled.java", ".github/workflows/smuggled.yml");
    var slipped = smuggle(smuggled);
    smuggled.forEach(path -> check("protected path caught: " + path, !slipped.contains(path)));

    // A merge commit lists no files of its own, so the walk has to notice it and
    // ask again. Without that, a protected path could ride into the branch inside
    // a merge and be reported as nothing changed.
    var beforeMerge = git.head();
    shell(workspace, "git", "checkout", "-q", "-b", "side");
    commit(workspace, "Smuggle through a side branch", ".github/workflows/side.yml", "nope");
    shell(workspace, "git", "checkout", "-q", "-");
    git(workspace, "merge", "--no-ff", "-q", "--no-verify", "-m", "Merge the side branch", "side");
    var merged = git.workSince(beforeMerge);
    check("a merge is still walked", merged.commits().size() == 2);
    check("a protected path inside a merge is caught", !merged.violations().isEmpty());

    var beforeRename = git.head();
    Proc.capture(List.of("git", "mv", "note.txt", ".golems/moved.md"), workspace);
    commit(workspace, "Move a file into the control plane");
    check(
        "rename into the control plane caught",
        !git.workSince(beforeRename).violations().isEmpty());
  }

  /// Commits each path in a repository of its own, and reports the ones the guard did not object
  /// to.
  ///
  /// The paths are independent claims about the same guard rather than a story, so they are
  /// attempted at once. A fixture repository is a copy and costs nothing; what the suite waits on
  /// is the commit, and three commits in a row was the longest stretch of the longest section.
  private static List<String> smuggle(List<String> paths) throws Exception {
    var slipped = new ArrayList<String>();
    try (var pool = Executors.newFixedThreadPool(paths.size())) {
      var pending = new LinkedHashMap<String, Future<Git.Work>>();
      for (var path : paths) {
        pending.put(
            path,
            pool.submit(
                () -> {
                  var workspace = Scratch.golem().create();
                  var git = Git.open(workspace);
                  var before = git.head();
                  commit(workspace, "Touch " + path, path, "nope");
                  return git.workSince(before);
                }));
      }
      for (var entry : pending.entrySet()) {
        if (entry.getValue().get().violations().isEmpty()) {
          slipped.add(entry.getKey());
        }
      }
    }
    return List.copyOf(slipped);
  }

  // ------------------------------------------------------------ branches

  /// Where a run starts from, which is a separate story from what it may publish.
  ///
  /// This is its own section because it shares no repository with the guards: keeping it there made
  /// the suite's longest chain of git processes longer still, and a section is the unit of
  /// parallelism.
  ///
  /// Every fixture here is the plain one, because a branch is asked for by the caller rather than
  /// read from the golem file: a fixture shaped differently would only be another repository to
  /// build.
  private static void branches() throws Exception {
    var normalGit = Git.open(Scratch.golem().create());
    var prepared = normalGit.prepare(Git.Remote.SKIP);
    check("normal mode keeps the branch", prepared.branch().equals(normalGit.position().branch()));
    check("normal mode pins a base", prepared.base().equals(normalGit.head()));

    var source = Scratch.golem().create();
    var branched = Git.open(source);
    var original = branched.position();
    var staged =
        branched.stage(
            "golem/x", Git.Remote.SKIP, source.resolveSibling(source.getFileName() + "-candidate"));
    check("original branch and head preserved", branched.position().equals(original));
    check("original tree clean", branched.tree() == Git.Tree.CLEAN);
    check("candidate detached", staged.git().position().branch().equals("HEAD"));
    check("candidate starts at pinned base", staged.git().head().equals(staged.prepared().base()));
    check("creation is explained", staged.prepared().note().contains("created from"));
    shell(source, "git", "branch", "golem/x");
    shell(source, "git", "checkout", "-q", "golem/x");
    commit(source, "A different local base", "local.txt", "local");
    var localHead = branched.head();
    shell(source, "git", "checkout", "-q", original.branch());
    var again =
        branched.stage(
            "golem/x", Git.Remote.SKIP, source.resolveSibling(source.getFileName() + "-second"));
    check("existing branch reused", again.prepared().note().contains("local branch"));
    check("existing local head pinned", again.prepared().base().equals(localHead));
    check("original checkout still pinned", branched.position().equals(original));

    check(
        "a non-repository is rejected",
        throwsRuntime(() -> Git.open(Files.createTempDirectory("golem-not-a-repo"))));
  }

  // -------------------------------------------------------------- remote

  /// Branch ownership is collaborative: a run continues from whatever the remote holds, and a push
  /// that would overwrite unseen work is refused rather than forced.
  private static void remote() throws Exception {
    var origin = Scratch.golem().published();
    var first = Scratch.golem().tracking(origin);
    var firstGit = Git.open(first);
    check("remote detected", firstGit.hasRemote());
    // The first clone only has to get onto the branch, so it does that directly.
    // Preparing it the way a run would costs a fetch and three more processes to
    // discover what this fixture already knows.
    shell(first, "git", "checkout", "-q", "-b", "golem/probe");
    commit(first, "Work from the first clone", "first.txt", "one");
    check("push accepted", firstGit.push("golem/probe").ok());

    var second = Scratch.golem().tracking(origin);
    var secondGit = Git.open(second);
    var original = secondGit.position();
    var staged =
        secondGit.stage(
            "golem/probe",
            Git.Remote.USE,
            second.resolveSibling(second.getFileName() + "-candidate"));
    var candidate = staged.git();
    check(
        "continues from the remote head", staged.prepared().note().contains("origin/golem/probe"));
    check(
        "runner checkout untouched",
        secondGit.position().equals(original) && secondGit.tree() == Git.Tree.CLEAN);
    check(
        "foreign commit preserved",
        candidate.git("log", "--format=%s", "-1").contains("Work from the first clone"));

    shell(second, "git", "branch", "golem/probe", "origin/golem/probe");
    shell(second, "git", "checkout", "-q", "golem/probe");
    commit(second, "An unpublished local commit", "local.txt", "local");
    var localHead = secondGit.head();
    var refused = second.resolveSibling(second.getFileName() + "-refused");
    check(
        "unpublished local commit blocks staging",
        throwsRuntime(() -> secondGit.stage("golem/probe", Git.Remote.USE, refused))
            && !Files.exists(refused));
    check("rejected staging leaves local head intact", secondGit.head().equals(localHead));

    commit(first, "A commit the second clone never saw", "third.txt", "three");
    firstGit.push("golem/probe");
    commit(candidate.root(), "A commit that would overwrite it", "other.txt", "two");
    check("diverged push refused", !candidate.push("golem/probe").ok());
    check(
        "history not rewritten to force it",
        candidate.git("log", "--format=%s", "-1").contains("would overwrite"));
  }

  // ------------------------------------------------------------ outcomes

  /// The specification's outcome table, executed. Each scenario drives the real entry point, so a
  /// change to the decision order fails here.
  private static void outcomes() throws Exception {
    // Each scenario owns a repository nothing else can see, so the slow part of
    // this section, which is waiting for child processes, happens at once.
    // Assertions stay sequential and in declared order, so a failure still reads
    // as a list rather than as a race.
    var table =
        List.of(
            new Scenario("changed", "changed", 0, "changed", Scratch.golem()),
            new Scenario("no change", "no_change", 0, "no_change", Scratch.golem()),
            new Scenario("uncommitted leftovers", "dirty", 1, "half-applied", Scratch.golem()),
            new Scenario(
                "control plane touched", "protected", 1, "protected path", Scratch.golem()),
            new Scenario("turn limit", "max_turns", 0, "turn limit", Scratch.golem()),
            new Scenario(
                "unfinished commits", "max_turns_work", 1, "unfinished committed", Scratch.golem()),
            new Scenario("agent failure", "fail", 1, "agent failed", Scratch.golem()),
            new Scenario("quota exhausted", "quota", 1, "quota exhausted", Scratch.golem()),
            new Scenario("flooded output", "flood", 0, "changed", Scratch.golem()),
            new Scenario(
                "verification passes",
                "changed",
                0,
                "changed",
                // Plumbing, not porcelain: git's human-readable status costs about
                // thirty seconds on a Windows workstation with endpoint protection,
                // against a quarter of a second for the same question asked the
                // machine-readable way. A fixture must not be the slowest thing here.
                Scratch.golem().verify("git status --porcelain")),
            new Scenario(
                "verification fails",
                "changed",
                1,
                "verification failed",
                Scratch.golem().verify("git rev-parse --verify no-such-ref")),
            new Scenario(
                "verification rewrites candidate",
                "changed",
                1,
                "verification changed the candidate",
                Scratch.golem().verify("git reset --hard HEAD^")));

    var narrated =
        List.of(
            new Scenario("budget", "hang", Scratch.golem().timeout("5s")),
            new Scenario("normal mode", "changed", Scratch.golem().normalMode()),
            new Scenario("version mismatch", "changed", Scratch.golem())
                .with("GOLEM_CLAUDE_VERSION", "0.0.0-not-installed"));

    var runs = execute(concat(table, narrated));

    for (var scenario : table) {
      var run = runs.get(scenario.label());
      var ok = run.exit() == scenario.exit() && run.outcomeLine().contains(scenario.expected());
      check(scenario.label() + " -> " + scenario.expected(), ok);
      if (!ok) {
        JOURNAL.get().note("exit " + run.exit() + ", " + run.outcomeLine());
      }
    }

    var timeout = runs.get("budget").output();
    check(
        "budget expiry is incomplete",
        timeout.contains("incomplete") && timeout.contains("budget expired"));

    var normal = runs.get("normal mode").output();
    check("normal mode needs no branch", normal.contains("outcome  changed"));
    check("normal mode says so", normal.contains("normal mode, no branch"));

    var mismatch = runs.get("version mismatch").output();
    check(
        "version mismatch blocks before launch",
        mismatch.contains("blocked") && mismatch.contains("version mismatch"));

    // The report is read from the run that already proved the outcome. A second
    // run with the same fake, the same fixture, and the same environment would
    // be the same process twice, and a process is what this section waits on.
    var reported = runs.get("changed").output();
    check("report names the commit", reported.contains("Add a note the fake agent left"));
    check("report carries cost", reported.contains("$0.31"));
    check("report carries tokens", reported.contains("41.2k in"));
    check("report carries the trace", reported.contains("trace"));
    check("local run publishes nothing", reported.contains("skipped          --local"));
    var original = Git.open(runs.get("changed").workspace());
    check(
        "agent left runner checkout unchanged",
        original.tree() == Git.Tree.CLEAN
            && !original.position().branch().equals("golem/probe")
            && !Files.exists(original.root().resolve("GOLEM.txt")));
    var candidateLine =
        reported
            .lines()
            .filter(line -> line.stripLeading().startsWith("candidate "))
            .findFirst()
            .orElseThrow();
    var candidate = Git.open(Path.of(candidateLine.replaceFirst("^\\s*candidate\\s+", "")));
    check(
        "agent committed inside detached worktree",
        candidate.position().branch().equals("HEAD")
            && Files.exists(candidate.root().resolve("GOLEM.txt"))
            && !candidate.workSince(original.head()).isEmpty());
  }

  /// One unattended run, described before it happens.
  ///
  /// `exit` and `expected` are the outcome table's claim about the run, and are unused by the
  /// scenarios that are read as prose instead.
  private record Scenario(
      String label,
      String fake,
      int exit,
      String expected,
      Scratch scratch,
      Map<String, String> environment) {
    Scenario(String label, String fake, int exit, String expected, Scratch scratch) {
      this(label, fake, exit, expected, scratch, Map.of());
    }

    Scenario(String label, String fake, Scratch scratch) {
      this(label, fake, 0, "", scratch, Map.of());
    }

    Scenario with(String key, String value) {
      return new Scenario(label, fake, exit, expected, scratch, Map.of(key, value));
    }

    /// Builds the repository and runs the golem in it.
    Finished execute() throws Exception {
      var workspace = scratch.create();
      var command = new ArrayList<>(compiledLaunch());
      command.addAll(
          List.of("run", "--golem", "probe", "--local", "--workspace", workspace.toString()));
      var builder =
          new ProcessBuilder(command).directory(REPOSITORY.toFile()).redirectErrorStream(true);
      builder.environment().put("GOLEM_FAKE", fake);
      builder.environment().put("GOLEM_CLAUDE", fakeAgent());
      builder.environment().putAll(environment);
      builder.environment().remove("GITHUB_ACTIONS");
      var process = builder.start();
      var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      process.waitFor(10, TimeUnit.MINUTES);
      return new Finished(label, process.exitValue(), output, workspace);
    }
  }

  /// What one scenario produced.
  private record Finished(String label, int exit, String output, Path workspace) {
    String outcomeLine() {
      return output.lines().filter(line -> line.startsWith("outcome ")).findFirst().orElse("");
    }
  }

  /// Runs every scenario at once and returns them by label.
  ///
  /// A scenario is almost entirely waiting: for a JVM to start, for git to fork, for a fake agent
  /// to finish. Running them one after another spends the suite's time on process creation rather
  /// than on checking anything.
  private static Map<String, Finished> execute(List<Scenario> scenarios) throws Exception {
    var runs = new LinkedHashMap<String, Finished>();
    try (var pool = Executors.newFixedThreadPool(Math.min(PARALLEL_SCENARIOS, scenarios.size()))) {
      var pending = scenarios.stream().map(scenario -> pool.submit(scenario::execute)).toList();
      for (var future : pending) {
        var run = future.get();
        runs.put(run.label(), run);
      }
    }
    assert runs.size() == scenarios.size() : "every scenario is labelled once";
    return runs;
  }

  private static List<Scenario> concat(List<Scenario> first, List<Scenario> second) {
    var all = new ArrayList<>(first);
    all.addAll(second);
    return all;
  }

  // -------------------------------------------------------- command line

  /// The command line is the first thing a stranger meets, so its failures have to be as clear as
  /// its successes.
  private static void commandLine() throws Exception {
    // This section drives the real repository, so it asks which golems it
    // defines rather than naming one. A check that hardcodes a golem name breaks
    // the day that golem is retired, which says nothing about the command line.
    var defined = Golem.discover(REPOSITORY);
    var any = defined.isEmpty() ? "" : defined.getFirst().name();

    var invocations = new LinkedHashMap<String, List<String>>();
    // The one launch that still goes through source-file mode, because that is
    // the documented way to run the orchestrator and it has to keep working.
    invocations.put("documented", cli(SOURCE_LAUNCH, List.of("--help")));
    invocations.put("help", cli(List.of("--help")));
    invocations.put("bare", cli(List.of()));
    invocations.put("unknown command", cli(List.of("frobnicate")));
    invocations.put("unknown option", cli(List.of("select", "--nope", "x")));
    invocations.put("missing value", cli(List.of("select", "--event")));
    invocations.put("no event", cli(List.of("select")));
    invocations.put("no golem", cli(List.of("run")));
    invocations.put("unknown golem", cli(List.of("run", "--golem", "nope", "--local")));
    invocations.put(
        "selection", cli(List.of("select", "--event", "schedule", "--at", "2026-09-14T03:00:00Z")));
    invocations.put(
        "dispatch", cli(List.of("select", "--event", "workflow_dispatch", "--golem", any)));
    invocations.put("undirected", cli(List.of("select", "--event", "workflow_dispatch")));

    var runs = launch(invocations);

    var documented = runs.get("documented");
    check(
        "the documented launch works",
        documented.exit() == 0 && documented.out().contains("usage:"));

    var help = runs.get("help");
    check("help succeeds", help.exit() == 0);
    check("help lists commands", help.out().contains("select") && help.out().contains("run"));
    check(
        "help lists frontmatter",
        help.out().contains("timeout") && help.out().contains("triggers"));
    check("help lists the environment", help.out().contains("GH_TOKEN"));
    check("bare invocation prints help", runs.get("bare").out().contains("usage:"));

    check("unknown command fails", runs.get("unknown command").exit() == 2);
    check("unknown option fails", runs.get("unknown option").exit() == 2);
    check("missing value fails", runs.get("missing value").exit() == 2);
    check("select without event fails", runs.get("no event").exit() == 2);
    check("run without golem fails", runs.get("no golem").exit() == 2);
    check("unknown golem fails", runs.get("unknown golem").exit() == 2);

    check("the repository defines a golem to select", !defined.isEmpty());

    var selection = runs.get("selection");
    check("selection succeeds", selection.exit() == 0);
    check("selection explains each golem", selection.out().contains(any));
    check("selection ends in a matrix", lastLine(selection.out()).startsWith("["));

    check(
        "dispatch selects the named golem",
        lastLine(runs.get("dispatch").out()).contains("\"golem\":\"" + any + "\""));
    check(
        "dispatch without a name selects nothing",
        lastLine(runs.get("undirected").out()).equals("[]"));
  }

  /// Runs every invocation at once and returns them by label.
  ///
  /// The command line is read before anything happens, so no invocation can observe what another
  /// did, and each one is a JVM start, which is the most expensive thing this machine does. Twelve
  /// of them in a row was the second longest thing in the suite.
  private static Map<String, Proc.Result> launch(Map<String, List<String>> invocations)
      throws Exception {
    var runs = new LinkedHashMap<String, Proc.Result>();
    try (var pool =
        Executors.newFixedThreadPool(Math.min(PARALLEL_SCENARIOS, invocations.size()))) {
      var pending = new LinkedHashMap<String, Future<Proc.Result>>();
      invocations.forEach(
          (label, command) ->
              pending.put(
                  label, pool.submit(() -> Proc.run(command, REPOSITORY, Map.of(), CLI_BUDGET))));
      for (var entry : pending.entrySet()) {
        runs.put(entry.getKey(), entry.getValue().get());
      }
    }
    assert runs.size() == invocations.size() : "every invocation is labelled once";
    return runs;
  }

  // ------------------------------------------------------------ fixtures

  private static List<String> cli(List<String> arguments) {
    return cli(compiledLaunch(), arguments);
  }

  private static List<String> cli(List<String> launch, List<String> arguments) {
    var command = new ArrayList<>(launch);
    command.addAll(arguments);
    return List.copyOf(command);
  }

  /// Compiles the orchestrator and the fake agent into one throwaway directory.
  ///
  /// `javac` is called in this process rather than as another JVM, because the point of compiling
  /// once is to stop paying for compilers.
  private static Path compile() {
    try {
      var classes = Files.createTempDirectory("golem-classes");
      var sources = new ArrayList<String>();
      try (var files = Files.list(REPOSITORY.resolve("tools/golems"))) {
        files
            .map(Path::toString)
            .filter(file -> file.endsWith(".java"))
            .filter(file -> !file.endsWith("SelfCheck.java"))
            .forEach(sources::add);
      }

      var arguments = new ArrayList<>(List.of("-d", classes.toString(), "-proc:none", "-nowarn"));
      arguments.addAll(sources);
      var javac =
          ToolProvider.findFirst("javac")
              .orElseThrow(() -> new IllegalStateException("no javac in this runtime"));
      var status = javac.run(System.out, System.err, arguments.toArray(String[]::new));
      if (status != 0) {
        throw new IllegalStateException("the orchestrator does not compile");
      }
      return classes;
    } catch (IOException failure) {
      throw new UncheckedIOException("cannot compile the orchestrator", failure);
    }
  }

  /// A throwaway repository holding one golem, so no scenario can observe another.
  ///
  /// Unset parts are null rather than empty optionals: this is a fixture, and the readable call
  /// site is the point. `Scratch.golem()` is the shape almost every check wants, and the rest name
  /// only what they change.
  private record Scratch(String timeout, String branch, String verify) {
    /// One built repository per distinct frontmatter, shared by every check that wants that shape.
    /// Concurrent because scenarios run at once.
    private static final Map<String, Path> TEMPLATES = new ConcurrentHashMap<>();

    /// An empty directory handed to `git init` as its template.
    ///
    /// Git otherwise copies fourteen sample hooks into every repository it creates. They are inert,
    /// and the suite then copies them again for every scenario, so they are pure cost: the fixture
    /// drops from forty-seven entries to twenty-nine, and copying it gets a third cheaper.
    private static final Path NO_TEMPLATE = emptyDirectory();

    private static Path emptyDirectory() {
      try {
        return Files.createTempDirectory("golem-no-template");
      } catch (IOException failure) {
        throw new UncheckedIOException("cannot create an empty template directory", failure);
      }
    }

    static Scratch golem() {
      return new Scratch("2m", "golem/probe", null);
    }

    Scratch timeout(String value) {
      return new Scratch(value, branch, verify);
    }

    /// A golem that owns no branch and works where it already is.
    Scratch normalMode() {
      return new Scratch(timeout, null, verify);
    }

    Scratch verify(String value) {
      return new Scratch(timeout, branch, value);
    }

    /// A repository per call, built by copying one that was built before.
    ///
    /// Creating a repository costs three `git` processes, and process creation is what this suite
    /// spends its wall clock on. Repositories with identical frontmatter are indistinguishable, so
    /// the first one of each shape is built and the rest are copied.
    Path create() throws Exception {
      var template = TEMPLATES.computeIfAbsent(frontmatter(), Scratch::build);
      var workspace = Files.createTempDirectory("golem-check").resolve("repo");
      copy(template, workspace);
      return workspace;
    }

    /// A bare repository holding what a clone of this fixture would find.
    ///
    /// A bare repository is a `.git` directory that says it is bare, so this is a copy and one line
    /// of configuration. Creating an empty remote and pushing a first branch into it was three
    /// processes, and that push was the single most expensive thing the suite did.
    Path published() throws Exception {
      var origin = Files.createTempDirectory("golem-origin").resolve("origin.git");
      copy(TEMPLATES.computeIfAbsent(frontmatter(), Scratch::build).resolve(".git"), origin);
      // The index describes a working tree a bare repository does not have.
      Files.deleteIfExists(origin.resolve("index"));
      var config = origin.resolve("config");
      Files.writeString(config, Files.readString(config).replace("bare = false", "bare = true"));
      return origin;
    }

    /// A workspace that already knows a remote, without cloning from it.
    ///
    /// A clone is a copy plus two lines of configuration, and the copy is free here because the
    /// fixture it would produce already exists. Whatever the remote holds beyond the template
    /// arrives with the fetch the run makes anyway.
    Path tracking(Path origin) throws Exception {
      var workspace = create();
      var config = workspace.resolve(".git").resolve("config");
      // Git reads a backslash in a configuration value as an escape, and accepts
      // forward slashes in a path on every platform.
      Text.append(
          config,
          "[remote \"origin\"]"
              + System.lineSeparator()
              + "\turl = "
              + origin.toString().replace('\\', '/')
              + System.lineSeparator()
              + "\tfetch = +refs/heads/*:refs/remotes/origin/*"
              + System.lineSeparator());
      return workspace;
    }

    private static Path build(String frontmatter) {
      try {
        var workspace = Files.createTempDirectory("golem-template").resolve("repo");
        Files.createDirectories(workspace.resolve(".golems"));
        Files.writeString(workspace.resolve("README.md"), "scratch" + System.lineSeparator());
        Files.writeString(workspace.resolve(".golems").resolve("probe.md"), frontmatter);

        // Identity is passed to the one command that needs it rather than written
        // by two more `git config` processes.
        shell(workspace, "git", "init", "-q", "--template=" + NO_TEMPLATE, "-b", "trunk", ".");
        commit(workspace, "init");
        return workspace;
      } catch (Exception failure) {
        throw new IllegalStateException("cannot build a scratch repository", failure);
      }
    }

    private static void copy(Path template, Path workspace) throws Exception {
      try (var entries = Files.walk(template)) {
        for (var source : entries.toList()) {
          var target = workspace.resolve(template.relativize(source).toString());
          if (Files.isDirectory(source)) {
            Files.createDirectories(target);
          } else {
            Files.createDirectories(target.getParent());
            Files.copy(source, target);
          }
        }
      }
    }

    private String frontmatter() {
      var text = new StringBuilder("---" + System.lineSeparator());
      if (branch != null) {
        text.append("branch: ").append(branch).append(System.lineSeparator());
      }
      if (verify != null) {
        text.append("verify: ").append(verify).append(System.lineSeparator());
      }
      text.append("timeout: ").append(timeout).append(System.lineSeparator());
      text.append("---").append(System.lineSeparator()).append(System.lineSeparator());
      text.append("Do the probe task.").append(System.lineSeparator());
      return text.toString();
    }
  }

  /// Commits everything in a fixture repository.
  ///
  /// The identity travels with the command rather than being configured into the repository, so a
  /// fixture needs no `git config` process and does not quietly depend on whoever happens to be
  /// logged in. Hooks are skipped because a throwaway repository has none worth running, and
  /// looking for them is a measurable part of what a commit costs here.
  private static void commit(Path workspace, String message) throws Exception {
    shell(workspace, "git", "add", "-A");
    git(workspace, "commit", "-q", "--no-verify", "-m", message);
  }

  private static void commit(Path workspace, String message, String file, String content)
      throws Exception {
    var target = workspace.resolve(file);
    Files.createDirectories(target.getParent());
    Files.writeString(target, content + System.lineSeparator(), StandardCharsets.UTF_8);
    commit(workspace, message);
  }

  /// Runs git as the fixture author.
  private static void git(Path directory, String... arguments) throws Exception {
    var command = new ArrayList<String>();
    command.add("git");
    command.addAll(FIXTURE_CONFIG);
    command.addAll(List.of(arguments));
    shell(directory, command.toArray(String[]::new));
  }

  private static void shell(Path directory, String... command) throws Exception {
    var process =
        new ProcessBuilder(command)
            .directory(directory.toFile())
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start();
    if (process.waitFor() != 0) {
      throw new IllegalStateException(String.join(" ", command) + " failed in " + directory);
    }
  }

  private static Run run(Run.Host host, Run.Platform platform) throws Exception {
    var directory = Files.createTempDirectory("golem-run");
    return new Run(
        UUID.randomUUID(),
        directory,
        REPOSITORY.resolve("tools/golems"),
        directory,
        Run.Publication.LOCAL,
        host,
        platform,
        "claude",
        "41898282+github-actions[bot]@users.noreply.github.com");
  }

  private static Golem parse(String content) throws Exception {
    var file = Files.createTempDirectory("golem-parse").resolve("probe.md");
    Files.writeString(file, content, StandardCharsets.UTF_8);
    return Golem.read(file);
  }

  private static void rejects(String label, String content) {
    check("rejects " + label, throwsRuntime(() -> parse(content)));
  }

  private static ZonedDateTime monday() {
    return ZonedDateTime.parse("2026-09-14T03:00:00Z");
  }

  private static ZonedDateTime thursday() {
    return ZonedDateTime.parse("2026-09-17T03:00:00Z");
  }

  private static String lastLine(String text) {
    var lines = text.strip().split("\r?\n");
    return lines[lines.length - 1].strip();
  }

  /// Assertions are the orchestrator's statements about its own invariants, so the self-check runs
  /// with them enabled and asserts that they fire.
  private static boolean throwsError(Attempt attempt) {
    try {
      attempt.execute();
      return false;
    } catch (AssertionError expected) {
      return true;
    } catch (Exception unexpected) {
      return false;
    }
  }

  private static boolean throwsRuntime(Attempt attempt) {
    try {
      attempt.execute();
      return false;
    } catch (RuntimeException expected) {
      return true;
    } catch (Exception unexpected) {
      return false;
    }
  }

  private static boolean quiet(Attempt attempt) {
    try {
      attempt.execute();
      return true;
    } catch (Exception failure) {
      return false;
    }
  }

  private interface Attempt {
    void execute() throws Exception;
  }

  private static void check(String label, boolean ok) {
    JOURNAL.get().check(label, ok);
  }
}
