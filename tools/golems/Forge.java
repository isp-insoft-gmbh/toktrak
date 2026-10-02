import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/// Everything the orchestrator publishes to GitHub.
///
/// This uses `gh` rather than a client library or an SDK, for the same reason the golem does: the
/// prototype takes no dependencies, and the command is already installed and authenticated for the
/// agent.
record Forge(Path root) {
  /// Fixed colors for the two labels whose meaning is fixed. The third is derived from the golem's
  /// name, so adding a golem needs no manual decision.
  private static final String COLOR_GOLEM = "6e7781";

  private static final String COLOR_BLOCKED = "d73a4a";

  /// The whole label vocabulary. A label is persistent state, so only the outcome that persists and
  /// demands a human earns one.
  private static final String LABEL_ALL = "golem";

  private static final String LABEL_OWNER_PREFIX = "golem:";
  private static final String LABEL_BLOCKED = "golem:blocked";

  /// Hue geometry for derived colors: the golden angle spreads adjacent names apart, and fixed
  /// saturation and lightness keep every label legible.
  private static final double GOLDEN_ANGLE = 137.508;

  private static final double SATURATION = 0.62;
  private static final double LIGHTNESS = 0.62;
  private static final long FNV_OFFSET = 0x811c9dc5L;
  private static final long FNV_PRIME = 0x01000193L;
  private static final long FNV_MASK = 0xffffffffL;
  private static final double DEGREES = 360.0;
  private static final int SECTORS = 6;
  private static final int MAX_CHANNEL = 255;

  private static final Duration BUDGET = Duration.ofMinutes(5);
  private static final String REVIEW_QUERY =
      "query($owner:String!,$repo:String!,$number:Int!,$after:String){repository(owner:$owner,name:$repo){pullRequest(number:$number){reviewThreads(first:100,after:$after){nodes{isResolved}pageInfo{hasNextPage"
          + " endCursor}}}}}";
  private static final int REVIEW_PAGE_MAX = 10;

  static Forge open(Path root) {
    return new Forge(root);
  }

  Forge {
    assert root != null : "a repository root is required";
  }

  private Proc.Result gh(List<String> arguments) {
    assert !arguments.isEmpty() : "a gh command needs arguments";
    var command = new ArrayList<String>(arguments.size() + 1);
    command.add("gh");
    command.addAll(arguments);
    return Proc.run(command, root, Map.of(), BUDGET);
  }

  String defaultBranch() {
    var result =
        required(gh(List.of("repo", "view", "--json", "defaultBranchRef")), "read default branch");
    if (!(Json.parse(result.out()) instanceof Json.ObjectValue repository)
        || !(repository.get("defaultBranchRef") instanceof Json.ObjectValue branch)
        || !(branch.get("name") instanceof Json.StringValue name))
      throw new IllegalStateException("invalid default branch response");
    return name.value();
  }

  /// An error querying GitHub is not evidence that no pull request exists.
  Optional<PullRequest> findPullRequest(String branch) {
    assert branch != null && !branch.isBlank() : "a branch is required";
    var result =
        required(
            gh(
                List.of(
                    "pr",
                    "list",
                    "--head",
                    branch,
                    "--state",
                    "open",
                    "--limit",
                    "2",
                    "--json",
                    "number,body,url")),
            "list pull requests");
    if (!(Json.parse(result.out()) instanceof Json.ArrayValue matches)
        || matches.elements().size() > 1)
      throw new IllegalStateException("invalid pull request list");
    if (matches.elements().isEmpty()) return Optional.empty();
    if (!(matches.elements().getFirst() instanceof Json.ObjectValue entry)
        || !(entry.get("number") instanceof Json.NumberValue number)
        || !(entry.get("url") instanceof Json.StringValue url)
        || !(entry.get("body") instanceof Json.StringValue body))
      throw new IllegalStateException("invalid pull request fields");
    return Optional.of(new PullRequest(number.value().intValueExact(), body.value(), url.value()));
  }

