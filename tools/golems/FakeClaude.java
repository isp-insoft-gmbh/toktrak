import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/// A stand-in for the Claude CLI, used by the offline verification level.
///
/// This is the main test lever of the prototype: it makes every branch of the outcome table
/// reachable without credentials, network access, or a real agent. The scenario is chosen with
/// `GOLEM_FAKE`:
///
/// - `changed` edit a file and commit it
/// - `no_change` do nothing
/// - `dirty` edit a file and leave it uncommitted
/// - `protected` edit a control-plane file and commit it
/// - `max_turns` report an exhausted turn limit
/// - `fail` report a failed run
/// - `quota` report an exhausted quota
/// - `flood` print far more output than the capture bound allows
/// - `hang` sleep past the budget so the orchestrator must stop it
///
/// It also writes the pull-request text, because a real agent does.
public final class FakeClaude {
  /// The version the orchestrator's preflight sees, so a pinned-version check can be exercised
  /// without installing anything.
  private static final String VERSION = "2.1.89 (Fake Claude)";

  public static void main(String[] arguments) throws Exception {
    if (List.of(arguments).contains("--version")) {
      System.out.println(VERSION);
      return;
    }
    var scenario = System.getenv().getOrDefault("GOLEM_FAKE", "no_change");
    var workspace = Path.of(".").toAbsolutePath().normalize();
    var declaredWorkspace = System.getenv("GOLEM_WORKSPACE");
    if (declaredWorkspace != null && !workspace.equals(Path.of(declaredWorkspace)))
      throw new IllegalStateException("candidate cwd and GOLEM_WORKSPACE differ");
    var prFile = System.getenv("GOLEM_PR_FILE");

    switch (scenario) {
      case "changed", "max_turns_work" -> {
        edit(workspace.resolve("GOLEM.txt"), "touched by the fake agent");
        commit(workspace, "Add a note the fake agent left");
      }
      case "dirty" -> edit(workspace.resolve("GOLEM.txt"), "left behind");
      case "protected" -> {
        edit(workspace.resolve(".golems").resolve("smuggled.md"), "should never be published");
        commit(workspace, "Try to edit the control plane");
      }
      case "flood" -> {
        var noise = "x".repeat(4096);
        for (var index = 0; index < 512; index++) {
          System.out.println(noise);
        }
        edit(workspace.resolve("GOLEM.txt"), "touched by the fake agent");
        commit(workspace, "Add a note after flooding the log");
      }
      case "hang" -> Thread.sleep(600_000);
      default -> {
        // no filesystem effect
      }
    }

    if (prFile != null && !prFile.isBlank()) {
      Files.writeString(
          Path.of(prFile),
          "Add a note the fake agent left"
              + System.lineSeparator()
              + System.lineSeparator()
              + "This pull request exists so the prototype can exercise publication."
              + System.lineSeparator(),
          StandardCharsets.UTF_8);
    }

    System.out.println(
        switch (scenario) {
          case "max_turns", "max_turns_work" -> result("error_max_turns", true, 20, 0);
          case "fail" -> result("error_during_execution", true, 3, 0);
          case "quota" -> result("error_during_execution", true, 1, 429);
          default -> result("success", false, 7, 0);
        });
    System.exit(scenario.equals("fail") || scenario.equals("quota") ? 1 : 0);
  }

  private static String result(String subtype, boolean isError, int turns, int apiErrorStatus) {
    return ("{\"type\":\"result\",\"subtype\":\"%s\",\"is_error\":%s,\"num_turns\":%d,"
                + "\"api_error_status\":%d,\"total_cost_usd\":0.31,"
                + "\"usage\":{\"input_tokens\":41200,\"output_tokens\":8100,\"cache_read_input_tokens\":180000},\"result\":\"Fake"
                + " agent run for scenario testing.\"}")
        .formatted(subtype, isError, turns, apiErrorStatus);
  }

  private static void edit(Path file, String text) throws Exception {
    Files.createDirectories(file.getParent());
    Files.writeString(file, text + System.lineSeparator(), StandardCharsets.UTF_8);
  }

  private static void commit(Path workspace, String message) throws Exception {
    git(workspace, List.of("git", "add", "-A"));
    git(workspace, List.of("git", "commit", "-m", message));
  }

  private static void git(Path workspace, List<String> command) throws Exception {
    // Output is discarded rather than inherited: anything a helper prints would
    // otherwise land in the stream the orchestrator reads the result from.
    var process =
        new ProcessBuilder(command)
            .directory(workspace.toFile())
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start();
    if (process.waitFor() != 0) {
      throw new IllegalStateException(String.join(" ", command) + " failed");
    }
  }
}
