/// The observed result of one {@link Golems} lifecycle, distinct from process exit status.
///
/// | Outcome | Meaning | Exit | Orchestrator publication |
/// | --- | --- | --- | --- |
/// | {@link Changed} | Committed work accepted by guards | 0 | PR update permitted |
/// | {@link NoChange} | Agent finished without commits | 0 | None |
/// | {@link Incomplete} | Budget or turns expired without commits, guards passed | 0 | None |
/// | {@link Blocked} | Unsafe, invalid, or unfinished committed state | 1 | Skipped or partially completed |
///
/// Declared verification runs before publication when work is clean and committed; a task without
/// `verify` has no repository check, so `Changed` alone does not imply tests ran. A budget
/// expiration **with** committed work is blocked rather than incomplete because the agent did not
/// finish that work; it must not be published as a partial result. Publication can push a branch
/// before a later PR update fails; a blocked result does not roll back that push, so inspect the
/// remote state before retrying. Invalid CLI arguments and otherwise unhandled runtime failures
/// have exit code 2 before an outcome exists. No-change and incomplete remain green on a schedule
/// when guards pass; read the report to distinguish them.
///
/// Sealed alternatives force consumers to handle all states, and reason-bearing states are records
/// rather than an enum with an optional string. These values come from preflight, agent
/// termination, Git commits, and guard observations, never from an agent's prose or a guessed
/// zero-cost usage record.
///
/// @see ExitCode
/// @see Golems
sealed interface Outcome {
  /// The agent finished and left new commits.
  record Changed() implements Outcome {}

  /// The agent finished and found nothing to do. This is a success, not a failure: a golem that
  /// changes nothing has still answered its question.
  record NoChange() implements Outcome {}

  /// The run cannot proceed without a human.
  record Blocked(String reason) implements Outcome {
    public Blocked {
      assert reason != null && !reason.isBlank() : "a blocked run must say why";
    }
  }

  /// The run ran out of budget or turns without committed work. No partial change is published;
  /// inspect {@link #reason()} for the limit reached.
  record Incomplete(String reason) implements Outcome {
    public Incomplete {
      assert reason != null && !reason.isBlank() : "an incomplete run must say why";
    }
  }

  /// The name used in reports, labels, and logs.
  default String label() {
    return switch (this) {
      case Changed _ -> "changed";
      case NoChange _ -> "no_change";
      case Blocked _ -> "blocked";
      case Incomplete _ -> "incomplete";
    };
  }

  /// Why the run ended this way, empty when the label says everything.
  default String reason() {
    return switch (this) {
      case Changed _ -> "";
      case NoChange _ -> "";
      case Blocked blocked -> blocked.reason();
      case Incomplete incomplete -> incomplete.reason();
    };
  }

  /// Whether the run produced something to publish.
  default boolean publishable() {
    return switch (this) {
      case Changed _ -> true;
      case NoChange _, Blocked _, Incomplete _ -> false;
    };
  }

  /// How the process should exit.
  ///
  /// Only `blocked` is a failure. A golem that found nothing to do, or that ran out of budget
  /// without committed work, completed its lifecycle and must not turn a schedule red. A malformed
  /// invocation maps to {@link ExitCode#USAGE} outside this interface.
  default ExitCode exit() {
    return switch (this) {
      case Changed _, NoChange _, Incomplete _ -> ExitCode.COMPLETED;
      case Blocked _ -> ExitCode.BLOCKED;
    };
  }

  /// Process exit codes, named so call sites do not carry bare integers.
  enum ExitCode {
    /// The lifecycle completed, whatever the golem decided.
    COMPLETED(0),
    /// The run needs a human.
    BLOCKED(1),
    /// The invocation itself was wrong.
    USAGE(2);

    private final int code;

    ExitCode(int code) {
      this.code = code;
    }

    int code() {
      return code;
    }
  }
}
