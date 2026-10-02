import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/// Runs child processes with bounded output, a wall-clock budget, and complete process-tree
/// termination.
///
/// Every external tool the orchestrator uses goes through here, so cancellation and output bounds
/// are enforced in one place rather than per call site. Commands are always argument arrays; no
/// string is handed to a shell.
final class Proc {
  /// Upper bounds on captured output per stream. A runaway build must not be able to exhaust the
  /// orchestrator's heap or the run report.
  ///
  /// Both ends are kept, because both matter and they are not the same bytes: the beginning
  /// explains how a run started, and the agent prints its result last, so a head-only bound would
  /// discard exactly the line the orchestrator reads.
  private static final int HEAD_CAPTURE = 1 << 17;

  private static final int TAIL_CAPTURE = 1 << 18;

  /// Grace period between the polite stop and the forceful one. Claude ends the current turn on
  /// SIGINT and exits 143 on SIGTERM, so it needs a moment in between to shut its own children
  /// down.
  private static final Duration GRACE = Duration.ofSeconds(10);

  /// How long the interrupt helper itself may take. It either signals at once or it is not there.
  private static final Duration KILL_BUDGET = Duration.ofSeconds(5);

  /// Default budget for a command the orchestrator expects to succeed quickly.
  private static final Duration CAPTURE_BUDGET = Duration.ofMinutes(5);

  private Proc() {}

  /// Outcome of one child process.
  ///
  /// `stopped` records how the orchestrator ended the process, which the outcome table needs: a run
  /// the orchestrator signalled is `incomplete`, while the same non-zero exit without a signal is
  /// `blocked`.
  record Result(int exit, String out, String err, Stop stopped) {
    boolean ok() {
      return exit == 0 && stopped == Stop.NONE;
    }
  }

  /// How a process ended.
  ///
  /// Two states, because a run has two ways to end: it finishes, or its budget does. Nothing
  /// cancels a golem, so there is no state for it.
  enum Stop {
    /// The process exited on its own.
    NONE,
    /// The budget expired and the orchestrator stopped it.
    TIMEOUT
  }

  /// Runs a command to completion, or stops it when the budget expires.
  static Result run(
      List<String> command, Path directory, Map<String, String> environment, Duration budget) {
    return run(command, directory, environment, budget, "");
  }

