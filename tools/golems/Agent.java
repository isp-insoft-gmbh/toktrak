import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/// Launches Claude Code and reads back what it did.
///
/// The agent is given the repository toolchain and its own credential, and nothing about
/// publication. It writes files, commits, and text; the orchestrator decides what leaves the
/// machine.
///
/// No result envelope is required from the agent. The CLI's own result object already distinguishes
/// success, an exhausted turn limit, and a failed run, which is everything the outcome table needs.
final class Agent {
  /// Settings that make a run the same run everywhere.
  ///
  /// These travel in the settings file rather than in the process environment on purpose: a
  /// settings entry replaces whatever the host exported, so a polluted runner cannot quietly change
  /// how a golem behaves.
  private static final Map<String, String> DETERMINISM =
      Map.ofEntries(
          // One switch covers auto-updates, telemetry, error reporting, and feature
          // flags. Flags are off deliberately: a pinned binary must not change
          // behavior because a remote flag changed.
          Map.entry("CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC", "1"),
          // Not covered by that switch, and a fresh runner looks like a first launch,
          // which is exactly when a marketplace fetch would happen.
          Map.entry("CLAUDE_CODE_DISABLE_OFFICIAL_MARKETPLACE_AUTOINSTALL", "1"),
          // Context comes from the repository. Learned memory, bundled skills, and
          // skills baked into an image would each make one run depend on another run
          // or on which machine it landed.
          Map.entry("CLAUDE_CODE_DISABLE_AUTO_MEMORY", "1"),
          Map.entry("CLAUDE_CODE_DISABLE_BUNDLED_SKILLS", "1"),
          Map.entry("CLAUDE_CODE_DISABLE_POLICY_SKILLS", "1"),
          // Nothing may outlive the run or schedule more of it.
          Map.entry("CLAUDE_CODE_DISABLE_BACKGROUND_TASKS", "1"),
          Map.entry("CLAUDE_CODE_DISABLE_CRON", "1"),
          // File checkpointing writes a backup tree into the workspace, which the
          // clean-workspace guard would read as work the golem failed to commit.
          Map.entry("CLAUDE_CODE_DISABLE_FILE_CHECKPOINTING", "1"),
          // In print mode the terminal title costs a background model call and buys
          // nothing, because no terminal is watching.
          Map.entry("CLAUDE_CODE_DISABLE_TERMINAL_TITLE", "1"),
          // Built-in commit and pull-request instructions would tell the agent to
          // push and to open pull requests, which the protocol forbids.
          Map.entry("CLAUDE_CODE_DISABLE_GIT_INSTRUCTIONS", "1"),
          // Capacity errors then wait instead of failing, which leaves the golem's
          // own wall clock as the single stop condition.
          Map.entry("CLAUDE_CODE_RETRY_WATCHDOG", "1"),
          // Golem work alternates long tool calls with model turns, so a five-minute
          // cache lifetime expires across an ordinary build. The longer window costs
          // more per write and less in total.
          Map.entry("CLAUDE_CODE_PROMPT_CACHE_TTL", "1h"),
          // Each shell command starts where the run started.
          Map.entry("CLAUDE_BASH_MAINTAIN_PROJECT_WORKING_DIR", "1"));

  private static final String RESULT_FILE = "result.json";
  private static final String SYSTEM_PROMPT_FILE = "system-prompt.md";
  private static final String DEBUG_FILE = "claude-debug.log";
  private static final String SUCCESS = "success";
  private static final String NO_RESULT = "none";

  /// A missing optional statistic is zero; a present malformed one is an error.
  private static long count(Json.ObjectValue object, String key) {
    return switch (object.get(key)) {
      case Json.NullValue ignored -> 0;
      case Json.NumberValue number -> number.value().longValueExact();
      default -> throw new IllegalArgumentException("invalid agent result: " + key);
    };
  }

  private static String text(Json.ObjectValue object, String key, String fallback) {
    return switch (object.get(key)) {
      case Json.NullValue ignored -> fallback;
      case Json.StringValue value -> value.value();
      default -> throw new IllegalArgumentException("invalid agent result: " + key);
    };
  }

