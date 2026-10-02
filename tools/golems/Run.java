import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/// Everything one run needs that the golem does not declare itself.
///
/// Host facts are resolved once, here, so nothing else has to ask what kind of machine it is on.
/// That is what keeps a local run and a hosted run one lifecycle with different capabilities rather
/// than two code paths.
record Run(
    UUID traceId,
    Path workspace,
    Path orchestrator,
    Path directory,
    Publication publication,
    Host host,
    Platform platform,
    String claudeCommand,
    String commitEmail) {
  /// Address used for every golem commit.
  ///
  /// GitHub resolves the avatar and the profile link from the address, not from the name, so a
  /// shared account address keeps commits linked while the per-golem author name still separates
  /// them in `git log` and `git shortlog`.
  private static final String DEFAULT_EMAIL =
      "41898282+github-actions[bot]@users.noreply.github.com";

  private static final String STATE_DIRECTORY = "golems";
  private static final String PR_FILE = "pull-request.md";
  private static final String PROTOCOL_FILE = "protocol.md";
  private static final String CONFIG_DIRECTORY = "claude-config";
  private static final String TEMP_DIRECTORY = "tmp";

  Run {
    assert traceId != null : "a run needs a trace";
    assert workspace != null && orchestrator != null && directory != null
        : "a run needs its directories";
    assert claudeCommand != null && !claudeCommand.isBlank() : "a run needs an agent command";
    assert commitEmail != null && commitEmail.contains("@") : "a run needs a commit address";
  }

  /// Whether the run may publish anything.
  enum Publication {
    /// Push, pull request, labels, and check run are all allowed.
    PUBLISH,
    /// Everything up to and including the guards; nothing leaves the machine.
    LOCAL
  }

  /// Where the run's output is being read.
  ///
  /// This is the one place that decides whether this process is running inside a forge workflow. It
  /// used to be decided three times, in three files, by three different tests of the same variable,
  /// which meant a host that set it to anything other than `true` got a run that disagreed with
  /// itself about where it was.
  ///
  /// The test is strict because the contract is: GitHub sets the variable to the string `true`, and
  /// anything else is somebody imitating it badly.
  enum Surface {
    /// A workflow log, which understands grouping and annotations.
    ACTIONS,
    /// A terminal, which understands neither.
    TERMINAL;

    static final String VARIABLE = "GITHUB_ACTIONS";

    static Surface detect() {
      return "true".equals(System.getenv(VARIABLE)) ? ACTIONS : TERMINAL;
    }

    boolean isActions() {
      return this == ACTIONS;
    }
  }

  /// What kind of machine the run is on.
  ///
  /// Only a disposable virtual machine earns the unattended permission mode. A developer
  /// workstation is trusted but not isolated, and must not claim to be.
  ///
  /// A forge workflow alone is not enough: a local run asks for the same isolated machine's
  /// privileges without being allowed to publish, so it stays trusted.
  enum Host {
    EPHEMERAL,
    TRUSTED;

    static Host of(Surface surface, Publication publication) {
      return surface.isActions() && publication == Publication.PUBLISH ? EPHEMERAL : TRUSTED;
    }
  }

  /// Which shell the platform offers the agent.
  enum Platform {
    WINDOWS,
    POSIX
  }

  static Run create(Path workspace, Path orchestrator, Publication publication) {
    assert workspace != null && orchestrator != null && publication != null
        : "a run needs its inputs";
    var traceId = UUID.randomUUID();
    var windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
    return new Run(
        traceId,
        workspace.toAbsolutePath().normalize(),
        orchestrator.toAbsolutePath().normalize(),
        stateDirectory(traceId),
        publication,
        Host.of(Surface.detect(), publication),
        windows ? Platform.WINDOWS : Platform.POSIX,
        Optional.ofNullable(System.getenv("GOLEM_CLAUDE")).orElse("claude"),
        Optional.ofNullable(System.getenv("GOLEM_COMMIT_EMAIL")).orElse(DEFAULT_EMAIL));
  }

  /// The agent command as an argument array.
  ///
  /// The configured command may carry arguments, which is what lets the self-check substitute a
  /// fake agent without a shell script or a shim on the path. Every caller must split it
  /// identically, so it is split once, here.
  List<String> claudeArgv() {
    var argv = List.of(claudeCommand.trim().split("\\s+"));
    assert !argv.isEmpty() : "an agent command cannot be empty";
    return argv;
  }

  /// Detached candidate checkout, retained for recovering local or failed work.
  Path candidateDirectory() {
    return directory.resolve("worktree");
  }

  /// Where the golem writes its pull-request text. Only the first creation needs it; an existing
  /// pull request is edited by the golem with `gh`.
  Path prFile() {
    return directory.resolve(PR_FILE);
  }

  /// The engine contract shipped beside the orchestrator.
  ///
  /// Named once, here, because preflight refuses to start without it and the agent is launched with
  /// it: two places that must not be able to disagree about which file that is.
  Path protocolFile() {
    return orchestrator.resolve(PROTOCOL_FILE);
  }

  /// Run-scoped configuration, which keeps the host's own settings, history, and plugins out of the
  /// run.
  Path configDirectory() {
    return directory.resolve(CONFIG_DIRECTORY);
  }

  /// Run-scoped scratch space, so nothing the agent writes outlives the run.
  Path tempDirectory() {
    return directory.resolve(TEMP_DIRECTORY);
  }

  private static Path stateDirectory(UUID traceId) {
    try {
      // A hosted runner exposes its own temp directory, so retained candidates
      // never dirty the checkout. Elsewhere the platform default is right.
      var temp =
          Optional.ofNullable(System.getenv("RUNNER_TEMP"))
              .filter(value -> !value.isBlank())
              .orElseGet(() -> System.getProperty("java.io.tmpdir"));
      var directory = Path.of(temp).resolve(STATE_DIRECTORY).resolve(traceId.toString());
      Files.createDirectories(directory);
      assert Files.isDirectory(directory) : "the run state directory must exist";
      return directory;
    } catch (IOException failure) {
      throw new UncheckedIOException("cannot create run state directory", failure);
    }
  }
}
