import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/// Entry point of the golem orchestrator.
///
/// Two commands, used identically on a laptop and on a runner: `select` decides which golems a
/// wake-up applies to, and `run` executes one of them. Everything the orchestrator learns is
/// printed as it happens, and the same facts are rendered once more as the run report at the end.
public final class Golems {
  private static final String VERSION_VARIABLE = "GOLEM_CLAUDE_VERSION";
  private static final String ORCHESTRATOR_DIRECTORY = "tools/golems";

  /// The CLI's own name for a run that ran out of turns, and the HTTP status it reports when an
  /// account has nothing left to spend.
  private static final String MAX_TURNS_SUBTYPE = "error_max_turns";

  private static final int QUOTA_STATUS = 429;

  /// What the orchestrator itself needs, beyond whatever the golem's task uses.
  private static final List<String> REQUIRED_TOOLS = List.of("git", "gh");

  private static final int SHORT_SHA = 7;
  private static final Duration VERIFY_BUDGET = Duration.ofMinutes(30);
  private static final Duration PREFLIGHT_BUDGET = Duration.ofMinutes(1);

  private static final String USAGE =
      """
      golems - run bounded, unattended maintenance tasks

      usage:
        java tools/golems/Golems.java select --event <event> [--golem <name>] [--at <iso-instant>]
        java -ea tools/golems/Golems.java run --golem <name> [--local] [--workspace <dir>]
        java -ea tools/golems/Golems.java auth-check --golem <name>
        java -ea tools/golems/Golems.java --help

      commands:
        select    print why each golem is due or skipped, then one line of matrix JSON
        run       execute one golem: prepare, agent, verify, publish
        auth-check  probe subscription with the task's model, without tools

      options:
        --event <event>   the wake-up: schedule, or a forge event such as pull_request_review
        --golem <name>    restrict selection to one golem, or name the golem to run
        --at <instant>    evaluate the schedule at this UTC instant instead of now
        --local           run everything up to and including the guards, publish nothing
        --workspace <dir> repository to work in, default the current directory
        --help            print this text

      golems are discovered in .golems/<name>.md, with strict frontmatter:

      %s
      schedule and triggers are independent; a golem with neither is manual only.
      workflow_dispatch is always available and is never listed in triggers.

      required tools:
        git, gh, and whatever the golem's own task needs

      environment:
        GH_TOKEN                 forge credential, required to publish
        CLAUDE_CODE_OAUTH_TOKEN  subscription credential for Claude tasks; API keys forbidden
        GOLEM_CLAUDE             claude executable, default: claude
        GOLEM_CLAUDE_VERSION     required version; a mismatch blocks the run
        GOLEM_COMMIT_EMAIL       address used for golem commits

      exit codes:
        0  the lifecycle completed, whatever the golem decided
        1  the run is blocked and needs a human
        2  the invocation itself was wrong
      """
          .formatted(Field.help());

  private Golems() {}

  public static void main(String[] arguments) {
    try {
      var args = Args.parse(arguments);
      if (args.help()) {
        IO.println(USAGE);
        return;
      }
      switch (args.command()) {
        case SELECT -> select(args);
        case RUN -> System.exit(run(args).code());
        case AUTH_CHECK -> System.exit(authCheck(args).code());
        case HELP -> IO.println(USAGE);
      }
    } catch (IllegalStateException blocked) {
      System.err.println("golems: blocked: " + blocked.getMessage());
      System.exit(Outcome.ExitCode.BLOCKED.code());
    } catch (RuntimeException failure) {
      fail(failure.getMessage());
    }
  }