  private static Json.ObjectValue object(Json value, String field) {
    return switch (value) {
      case Json.ObjectValue result -> result;
      case Json.NullValue ignored -> new Json.ObjectValue(Map.of());
      default -> throw new IllegalArgumentException("invalid agent result: " + field);
    };
  }

  private Agent() {}

  /// What one agent run produced.
  ///
  /// `subtype` is the CLI's own classification and `stopped` is the orchestrator's. Both are
  /// needed: an expired budget and a failed run can share an exit code while meaning different
  /// things.
  record Result(
      int exit,
      Proc.Stop stopped,
      String subtype,
      boolean isError,
      int turns,
      double costUsd,
      long inputTokens,
      long outputTokens,
      long cacheTokens,
      int apiErrorStatus,
      String narrative,
      String raw) {
    Result {
      assert subtype != null && !subtype.isBlank() : "a result always has a classification";
      assert stopped != null : "a result records how the process ended";
      assert turns >= 0 && costUsd >= 0 : "counters cannot be negative";
    }

    boolean succeeded() {
      return exit == 0 && stopped == Proc.Stop.NONE && SUCCESS.equals(subtype);
    }

    /// A run that produced no readable result.
    ///
    /// Every counter is zero because nothing was counted, not because nothing happened. What it
    /// means is decided by the outcome table, from the exit code and how the process was stopped.
    static Result none(Proc.Result process, String narrative, String raw) {
      return new Result(
          process.exit(), process.stopped(), NO_RESULT, true, 0, 0, 0, 0, 0, 0, narrative, raw);
    }

    static Result of(Proc.Result process, Json.ObjectValue row, String raw) {
      var usage = object(row.get("usage"), "usage");
      var failed =
          switch (row.get("is_error")) {
            case Json.BooleanValue flag -> flag.value();
            case Json.NullValue ignored -> false;
            default -> throw new IllegalArgumentException("invalid agent result: is_error");
          };
      var cost =
          switch (row.get("total_cost_usd")) {
            case Json.NumberValue number -> number.value().doubleValue();
            case Json.NullValue ignored -> 0.0;
            default -> throw new IllegalArgumentException("invalid agent result: total_cost_usd");
          };
      if (!Double.isFinite(cost) || cost < 0)
        throw new IllegalArgumentException("invalid agent cost");
      return new Result(
          process.exit(),
          process.stopped(),
          text(row, "subtype", "none"),
          failed,
          Math.toIntExact(count(row, "num_turns")),
          cost,
          count(usage, "input_tokens"),
          count(usage, "output_tokens"),
          count(usage, "cache_read_input_tokens"),
          Math.toIntExact(count(row, "api_error_status")),
          text(row, "result", ""),
          raw);
    }
  }

  /// Runs the golem's task and returns what the CLI reported.
  static Result run(Golem golem, Path workspace, Run run) {
    assert golem != null && workspace != null && run != null : "an agent run needs its inputs";
    assert Files.isDirectory(workspace) : "the workspace must exist";

    var systemPrompt = assembleSystemPrompt(golem, workspace, run);
    var command = command(golem, systemPrompt, run);
    var prompt =
        golem.harness() == Golem.Harness.CLAUDE
            ? ""
            : Text.read(systemPrompt) + System.lineSeparator() + golem.body();
    var process =
        Proc.run(command, workspace, environment(golem, run, workspace), golem.timeout(), prompt);
    var result =
        golem.harness() == Golem.Harness.CLAUDE
            ? interpret(process, run)
            : new Result(
                process.exit(),
                process.stopped(),
                process.ok() ? SUCCESS : NO_RESULT,
                !process.ok(),
                0,
                0,
                0,
                0,
                0,
                0,
                process.out(),
                process.out());

    assert result.stopped() == process.stopped() : "the stop reason is carried through";
    return result;
  }