  /// Human review threads block new pushes until their feedback has been addressed.
  void requireResolvedReviews(PullRequest pullRequest) {
    assert pullRequest != null : "a review needs its pull request";
    var repoResult =
        required(gh(List.of("repo", "view", "--json", "nameWithOwner")), "read repository name");
    if (!(Json.parse(repoResult.out()) instanceof Json.ObjectValue repository)
        || !(repository.get("nameWithOwner") instanceof Json.StringValue fullName))
      throw new IllegalStateException("invalid repository identity");
    var parts = fullName.value().split("/", -1);
    if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank())
      throw new IllegalStateException("invalid repository name");
    String cursor = null;
    for (int page = 0; page < REVIEW_PAGE_MAX; page++) {
      var query =
          new ArrayList<>(
              List.of(
                  "api",
                  "graphql",
                  "-f",
                  "query=" + REVIEW_QUERY,
                  "-f",
                  "owner=" + parts[0],
                  "-f",
                  "repo=" + parts[1],
                  "-F",
                  "number=" + pullRequest.number()));
      if (cursor != null) query.addAll(List.of("-f", "after=" + cursor));
      var pageResult = required(gh(query), "read pull request reviews");
      var review = reviewPage(pageResult.out());
      if (!review.more()) return;
      if (review.cursor().isBlank() || review.cursor().equals(cursor))
        throw new IllegalStateException("review pagination failed");
      cursor = review.cursor();
    }
    throw new IllegalStateException("review thread count exceeds bounded inspection");
  }

  record ReviewPage(boolean more, String cursor) {}

  static ReviewPage reviewPage(String response) {
    if (!(Json.parse(response) instanceof Json.ObjectValue root)
        || !(root.get("data") instanceof Json.ObjectValue data)
        || !(data.get("repository") instanceof Json.ObjectValue repo)
        || !(repo.get("pullRequest") instanceof Json.ObjectValue pr)
        || !(pr.get("reviewThreads") instanceof Json.ObjectValue threads)
        || !(threads.get("nodes") instanceof Json.ArrayValue nodes)
        || !(threads.get("pageInfo") instanceof Json.ObjectValue info)
        || !(info.get("hasNextPage") instanceof Json.BooleanValue next))
      throw new IllegalStateException("invalid pull request review response");
    for (var node : nodes.elements()) {
      if (!(node instanceof Json.ObjectValue thread)
          || !(thread.get("isResolved") instanceof Json.BooleanValue resolved))
        throw new IllegalStateException("invalid review thread");
      if (!resolved.value()) throw new IllegalStateException("unresolved human review thread");
    }
    if (next.value() && !(info.get("endCursor") instanceof Json.StringValue))
      throw new IllegalStateException("invalid review pagination cursor");
    return new ReviewPage(
        next.value(), info.get("endCursor") instanceof Json.StringValue value ? value.value() : "");
  }

  /// Opens the pull request using the title and body the golem wrote.
  ///
  /// The first line of the file is the title and the rest is the body, because the golem should
  /// write prose, not fill in a form.
  Optional<PullRequest> createPullRequest(String branch, String prText) {
    var newline = prText.indexOf('\n');
    var title = (newline < 0 ? prText : prText.substring(0, newline)).strip();
    var body = newline < 0 ? "" : prText.substring(newline + 1).strip();
    if (title.isEmpty()) {
      return Optional.empty();
    }
    required(
        gh(List.of("pr", "create", "--head", branch, "--title", title, "--body", body)),
        "create pull request");
    return findPullRequest(branch);
  }

  /// Replaces only the orchestrator's block in the body.
  void updateBody(PullRequest pullRequest, String body) {
    required(
        gh(List.of("pr", "edit", Integer.toString(pullRequest.number()), "--body", body)),
        "update pull request");
  }

  /// Creates or updates the golem labels and applies them.
  ///
  /// `golem` is the aggregate filter, because GitHub label search has no prefix match.
  /// `golem:<name>` identifies the owner. `golem:blocked` is the only outcome that is persistent
  /// state rather than per-run state.
  void syncLabels(PullRequest pullRequest, String golem, Outcome outcome) {
    assert pullRequest != null && golem != null && outcome != null : "label sync needs its inputs";
    ensureLabel(LABEL_ALL, COLOR_GOLEM, "Opened by a golem");
    ensureLabel(LABEL_OWNER_PREFIX + golem, color(golem), "Owned by golem " + golem);
    ensureLabel(LABEL_BLOCKED, COLOR_BLOCKED, "The last golem run needs a human");

    var number = Integer.toString(pullRequest.number());
    required(
        gh(
            List.of(
                "pr",
                "edit",
                number,
                "--add-label",
                LABEL_ALL,
                "--add-label",
                LABEL_OWNER_PREFIX + golem)),
        "apply golem labels");
    var blocked = outcome instanceof Outcome.Blocked;
    required(
        gh(
            List.of(
                "pr", "edit", number, blocked ? "--add-label" : "--remove-label", LABEL_BLOCKED)),
        "update blocked label");
  }

  private void ensureLabel(String name, String color, String description) {
    required(
        gh(
            List.of(
                "label",
                "create",
                name,
                "--color",
                color,
                "--description",
                description,
                "--force")),
        "ensure label");
  }

  private static Proc.Result required(Proc.Result result, String operation) {
    if (!result.ok())
      throw new IllegalStateException(operation + " failed (exit " + result.exit() + ")");
    return result;
  }

  /// Derives a stable, distinct label color from a name.
  ///
  /// The hue walks by the golden angle so names that sort next to each other do not get
  /// neighbouring colors, and saturation and lightness are fixed so every label stays legible with
  /// the text color GitHub picks for it.
  static String color(String name) {
    assert name != null && !name.isBlank() : "a name is required";
    var hash = FNV_OFFSET;
    for (var index = 0; index < name.length(); index++) {
      hash = (hash ^ name.charAt(index)) * FNV_PRIME & FNV_MASK;
    }
    var hue = (hash * GOLDEN_ANGLE) % DEGREES;
    var color = hex(hue / DEGREES, SATURATION, LIGHTNESS);
    assert color.matches("[0-9a-f]{6}") : "a label color is six hex digits";
    return color;
  }

  /// Converts HSL to the six hex digits GitHub wants for a label.
  ///
  /// The hue circle is six sectors wide. Within a sector one channel is at full chroma, one is off,
  /// and one interpolates, so naming the sector is the whole conversion.
  private static String hex(double hue, double saturation, double lightness) {
    assert hue >= 0 && hue < 1 : "a hue is a fraction of the circle";
    var chroma = (1 - Math.abs(2 * lightness - 1)) * saturation;
    var position = hue * SECTORS;
    var rising = chroma * (1 - Math.abs(position % 2 - 1));
    var channels =
        switch ((int) position) {
          case 0 -> new Rgb(chroma, rising, 0);
          case 1 -> new Rgb(rising, chroma, 0);
          case 2 -> new Rgb(0, chroma, rising);
          case 3 -> new Rgb(0, rising, chroma);
          case 4 -> new Rgb(rising, 0, chroma);
          default -> new Rgb(chroma, 0, rising);
        };
    // Lightness is applied afterwards, as the offset that lifts the whole triple
    // to the requested brightness without changing its hue.
    var lift = lightness - chroma / 2;
    return String.format(
        "%02x%02x%02x",
        byteOf(channels.red(), lift),
        byteOf(channels.green(), lift),
        byteOf(channels.blue(), lift));
  }

  private static long byteOf(double channel, double lift) {
    return Math.round((channel + lift) * MAX_CHANNEL);
  }

  /// One color as three channels in `[0, 1]`, before lightness is applied.
  private record Rgb(double red, double green, double blue) {}

  /// A golem-owned pull request.
  record PullRequest(int number, String body, String url) {}
}