  static Result run(
      List<String> command,
      Path directory,
      Map<String, String> environment,
      Duration budget,
      String input) {
    try {
      var builder = new ProcessBuilder(command).directory(directory.toFile());
      builder.environment().putAll(environment);
      var process = builder.start();
      try (var stdin = process.getOutputStream()) {
        stdin.write(input.getBytes(StandardCharsets.UTF_8));
      }
      var out = new Pump(process.getInputStream());
      var err = new Pump(process.getErrorStream());
      out.start();
      err.start();

      var finished = process.waitFor(budget.toMillis(), TimeUnit.MILLISECONDS);
      var stopped = Stop.NONE;
      if (!finished) {
        stopped = Stop.TIMEOUT;
        terminate(process);
      }
      out.join();
      err.join();
      return new Result(process.exitValue(), out.text(), err.text(), stopped);
    } catch (IOException failure) {
      throw new IllegalStateException("cannot run " + String.join(" ", command), failure);
    } catch (InterruptedException interruption) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(
          "interrupted while running " + String.join(" ", command), interruption);
    }
  }

  /// Whether an executable exists on the search path.
  ///
  /// This answers the preflight question without starting anything. Asking a tool for its version
  /// costs a process, and on Windows that is the most expensive thing this program does, so the
  /// cheap question is asked instead: a tool that is not on the path is the failure worth reporting
  /// early, and a tool that is there but broken fails loudly at its first real use.
  static boolean onPath(String executable) {
    assert executable != null && !executable.isBlank() : "an executable name is required";
    var path = System.getenv("PATH");
    if (path == null || path.isBlank()) {
      return false;
    }
    var windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
    var suffixes = windows ? windowsSuffixes() : List.of("");
    for (var entry : path.split(Pattern.quote(File.pathSeparator))) {
      if (entry.isBlank()) {
        continue;
      }
      for (var suffix : suffixes) {
        var candidate = Path.of(entry).resolve(executable + suffix);
        if (Files.isRegularFile(candidate)) {
          return true;
        }
      }
    }
    return false;
  }

  /// The extensions Windows considers executable, which is where the `.exe` a bare command name
  /// really means comes from.
  private static List<String> windowsSuffixes() {
    var configured = System.getenv("PATHEXT");
    var extensions =
        configured == null || configured.isBlank() ? ".EXE;.CMD;.BAT;.COM" : configured;
    var suffixes = new ArrayList<String>();
    suffixes.add("");
    for (var extension : extensions.split(Pattern.quote(File.pathSeparator))) {
      if (!extension.isBlank()) {
        suffixes.add(extension.toLowerCase(Locale.ROOT));
        suffixes.add(extension.toUpperCase(Locale.ROOT));
      }
    }
    return List.copyOf(suffixes);
  }

  /// Runs a command that is expected to succeed and returns its trimmed output.
  static String capture(List<String> command, Path directory) {
    var result = run(command, directory, Map.of(), CAPTURE_BUDGET);
    if (!result.ok()) {
      throw new IllegalStateException(
          String.join(" ", command)
              + " failed with "
              + result.exit()
              + System.lineSeparator()
              + result.err());
    }
    return result.out().strip();
  }

  /// Ends a process and everything it started.
  ///
  /// The escalation is deliberate: an interrupt lets the agent close the turn it is in, a terminate
  /// makes it stop, and only then is the remaining tree destroyed. Descendants are collected before
  /// signalling, because a dying parent orphans them and they would otherwise survive the run.
  private static void terminate(Process process) throws InterruptedException {
    var descendants = process.descendants().toList();
    // The grace period is only spent when there is something to be graceful
    // about. Where no interrupt was delivered, waiting for a reaction to it costs
    // the full period and changes nothing, so the escalation starts one step
    // later instead.
    if (interrupt(process.pid()) == Signal.SENT
        && process.waitFor(GRACE.toMillis(), TimeUnit.MILLISECONDS)) {
      descendants.forEach(ProcessHandle::destroyForcibly);
      return;
    }
    process.destroy();
    if (!process.waitFor(GRACE.toMillis(), TimeUnit.MILLISECONDS)) {
      process.destroyForcibly();
      process.waitFor();
    }
    descendants.forEach(ProcessHandle::destroyForcibly);
  }

  /// Whether the polite stop reached the process at all.
  private enum Signal {
    SENT,
    UNAVAILABLE
  }

  /// Sends SIGINT where the platform has it.
  ///
  /// Java offers no portable interrupt, so this shells out on POSIX. On Windows, and wherever
  /// `kill` is missing, there is nothing to send and the caller is told so rather than left waiting
  /// for an answer.
  private static Signal interrupt(long pid) {
    if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows")) {
      return Signal.UNAVAILABLE;
    }
    try {
      var kill = new ProcessBuilder("kill", "-INT", Long.toString(pid)).start();
      return kill.waitFor(KILL_BUDGET.toSeconds(), TimeUnit.SECONDS) && kill.exitValue() == 0
          ? Signal.SENT
          : Signal.UNAVAILABLE;
    } catch (IOException | InterruptedException unavailable) {
      // The forceful path is the guarantee; a missing kill only skips a courtesy.
      return Signal.UNAVAILABLE;
    }
  }

  /// Drains one stream into a bounded buffer.
  ///
  /// Draining must happen concurrently with waiting, because a child that fills its pipe blocks
  /// forever and would consume the whole budget doing nothing.
  private static final class Pump extends Thread {
    private final InputStream stream;
    private final StringBuilder head = new StringBuilder();
    private final StringBuilder tail = new StringBuilder();
    private boolean truncated;

    private Pump(InputStream stream) {
      this.stream = stream;
      setDaemon(true);
    }

    @Override
    public void run() {
      var chunk = new byte[8192];
      try (stream) {
        int read;
        while ((read = stream.read(chunk)) >= 0) {
          var text = new String(chunk, 0, read, StandardCharsets.UTF_8);
          if (head.length() < HEAD_CAPTURE) {
            head.append(text);
            continue;
          }
          tail.append(text);
          if (tail.length() > TAIL_CAPTURE) {
            tail.delete(0, tail.length() - TAIL_CAPTURE);
            truncated = true;
          }
        }
      } catch (IOException ignored) {
        // A closed stream means the child is gone; whatever arrived is what we report.
      }
    }

    private String text() {
      if (!truncated) {
        return head.toString() + tail;
      }
      return head + System.lineSeparator() + "[output truncated]" + System.lineSeparator() + tail;
    }
  }
}