  /// Builds the system prompt: the versioned protocol, then repository-owned behavior.
  ///
  /// Style and policy live in the repository's shared file, never in the protocol, so a repository
  /// can change how work is done without changing the engine.
  private static Path assembleSystemPrompt(Golem golem, Path workspace, Run run) {
    var text = new StringBuilder(Text.read(run.protocolFile()));
    for (var file : List.of("SYSTEM.md", "MISSION.md", "RULES.md")) {
      var system = run.workspace().resolve(".system").resolve(file);
      if (Files.isRegularFile(system))
        text.append(System.lineSeparator())
            .append(System.lineSeparator())
            .append(Text.read(system));
    }
    var shared = Text.readIfPresent(Golem.sharedPolicy(run.workspace()));
    if (!shared.isBlank()) {
      text.append(System.lineSeparator()).append(System.lineSeparator()).append(shared);
    }
    var target = run.directory().resolve(SYSTEM_PROMPT_FILE);
    Text.write(target, text.toString());
    assert Files.isRegularFile(target) : "the assembled prompt must exist before launch";
    return target;
  }

  /// Builds the argument array for one agent run.
  ///
  /// Visible to the self-check so the invocation can be asserted without launching anything: which
  /// permission mode a host earns, whether a turn cap was declared, and which shell a platform uses
  /// are all decisions worth pinning down.
  static List<String> command(Golem golem, Path systemPrompt, Run run) {
    assert golem != null && systemPrompt != null && run != null
        : "building a command needs its inputs";

    if (golem.harness() == Golem.Harness.PI)
      return List.of(
          "pi",
          "--print",
          "--no-session",
          "--no-extensions",
          "--no-skills",
          "--no-prompt-templates",
          "--no-context-files",
          "--no-approve",
          "--model",
          golem.model(),
          "--thinking",
          golem.effort());
    if (golem.harness() == Golem.Harness.CODEX) {
      var codex =
          new ArrayList<>(
              List.of(
                  "codex",
                  "exec",
                  "--ephemeral",
                  "--model",
                  golem.model(),
                  "--config",
                  "model_reasoning_effort=" + Json.encode(new Json.StringValue(golem.effort()))));
      if (run.host() == Run.Host.EPHEMERAL) codex.add("--dangerously-bypass-approvals-and-sandbox");
      else codex.addAll(List.of("--sandbox", "workspace-write"));
      codex.add("-");
      return List.copyOf(codex);
    }
    var command = new ArrayList<>(run.claudeArgv());
    command.add("-p");
    command.add(golem.body());
    command.add("--output-format");
    command.add("json");
    command.add("--append-system-prompt-file");
    command.add(systemPrompt.toString());
    command.add("--model");
    command.add(golem.model());
    command.add("--effort");
    command.add(golem.effort());
    command.add("--session-id");
    command.add(run.traceId().toString());
    command.add("--settings");
    command.add(settings(run));
    command.add("--strict-mcp-config");
    command.add("--mcp-config");
    command.add("{}");
    command.add("--debug-file");
    command.add(run.directory().resolve(DEBUG_FILE).toString());
    golem
        .turns()
        .ifPresent(
            turns -> {
              command.add("--max-turns");
              command.add(Integer.toString(turns));
            });

    switch (run.host()) {
      // A disposable virtual machine is the only place the documented unattended
      // mode is defensible.
      case EPHEMERAL -> command.add("--dangerously-skip-permissions");
      // Anywhere else the run stays in dontAsk, where anything that would prompt
      // is denied rather than waited on forever.
      case TRUSTED -> {
        command.add("--permission-mode");
        command.add("dontAsk");
        command.add("--allowedTools");
        command.add(
            switch (run.platform()) {
              case WINDOWS -> "PowerShell,Read,Edit,Write,Glob,Grep";
              case POSIX -> "Bash,Read,Edit,Write,Glob,Grep";
            });
      }
    }

    // Windows has no sandbox and, with Git for Windows present, would route
    // commands through Git Bash. PowerShell is the native shell there, so Bash is
    // removed rather than left as a second, differently behaving option.
    if (run.platform() == Run.Platform.WINDOWS) {
      command.add("--disallowedTools");
      command.add("Bash");
    }

    assert command.contains("-p") : "the agent always runs non-interactively";
    return List.copyOf(command);
  }

