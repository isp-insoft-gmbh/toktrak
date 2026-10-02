import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/// Repository operations the orchestrator owns.
///
/// The golem authors commits, because a commit message needs the context of the work. Everything
/// that leaves the machine is here instead, so the guards always run before a push can happen.
///
/// This drives the `git` command rather than a library: the prototype takes no dependencies, and
/// `git` is present anyway because the golem needs it.
record Git(Path root) {
  /// Paths no golem may change. They are its own control plane, and a golem that can edit them can
  /// rewrite the rules it runs under.
  private static final List<String> PROTECTED =
      List.of(
          Golem.DIRECTORY + "/",
          "tools/golems/",
          ".github/workflows/",
          ".github/golems/",
          ".system/",
          ".agents/",
          ".claude/",
          ".codex/",
          ".pi/");

  private static final String ORIGIN = "origin";

  /// Marks a commit line in the combined log, so a file named like a subject cannot be mistaken for
  /// one, and separates the fields within it.
  private static final String COMMIT_MARKER = "\u0001";

  private static final String FIELD_MARKER = "\u0002";

  private static final Duration BUDGET = Duration.ofMinutes(10);

  Git {
    assert root != null : "a repository root is required";
  }

  /// Whether remote refs may be consulted. A local run stays offline so it needs no network and no
  /// credential.
  enum Remote {
    USE,
    SKIP
  }

  static Git open(Path root) {
    assert root != null : "a repository root is required";
    if (!Files.isDirectory(root.resolve(".git")) && !Files.isRegularFile(root.resolve(".git"))) {
      throw new IllegalStateException(root.toAbsolutePath() + " is not a git repository");
    }
    return new Git(root);
  }

  /// Runs a git command that is expected to succeed and returns its output.
  String git(String... arguments) {
    assert arguments.length > 0 : "a git command needs arguments";
    return Proc.capture(command(arguments), root);
  }

  private Proc.Result attempt(String... arguments) {
    return Proc.run(command(arguments), root, Map.of(), BUDGET);
  }

  private List<String> command(String... arguments) {
    var command = new ArrayList<String>(arguments.length + 1);
    command.add("git");
    command.addAll(List.of(arguments));
    return command;
  }

  String head() {
    var head = git("rev-parse", "HEAD");
    assert !head.isBlank() : "a repository always has a head";
    return head;
  }

  /// Where the workspace currently stands.
  ///
  /// `rev-parse` answers as many questions as it is asked, and the flag applies to what follows it,
  /// so the commit and the branch cost one process together rather than one each.
  record Position(String head, String branch) {
    Position {
      assert head != null && !head.isBlank() : "a workspace always stands on a commit";
      assert branch != null && !branch.isBlank()
          : "a workspace always stands on a branch or on HEAD";
    }
  }

  Position position() {
    var answer = git("rev-parse", "HEAD", "--abbrev-ref", "HEAD").split("\r?\n");
    assert answer.length == 2 : "one question about the commit, one about the branch";
    return new Position(answer[0].strip(), answer[1].strip());
  }

  boolean hasRemote() {
    return attempt("remote", "get-url", ORIGIN).ok();
  }

  /// Whether the golem committed everything it produced.
  ///
  /// A dirty tree at the end of a run is a failure rather than work in progress, so this feeds the
  /// outcome table directly.
  enum Tree {
    CLEAN,
    DIRTY
  }

  Tree tree() {
    return git("status", "--porcelain").isEmpty() ? Tree.CLEAN : Tree.DIRTY;
  }

  /// Everything the golem left behind, read in one walk of the history.
  ///
  /// The commits, the files they touched, and the resulting head all describe the same range, so
  /// they are read together: it is one process instead of three, and one snapshot instead of three
  /// that could disagree.
  record Work(String head, List<String> commits, List<String> files, List<String> violations) {
    Work {
      assert head != null && !head.isBlank() : "work is always pinned to a commit";
      assert commits != null && files != null && violations != null
          : "work always answers all three questions";
      assert commits.isEmpty() == files.isEmpty() || !files.isEmpty()
          : "commits without files are possible";
    }

    boolean isEmpty() {
      return commits.isEmpty();
    }
  }