  private static Outcome.ExitCode authCheck(Args args) {
    var name =
        args.value("golem")
            .orElseThrow(() -> new IllegalArgumentException("auth-check needs --golem"));
    var golem =
        Golem.discover(args.workspace()).stream()
            .filter(candidate -> candidate.name().equals(name))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("unknown golem '" + name + "'"));
    if (!Proc.onPath(golem.harness().cli()))
      throw new IllegalStateException(golem.harness().cli() + " is not on the path");
    var probe = "Reply with the single word OK. Do not use tools.";
    var command =
        switch (golem.harness()) {
          case PI ->
              List.of(
                  "pi",
                  "--print",
                  "--no-session",
                  "--no-tools",
                  "--no-extensions",
                  "--no-skills",
                  "--no-prompt-templates",
                  "--no-context-files",
                  "--model",
                  golem.model(),
                  "--thinking",
                  golem.effort(),
                  probe);
          case CODEX ->
              List.of(
                  "codex",
                  "exec",
                  "--ephemeral",
                  "--sandbox",
                  "read-only",
                  "--model",
                  golem.model(),
                  "--config",
                  "model_reasoning_effort=" + Json.encode(new Json.StringValue(golem.effort())),
                  probe);
          case CLAUDE ->
              List.of(
                  "claude",
                  "-p",
                  probe,
                  "--model",
                  golem.model(),
                  "--effort",
                  golem.effort(),
                  "--max-turns",
                  "1");
        };
    var result = Proc.run(command, args.workspace(), Map.of(), Duration.ofMinutes(3));
    if (!result.ok() || !result.out().strip().endsWith("OK"))
      throw new IllegalStateException("subscription authentication probe failed for " + name);
    IO.println("subscription authentication verified for " + name);
    return Outcome.ExitCode.COMPLETED;
  }

  /// Prints the selection decision for every golem, then the matrix.
  ///
  /// The reasoning is for people and the last line is for the workflow. A skipped golem is never
  /// silent, because "nothing ran last night" has to be explainable without reading logs.
  private static void select(Args args) {
    assert args != null : "selection needs its arguments";
    var root = args.workspace();
    var event =
        args.value("event").orElseThrow(() -> new IllegalArgumentException("select needs --event"));
    var tick =
        args.value("at")
            .map(ZonedDateTime::parse)
            .orElseGet(() -> ZonedDateTime.now(ZoneOffset.UTC));
    var only = args.value("golem");
    var dispatched = Golem.Event.DISPATCH.is(event);
    var discovered = Golem.discover(root);
    if (only.isPresent() && discovered.stream().noneMatch(task -> task.name().equals(only.get())))
      throw new IllegalArgumentException("unknown golem: " + only.get());

    IO.println("tick " + tick.withZoneSameInstant(ZoneOffset.UTC) + " event " + event);
    var due = new ArrayList<Golem>();
    for (var golem : discovered) {
      if (only.isPresent() && !only.get().equals(golem.name())) {
        continue;
      }
      // A dispatch names its golem, so it needs no activation of its own.
      var selected = dispatched ? only.isPresent() : golem.due(event, tick);
      IO.println(
          String.format(
              "  %-16s %s",
              golem.name(), dispatched ? "due: dispatched" : golem.reason(event, tick)));
      if (selected) {
        due.add(golem);
      }
    }
    var matrix = "[" + String.join(",", due.stream().map(Golem::toMatrixEntry).toList()) + "]";
    assert matrix.startsWith("[") && matrix.endsWith("]") : "a matrix is one array";
    IO.println(matrix);
  }

  /// Executes one golem and returns how the process should exit.
  ///
  /// A golem that found nothing to do has not failed, so only `blocked` exits non-zero: it is the
  /// state that needs a human.
  private static Outcome.ExitCode run(Args args) {
    assert args != null : "a run needs its arguments";
    var root = args.workspace();
    var name =
        args.value("golem").orElseThrow(() -> new IllegalArgumentException("run needs --golem"));
    var golem = Golem.require(root, name);
    var run = Run.create(root, orchestratorDirectory(), args.publication());
    var started = System.nanoTime();

    IO.println(
        "golem-"
            + golem.name()
            + "  trace "
            + run.traceId()
            + "  budget "
            + Report.duration(golem.timeout()));

    var blocker = preflight(run, golem);
    if (blocker.isPresent()) {
      return finish(golem, run, new Outcome.Blocked(blocker.get()), started);
    }

    group("prepare");
    var git = Git.open(root);
    if (git.tree() != Git.Tree.CLEAN)
      return finish(golem, run, new Outcome.Blocked("workspace was dirty before the run"), started);
    var remote = args.publication() == Run.Publication.PUBLISH ? Git.Remote.USE : Git.Remote.SKIP;
    Optional<Git.Staged> staged;
    try {
      staged = golem.branch().map(branch -> git.stage(branch, remote, run.candidateDirectory()));
    } catch (IllegalStateException failure) {
      endGroup();
      return finish(
          golem,
          run,
          new Outcome.Blocked("cannot stage candidate: " + failure.getMessage()),
          started);
    }
    var candidate = staged.map(Git.Staged::git).orElse(git);
    Git.Prepared prepared;
    try {
      prepared = staged.map(Git.Staged::prepared).orElseGet(() -> git.prepare(remote));
    } catch (IllegalStateException failure) {
      endGroup();
      return finish(
          golem, run, new Outcome.Blocked("cannot pin base: " + failure.getMessage()), started);
    }
    var workspace = candidate.root();
    line("workspace", workspace.toAbsolutePath().toString());
    line("branch", prepared.branch() + " @ " + shortSha(prepared.base()));
    line("base", prepared.note());
    line("identity", "golem-" + golem.name() + " <" + run.commitEmail() + ">");
    endGroup();

    group("agent");
    line("model", golem.model() + " / " + golem.effort());
    var agent = Agent.run(golem, workspace, run);
    line(
        "result",
        String.format(
            Locale.ROOT,
            "exit %d, subtype %s, %d turns, $%.2f",
            agent.exit(),
            agent.subtype(),
            agent.turns(),
            agent.costUsd()));
    endGroup();

    group("verify");
    var guards = inspect(golem, workspace, candidate, prepared);
    var work = guards.work();
    line("workspace", guards.tree() == Git.Tree.CLEAN ? "clean" : "uncommitted changes");
    line("commits", work.commits().size() + " since " + shortSha(prepared.base()));
    line("protected paths", work.violations().isEmpty() ? "ok" : work.violations().toString());
    line("repo checks", guards.verification().describe());
    endGroup();

    var progress = new Progress(candidate, prepared, agent, work);
    return finish(golem, run, progress, decide(agent, guards), started);
  }

  /// What a run produced once it got past preflight.
  ///
  /// These four travel together because they are only ever known together: a run that reached its
  /// agent has a repository, a pinned branch, and a result. Before that, none of them exist, which
  /// is a different overload rather than a row of empty values.
  private record Progress(Git git, Git.Prepared prepared, Agent.Result agent, Git.Work work) {
    Progress {
      assert git != null && prepared != null : "progress implies a prepared repository";
      assert agent != null : "progress implies the agent ran";
      assert work != null : "progress states what was committed, even if nothing was";
    }
  }

  /// What the guards observed about the candidate the agent left behind.
  private record Guards(Git.Tree tree, Git.Work work, Verification verification) {
    Guards {
      assert tree != null && work != null : "every guard reports something";
      assert verification != null : "the repository check always has a verdict";
    }
  }

  /// Runs every guard against the workspace the agent left behind.
  private static Guards inspect(Golem golem, Path root, Git git, Git.Prepared prepared) {
    var tree = git.tree();
    var head = git.head();
    var work = git.workSince(prepared.base());
    var verification = verify(golem, root, tree, work);
    if (!git.head().equals(head)
        || git.tree() != tree
        || !git.workSince(prepared.base()).equals(work))
      verification = new Verification.Failed("verification changed the candidate");
    return new Guards(git.tree(), work, verification);
  }

  /// Derives the outcome from what the orchestrator observed.
  ///
  /// The order is the specification's order, and nothing here reads the agent's prose: judgment
  /// stays human-readable, machine state stays derived.
  private static Outcome decide(Agent.Result agent, Guards guards) {
    assert agent != null && guards != null : "deciding needs its inputs";
    if (!guards.work().violations().isEmpty()) {
      return new Outcome.Blocked("protected path touched");
    }
    if (guards.tree() == Git.Tree.DIRTY) {
      return new Outcome.Blocked("run left work half-applied");
    }
    if (guards.verification() instanceof Verification.Failed failed) {
      return new Outcome.Blocked(failed.summary());
    }
    if ((agent.stopped() != Proc.Stop.NONE || MAX_TURNS_SUBTYPE.equals(agent.subtype()))
        && !guards.work().isEmpty())
      return new Outcome.Blocked("unfinished committed work was not published");
    if (agent.stopped() != Proc.Stop.NONE) return new Outcome.Incomplete("budget expired");
    if (MAX_TURNS_SUBTYPE.equals(agent.subtype()))
      return new Outcome.Incomplete("turn limit reached");
    if (!agent.succeeded())
      return new Outcome.Blocked(
          agent.apiErrorStatus() == QUOTA_STATUS ? "quota exhausted" : "agent failed");
    return guards.work().isEmpty() ? new Outcome.NoChange() : new Outcome.Changed();
  }

  /// What the repository's own check said about the candidate.
  sealed interface Verification {
    /// The golem declared no check, or there was nothing to check.
    record Skipped(String why) implements Verification {}

    record Passed() implements Verification {}

    record Failed(String summary) implements Verification {}

    default String describe() {
      return switch (this) {
        case Skipped skipped -> "skipped: " + skipped.why();
        case Passed _ -> "ok";
        case Failed failed -> failed.summary();
      };
    }
  }

  /// Runs the repository's own check, when the golem declares one.
  ///
  /// This is a guard rather than a convenience: it runs before anything is published, so a
  /// candidate that does not build never reaches a branch.
  private static Verification verify(Golem golem, Path root, Git.Tree tree, Git.Work work) {
    if (golem.verify().isEmpty()) {
      return new Verification.Skipped("no check declared");
    }
    if (work.isEmpty()) {
      return new Verification.Skipped("nothing to check");
    }
    if (tree == Git.Tree.DIRTY) {
      return new Verification.Skipped("workspace is dirty");
    }
    var command = List.of(golem.verify().get().split("\\s+"));
    var result = Proc.run(command, root, Map.of(), VERIFY_BUDGET);
    return result.ok()
        ? new Verification.Passed()
        : new Verification.Failed(
            "verification failed: " + String.join(" ", command) + " exited " + result.exit());
  }

  /// Reports a run that ended before it reached its agent, which is only ever a failed preflight.
  private static Outcome.ExitCode finish(Golem golem, Run run, Outcome outcome, long started) {
    var report = describe(golem, run, outcome, started);
    report.writeArtifacts(run);
    return render(report, run, outcome);
  }

  /// Publishes when allowed, then renders the report into every sink.
  private static Outcome.ExitCode finish(
      Golem golem, Run run, Progress progress, Outcome outcome, long started) {
    assert progress != null : "this overload is for runs that reached the agent";
    var commits = progress.work().commits();
    var agent = progress.agent();
    var report =
        describe(golem, run, outcome, started)
            // The head is the one the history walk already reported, so writing the
            // report costs no further process.
            .fact("branch", progress.prepared().branch() + " @ " + shortSha(progress.work().head()))
            .fact("candidate", progress.git().root().toAbsolutePath().toString())
            .fact(
                "commits",
                commits.isEmpty() ? "none" : commits.size() + ": " + String.join("; ", commits))
            .usage(golem.harness(), agent)
            .narrative(agent.narrative());

    try {
      publish(golem, run, progress, outcome, report);
    } catch (RuntimeException failure) {
      var blocked = new Outcome.Blocked("publication failed: " + failure.getMessage());
      var failed =
          describe(golem, run, blocked, started)
              .fact(
                  "branch", progress.prepared().branch() + " @ " + shortSha(progress.work().head()))
              .narrative(agent.narrative());
      failed.writeArtifacts(run, agent);
      return render(failed, run, blocked);
    }
    report.writeArtifacts(run, agent);
    return render(report, run, outcome);
  }

  /// The facts every run has, whatever it managed to do.
  private static Report describe(Golem golem, Run run, Outcome outcome, long started) {
    assert golem != null && run != null && outcome != null : "a report needs its inputs";
    var elapsed = Duration.ofNanos(System.nanoTime() - started);
    return new Report(golem.name(), outcome)
        .fact("outcome", outcome.label())
        .fact("host", run.host() + " / " + run.platform())
        .fact("model / effort", golem.model() + " / " + golem.effort())
        .fact("duration", Report.duration(elapsed) + " of " + Report.duration(golem.timeout()))
        .fact("trace", run.traceId().toString());
  }

  /// Writes the finished report to every sink the host offers.
  private static Outcome.ExitCode render(Report report, Run run, Outcome outcome) {
    group("report");
    IO.println(report.plain());
    endGroup();
    report.writeJobSummary();
    report.notice().ifPresent(IO::println);
    IO.println("artifacts " + run.directory());
    return outcome.exit();
  }

  /// Pushes the branch and updates the pull request and labels.
  ///
  /// Nothing here runs before the guards, and nothing here happens at all in a local run. A
  /// rejected push is reported, never forced.
  private static void publish(
      Golem golem, Run run, Progress progress, Outcome outcome, Report report) {
    group("publish");
    var git = progress.git();
    var branch = progress.prepared().branch();
    var refusal = refusal(run, golem, outcome);
    if (refusal.isPresent()) {
      line("skipped", refusal.get());
      endGroup();
      return;
    }

    var forge = Forge.open(run.workspace());
    if (branch.equals(forge.defaultBranch()))
      throw new IllegalStateException("cannot publish to the default branch");
    var existing = forge.findPullRequest(branch);
    existing.ifPresent(forge::requireResolvedReviews);
    var proposed = Text.readIfPresent(run.prFile());
    if (existing.isEmpty() && proposed.isBlank())
      throw new IllegalStateException("new branch requires pull-request text before push");
    if (progress.prepared().note().startsWith("continued from ")) {
      var remote = git.git("ls-remote", "--exit-code", "origin", "refs/heads/" + branch);
      if (!remote.startsWith(progress.prepared().base() + "\t"))
        throw new IllegalStateException("remote branch moved during the run");
    }
    var push = git.push(branch);
    if (!push.ok()) throw new IllegalStateException("remote branch rejected candidate push");
    line("push", "ok");
    var pullRequest =
        existing
            .or(() -> forge.createPullRequest(branch, proposed))
            .orElseThrow(() -> new IllegalStateException("pull request was not created"));
    forge.updateBody(pullRequest, report.mergeIntoBody(pullRequest.body()));
    forge.syncLabels(pullRequest, golem.name(), outcome);
    report.fact("pull request", pullRequest.url());
    line("pull request", pullRequest.url());
    endGroup();
  }

  private static Optional<String> refusal(Run run, Golem golem, Outcome outcome) {
    if (run.publication() == Run.Publication.LOCAL) {
      return Optional.of("--local");
    }
    if (golem.branch().isEmpty()) {
      return Optional.of("normal mode");
    }
    return outcome.publishable() ? Optional.empty() : Optional.of("nothing to publish");
  }

  /// Confirms the host can run this golem before the agent is launched.
  ///
  /// A preflight failure is blocked rather than failed, because it always needs a human: a missing
  /// tool, a wrong version, or a missing credential.
  private static Optional<String> preflight(Run run, Golem golem) {
    assert run != null : "preflight needs a run";
    if (!Files.isRegularFile(run.protocolFile())) {
      return Optional.of("the golem protocol is missing from " + run.orchestrator());
    }
    for (var tool : REQUIRED_TOOLS) {
      if (!Proc.onPath(tool)) return Optional.of(tool + " is not on the path");
    }
    if (!supportedVersion("git", List.of("git", "--version"), 2, 40))
      return Optional.of("git 2.40+ is required");
    if (!supportedVersion("gh", List.of("gh", "--version"), 2, 70))
      return Optional.of("gh 2.70+ is required");
    for (var variable : List.of("GIT_DIR", "GIT_WORK_TREE", "GIT_INDEX_FILE", "GIT_COMMON_DIR")) {
      if (System.getenv(variable) != null)
        return Optional.of(variable + " must not override the candidate workspace");
    }

    if (!(golem.harness() == Golem.Harness.CLAUDE && System.getenv("GOLEM_CLAUDE") != null)
        && !Proc.onPath(golem.harness().cli()))
      return Optional.of(golem.harness().cli() + " is not on the path");
    if (run.publication() == Run.Publication.PUBLISH
        && (System.getenv("GH_TOKEN") == null || System.getenv("GH_TOKEN").isBlank()))
      return Optional.of("GH_TOKEN is required to publish");
    if (golem.harness() == Golem.Harness.CLAUDE && System.getenv("ANTHROPIC_API_KEY") != null)
      return Optional.of("metered Anthropic API key is forbidden for this golem");
    if (golem.harness() == Golem.Harness.CLAUDE
        && System.getenv("GOLEM_CLAUDE") == null
        && (System.getenv("CLAUDE_CODE_OAUTH_TOKEN") == null
            || System.getenv("CLAUDE_CODE_OAUTH_TOKEN").isBlank()))
      return Optional.of("Claude subscription credential is required");
    if (golem.harness() != Golem.Harness.CLAUDE) return Optional.empty();
    var required = System.getenv(VERSION_VARIABLE);
    if (required == null || required.isBlank()) {
      return Optional.empty();
    }
    var command = new ArrayList<>(run.claudeArgv());
    command.add("--version");
    var result = Proc.run(command, run.workspace(), Map.of(), PREFLIGHT_BUDGET);
    if (!result.ok()) {
      return Optional.of("claude is not runnable");
    }
    return result.out().contains(required)
        ? Optional.empty()
        : Optional.of("claude version mismatch, expected " + required);
  }

  private static boolean supportedVersion(String tool, List<String> command, int major, int minor) {
    var result = Proc.run(command, Path.of("."), Map.of(), PREFLIGHT_BUDGET);
    if (!result.ok()) return false;
    var pattern = java.util.regex.Pattern.compile("\\b(\\d+)\\.(\\d+)\\.(\\d+)\\b");
    var version = pattern.matcher(result.out());
    if (!version.find() || !result.out().toLowerCase(Locale.ROOT).contains(tool)) return false;
    try {
      int foundMajor = Integer.parseInt(version.group(1));
      int foundMinor = Integer.parseInt(version.group(2));
      return foundMajor > major || foundMajor == major && foundMinor >= minor;
    } catch (NumberFormatException invalid) {
      return false;
    }
  }

  private static Path orchestratorDirectory() {
    var here = Path.of(ORCHESTRATOR_DIRECTORY);
    return Files.isDirectory(here) ? here : Path.of(".");
  }

  private static String shortSha(String sha) {
    return sha.length() > SHORT_SHA ? sha.substring(0, SHORT_SHA) : sha;
  }

  private static void line(String key, String value) {
    IO.println(String.format("  %-16s %s", key, value));
  }

  /// Collapses a phase in the runner log and prints a plain heading elsewhere, so the same output
  /// reads well in a terminal and on a run page.
  private static void group(String title) {
    IO.println(
        Run.Surface.detect().isActions()
            ? "::group::" + title
            : System.lineSeparator() + "▸ " + title);
  }

  private static void endGroup() {
    if (Run.Surface.detect().isActions()) {
      IO.println("::endgroup::");
    }
  }

  private static void fail(String message) {
    System.err.println("golems: " + message);
    System.err.println();
    System.err.println(USAGE);
    System.exit(Outcome.ExitCode.USAGE.code());
  }

  /// The commands the orchestrator offers.
  private enum Command {
    SELECT,
    RUN,
    AUTH_CHECK,
    HELP;

    static Command of(String value) {
      return switch (value.toLowerCase(Locale.ROOT)) {
        case "select" -> SELECT;
        case "run" -> RUN;
        case "auth-check" -> AUTH_CHECK;
        case "help" -> HELP;
        default -> throw new IllegalArgumentException("unknown command '" + value + "'");
      };
    }
  }

  /// Command line of the orchestrator: one command, then `--key value` options and `--flag`
  /// switches.
  ///
  /// Deliberately small. A golem declares its own behavior in frontmatter rather than on a command
  /// line, so this parser never has to grow.
  private record Args(Command command, Map<String, String> values, List<String> flags) {
    private static final List<String> KNOWN_FLAGS = List.of("local", "help");
    private static final List<String> KNOWN_VALUES = List.of("event", "golem", "at", "workspace");

    static Args parse(String[] arguments) {
      assert arguments != null : "arguments are required";
      if (arguments.length == 0 || arguments[0].equals("--help")) {
        return new Args(Command.HELP, Map.of(), List.of("help"));
      }

      var command = Command.of(arguments[0]);
      var values = new LinkedHashMap<String, String>();
      var flags = new ArrayList<String>();
      for (var index = 1; index < arguments.length; index++) {
        var argument = arguments[index];
        if (!argument.startsWith("--")) {
          throw new IllegalArgumentException("unexpected argument '" + argument + "'");
        }
        var key = argument.substring(2);
        if (KNOWN_FLAGS.contains(key)) {
          flags.add(key);
        } else if (KNOWN_VALUES.contains(key)) {
          if (index + 1 >= arguments.length) {
            throw new IllegalArgumentException("--" + key + " needs a value");
          }
          values.put(key, arguments[++index]);
        } else {
          throw new IllegalArgumentException("unknown option '--" + key + "'");
        }
      }
      return new Args(command, Map.copyOf(values), List.copyOf(flags));
    }

    boolean help() {
      return flags.contains("help") || command == Command.HELP;
    }

    Run.Publication publication() {
      return flags.contains("local") ? Run.Publication.LOCAL : Run.Publication.PUBLISH;
    }

    Optional<String> value(String name) {
      return Optional.ofNullable(values.get(name));
    }

    Path workspace() {
      return value("workspace").map(Path::of).orElseGet(() -> Path.of("."));
    }
  }
}