  /// The settings file contents, inline.
  ///
  /// Everything here outranks the host: a settings entry replaces the value a runner exported, so
  /// how a golem behaves does not depend on the state of the machine it happened to land on.
  static String settings(Run run) {
    assert run != null : "settings belong to a run";
    var entries = new LinkedHashMap<>(DETERMINISM);
    if (run.platform() == Run.Platform.WINDOWS) {
      // Windows has no sandbox and would otherwise route commands through Git
      // Bash. PowerShell is the native shell there.
      entries.put("CLAUDE_CODE_USE_POWERSHELL_TOOL", "1");
    }
    var environment = new LinkedHashMap<String, Json>();
    entries.forEach((key, value) -> environment.put(key, new Json.StringValue(value)));
    var attribution =
        new Json.ObjectValue(
            Map.of(
                "commit",
                new Json.StringValue(""),
                "pr",
                new Json.StringValue(""),
                "sessionUrl",
                new Json.BooleanValue(false)));
    var settings =
        Json.encode(
            new Json.ObjectValue(
                Map.of("attribution", attribution, "env", new Json.ObjectValue(environment))));
    assert settings.contains("CLAUDE_CODE_RETRY_WATCHDOG")
        : "an unattended run must survive capacity errors";
    return settings;
  }

  /// Builds the process environment for one agent run.
  ///
  /// Only what the launcher itself must own lives here: the run-scoped directories, the commit
  /// identity, and the facts the protocol names. Everything else is a setting, so a host cannot
  /// override it.
  static Map<String, String> environment(Golem golem, Run run) {
    return environment(golem, run, run.workspace());
  }

  static Map<String, String> environment(Golem golem, Run run, Path workspace) {
    assert golem != null && run != null && workspace != null
        : "building an environment needs its inputs";
    var environment = new LinkedHashMap<String, String>();

    // A run-scoped configuration directory keeps host settings, history, and
    // plugins out, so a local run and a hosted run see the same world. This one
    // cannot move into the settings file, because it decides where that file is
    // read from.
    environment.put("CLAUDE_CONFIG_DIR", run.configDirectory().toString());
    environment.put("CLAUDE_CODE_TMPDIR", run.tempDirectory().toString());

    // The commit identity is process state, so the golem has nothing to configure
    // and nothing to override. The name separates golems in git log; the address
    // is shared so GitHub can still resolve the account.
    var author = "golem-" + golem.name();
    environment.put("GIT_AUTHOR_NAME", author);
    environment.put("GIT_COMMITTER_NAME", author);
    environment.put("GIT_AUTHOR_EMAIL", run.commitEmail());
    environment.put("GIT_COMMITTER_EMAIL", run.commitEmail());

    environment.put("GOLEM_NAME", golem.name());
    environment.put("GOLEM_WORKSPACE", workspace.toAbsolutePath().toString());
    environment.put("GOLEM_TIMEOUT", golem.timeout().toString());
    environment.put("GOLEM_PR_FILE", run.prFile().toString());
    environment.put("GOLEM_ACTOR", author);
    golem.branch().ifPresent(branch -> environment.put("GOLEM_BRANCH", branch));

    assert environment.containsKey("GIT_AUTHOR_NAME") : "commits must be attributable to the golem";
    return Map.copyOf(environment);
  }

  /// Reads the CLI result object, tolerating a run that produced none.
  ///
  /// A missing or unreadable result is not fatal here: the outcome table decides what it means,
  /// from the exit code and how the process was stopped.
  static Result interpret(Proc.Result process, Run run) {
    var raw = resultLine(process.out());
    if (raw.isEmpty()) {
      return Result.none(process, process.err().strip(), process.out());
    }

    Text.write(run.directory().resolve(RESULT_FILE), raw);
    try {
      var parsed = Json.parse(raw);
      if (!(parsed instanceof Json.ObjectValue row))
        throw new IllegalArgumentException("agent result is not an object");
      return Result.of(process, row, raw);
    } catch (IllegalArgumentException | ArithmeticException malformed) {
      return Result.none(process, "agent result is malformed", raw);
    }
  }

  /// Finds the result object in the captured output.
  ///
  /// The CLI prints its result last, but a run is not guaranteed to have printed nothing before it,
  /// so the last line that looks like an object wins rather than the first byte of the stream.
  private static String resultLine(String output) {
    var lines = output.strip().split("\r?\n");
    for (var index = lines.length - 1; index >= 0; index--) {
      var line = lines[index].strip();
      if (line.startsWith("{") && line.endsWith("}")) {
        return line;
      }
    }
    return "";
  }
}