  /// Reads the commits and files added since the pinned base, oldest first.
  ///
  /// Rename detection stays off: a rename reported as a single entry would hide that a protected
  /// path was the source or the target of the move. Commit lines are marked, because a file name is
  /// otherwise indistinguishable from a commit subject.
  Work workSince(String base) {
    assert base != null && !base.isBlank() : "a base commit is required";
    if (!attempt("merge-base", "--is-ancestor", base, "HEAD").ok())
      throw new IllegalStateException("candidate no longer descends from its base");
    var log =
        git(
            "log",
            "--reverse",
            "--format=" + COMMIT_MARKER + "%h" + FIELD_MARKER + "%p" + FIELD_MARKER + "%s",
            base + "..HEAD");
    var commits = new ArrayList<String>();
    for (var line : log.split("\\r?\\n")) {
      if (line.startsWith(COMMIT_MARKER)) {
        var fields = line.substring(COMMIT_MARKER.length()).split(FIELD_MARKER, 3);
        commits.add(fields[0] + " " + fields[2]);
      }
    }
    var files = new LinkedHashSet<>(changedFiles(base));
    var violations =
        files.stream().filter(path -> PROTECTED.stream().anyMatch(path::startsWith)).toList();
    // The newest commit is the head, so the range that was just read answers what
    // would otherwise be another `rev-parse`.
    var head = commits.isEmpty() ? base : commits.getLast().split(" ", 2)[0];
    return new Work(head, List.copyOf(commits), List.copyOf(files), violations);
  }

  private List<String> changedFiles(String base) {
    var diff = git("diff", "--no-renames", "--name-only", "-z", base + "..HEAD");
    if (diff.contains("[output truncated]") || diff.indexOf('\ufffd') >= 0)
      throw new IllegalStateException("candidate path list was truncated or undecodable");
    return diff.isEmpty() ? List.of() : List.of(diff.split(String.valueOf('\0')));
  }

  /// Pins the current commit for a branchless task without changing the checkout.
  Prepared prepare(Remote remote) {
    fetch(remote);
    var start = position();
    return new Prepared(start.branch(), start.head(), "normal mode, no branch");
  }

  /// Creates a detached candidate while leaving the runner's original branch and index untouched.
  Staged stage(String branch, Remote remote, Path destination) {
    assert branch != null && !branch.isBlank() && destination != null
        : "staging needs a branch and path";
    if (Files.exists(destination) || Files.isSymbolicLink(destination))
      throw new IllegalStateException("candidate worktree already exists: " + destination);
    var online = fetch(remote);
    var remoteRef = ORIGIN + "/" + branch;
    Prepared prepared;
    if (online && attempt("rev-parse", "--verify", "--quiet", remoteRef).ok()) {
      if (attempt("show-ref", "--verify", "--quiet", "refs/heads/" + branch).ok()
          && !attempt("merge-base", "--is-ancestor", branch, remoteRef).ok())
        throw new IllegalStateException("local branch has commits absent from " + remoteRef);
      prepared = new Prepared(branch, git("rev-parse", remoteRef), "continued from " + remoteRef);
    } else if (attempt("show-ref", "--verify", "--quiet", "refs/heads/" + branch).ok()) {
      prepared = new Prepared(branch, git("rev-parse", branch), "local branch");
    } else {
      var start = position();
      prepared = new Prepared(branch, start.head(), "created from " + start.branch());
    }
    var result =
        attempt(
            "worktree",
            "add",
            "--detach",
            destination.toAbsolutePath().toString(),
            prepared.base());
    if (!result.ok()) throw new IllegalStateException("cannot create detached candidate worktree");
    var candidate = Git.open(destination);
    assert candidate.position().branch().equals("HEAD") : "candidate must remain detached";
    assert candidate.head().equals(prepared.base()) : "candidate must start at the pinned base";
    return new Staged(candidate, prepared);
  }

  record Staged(Git git, Prepared prepared) {
    Staged {
      assert git != null && prepared != null : "a staged candidate needs its repository and base";
    }
  }

  /// Brings remote refs up to date, and reports whether they can be trusted.
  private boolean fetch(Remote remote) {
    assert remote != null : "preparing needs to know whether it may use the remote";
    var online = remote == Remote.USE && hasRemote();
    if (online && !attempt("fetch", "--prune", ORIGIN).ok())
      throw new IllegalStateException("cannot refresh remote branches");
    return online;
  }

  /// Publishes the branch.
  ///
  /// A rejected push means the remote moved after the run started. The orchestrator reports that
  /// rather than forcing its way through, because the commits in the way belong to someone else.
  Proc.Result push(String branch) {
    assert branch != null && !branch.isBlank() : "a branch is required";
    return attempt("push", ORIGIN, "HEAD:refs/heads/" + branch);
  }

  /// Result of preparing the workspace: the branch the run works on, the commit it is pinned to,
  /// and how that was reached.
  record Prepared(String branch, String base, String note) {
    Prepared {
      assert branch != null && !branch.isBlank() : "a run always has a branch";
      assert base != null && !base.isBlank() : "a run is always pinned to a commit";
      assert note != null && !note.isBlank() : "how the branch was reached is always explained";
    }
  }
}
