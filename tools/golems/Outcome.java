/// How a run ended.
///
/// Sealed so every consumer must handle all four states, and modelled as records rather than an
/// enum plus a loose string, so a state that needs a reason cannot be constructed without one.
///
/// Every value here is derived from what the orchestrator observed: its own preflight, the signal
/// it sent, the agent's result, and the workspace. Nothing is derived from the agent's prose.
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

  /// The run ran out of budget or turns without committed work. No partial change is published.
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
  /// Only `blocked` is a failure. A golem that found nothing to do, or that ran out of budget,
  /// completed its lifecycle and must not turn a schedule red.
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
