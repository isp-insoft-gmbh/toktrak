package toktrak.tests;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;
import toktrak.http.Changelog;
import toktrak.http.Changelog.Note;
import toktrak.http.Changelog.Segment;
import toktrak.http.Changelog.SegmentKind;

final class ChangelogTest {
  @Test
  void given_supportedMarkdown_when_parsingReleaseNotes_then_buildsCurrentAndEarlierReleases() {
    String markdown =
        """
        # Changelog

        ## v2

        - Keep `node` available across
          scheduled runs.
        - Read the [guide](https://example.com/guide).

        ## v1

        - Previous change.

        ## v0

        - Initial release.
        """;

    Changelog changelog = Changelog.parse("v2", markdown);

    assertAll(
        () -> assertEquals("Current release", changelog.currentLabel()),
        () -> assertEquals("v2", changelog.current().version()),
        () ->
            assertEquals(
                List.of("v1", "v0"),
                changelog.earlier().stream().map(Changelog.Release::version).toList()),
        () ->
            assertEquals(
                "Keep ", changelog.current().notes().getFirst().segments().getFirst().text()),
        () ->
            assertEquals(
                SegmentKind.CODE, changelog.current().notes().getFirst().segments().get(1).kind()),
        () ->
            assertEquals(
                " available across scheduled runs.",
                changelog.current().notes().getFirst().segments().get(2).text()),
        () ->
            assertEquals(
                "https://example.com/guide",
                changelog.current().notes().get(1).segments().get(1).url()));
  }

  @Test
  void given_linkLabelWithParenthesis_when_parsingReleaseNotes_then_preservesLabelAndUrl() {
    Changelog changelog =
        Changelog.parse("v0", "# Changelog\n\n## v0\n\n- [A)B](https://example.com/guide).\n");

    var link = changelog.current().notes().getFirst().segments().getFirst();
    assertEquals("A)B", link.text());
    assertEquals("https://example.com/guide", link.url());
  }

  @Test
  void given_developmentRuntime_when_loadingPackagedChangelog_then_marksNewestReleaseUnreleased() {
    Changelog changelog = Changelog.load("dev");

    assertEquals("Unreleased", changelog.currentLabel());
    assertTrue(changelog.current().version().matches("v[0-9]+"));
    assertFalse(changelog.current().notes().isEmpty());
    assertTrue(changelog.hasEarlier());
    assertFalse(Changelog.parse("v0", "# Changelog\n\n## v0\n\n- Initial.\n").hasEarlier());
  }

  @Test
  void given_invalidMarkdownOrRuntime_when_parsingReleaseNotes_then_rejectsInput() {
    String complete = "# Changelog\n\n## v1\n\n- Change.\n\n## v0\n\n- Initial.\n";
    for (String invalid :
        List.of(
            "# Changes\n\n## v0\n\n- Initial.\n",
            "# Changelog\n\n## v1\n\nParagraph.\n\n## v0\n\n- Initial.\n",
            "# Changelog\n\n## v2\n\n- Change.\n\n## v0\n\n- Initial.\n",
            "# Changelog\n\n## v0\n\n- Broken `code.\n",
            "# Changelog\n\n## v0\n\n- [broken\n",
            "# Changelog\n\n## v0\n\n- [](https://example.com).\n",
            "# Changelog\n\n## v0\n\n- [Link](https://example.com\n",
            "# Changelog\n\n## v0\n\n- [Link](http://example.com).\n")) {
      assertThrows(IllegalArgumentException.class, () -> Changelog.parse("dev", invalid), invalid);
    }
    assertThrows(IllegalArgumentException.class, () -> Changelog.parse("v0", complete));
    assertThrows(IllegalArgumentException.class, () -> Changelog.parse("garbage", complete));
    assertEquals(
        "v0 has no release notes",
        assertThrows(
                IllegalArgumentException.class,
                () -> Changelog.parse("dev", "# Changelog\n\n## v0\n\n\n"))
            .getMessage());
    for (String link :
        List.of("- [](https://example.com).", "- a[](https://example.com).", "- [a](https://e")) {
      assertEquals(
          "changelog link is invalid", rejection("# Changelog\n\n## v0\n\n" + link + "\n"), link);
    }
    for (String emptyCode : List.of("- ``.", "- a``b.")) {
      assertEquals(
          "changelog inline code is invalid",
          rejection("# Changelog\n\n## v0\n\n" + emptyCode + "\n"),
          emptyCode);
    }
  }

  @Test
  void given_inlineCodeAndLinks_when_parsingReleaseNotes_then_keepsExactSegmentText() {
    var segments =
        Changelog.parse("v0", "# Changelog\n\n## v0\n\n- x`y`z [l](https://e.example/p)!\n")
            .current()
            .notes()
            .getFirst()
            .segments();
    assertEquals(
        List.of(
            Segment.text("x"),
            Segment.code("y"),
            Segment.text("z "),
            Segment.link("l", "https://e.example/p"),
            Segment.text("!")),
        segments);
  }

  @Test
  void given_inputsAtEachLimit_when_parsingReleaseNotes_then_acceptsLimitAndRejectsOneMore() {
    // 64 KiB of markdown.
    String head = "# Changelog\n\n## v0\n\n- ";
    String exact = head + "a".repeat(64 * 1024 - head.length() - 1) + "\n";
    assertEquals(64 * 1024, exact.length());
    assertEquals("v0", Changelog.parse("dev", exact).current().version());
    assertEquals("changelog is too large", rejection(exact + "\n"));

    // 256 releases.
    assertEquals("v255", Changelog.parse("dev", releases(256)).current().version());
    assertEquals("changelog releases exceed 256", rejection(releases(257)));

    // 1,024 notes in one release.
    assertEquals(1_024, Changelog.parse("dev", notes(1_024)).current().notes().size());
    assertEquals("changelog notes exceed 1024", rejection(notes(1_025)));

    // 128 segments in one note: alternating text and code.
    assertEquals(
        128,
        Changelog.parse("dev", segments(64, "")).current().notes().getFirst().segments().size());
    assertEquals("changelog note segments exceed 128", rejection(segments(64, "!")));

    // A current-release label of 32 characters.
    var release = new Changelog.Release("v0", List.of(new Note(List.of(Segment.text("a")))));
    assertEquals(32, new Changelog("x".repeat(32), release, List.of()).currentLabel().length());
    assertThrows(
        IllegalArgumentException.class, () -> new Changelog("x".repeat(33), release, List.of()));
  }

  @Test
  void given_recordsAtTheirLimits_when_constructing_then_acceptsLimitAndRejectsOneMore() {
    Note note = new Note(List.of(Segment.text("a")));
    assertEquals(1_024, new Changelog.Release("v0", nCopies(1_024, note)).notes().size());
    assertThrows(
        IllegalArgumentException.class, () -> new Changelog.Release("v0", nCopies(1_025, note)));
    assertEquals(128, new Note(nCopies(128, Segment.text("a"))).segments().size());
    assertThrows(IllegalArgumentException.class, () -> new Note(nCopies(129, Segment.text("a"))));
    assertThrows(
        IllegalArgumentException.class,
        () -> new Segment(SegmentKind.TEXT, "a", "https://e.example"));
    assertThrows(IllegalArgumentException.class, () -> new Segment(SegmentKind.LINK, "a", ""));
    assertEquals("a", new Segment(SegmentKind.CODE, "a", "").text());
    assertThrows(IllegalArgumentException.class, () -> Segment.code(" "));
    assertEquals(" ", Segment.text(" ").text());
    assertEquals(" ", Segment.link(" ", "https://e.example").text());
  }

  private static String rejection(String markdown) {
    return assertThrows(IllegalArgumentException.class, () -> Changelog.parse("dev", markdown))
        .getMessage();
  }

  private static String releases(int count) {
    var markdown = new StringBuilder("# Changelog\n");
    for (int version = count - 1; version >= 0; version--) {
      markdown.append("\n## v").append(version).append("\n\n- Change.\n");
    }
    return markdown.toString();
  }

  private static String notes(int count) {
    return "# Changelog\n\n## v0\n\n" + "- a\n".repeat(count);
  }

  private static String segments(int pairs, String tail) {
    return "# Changelog\n\n## v0\n\n- " + "a`b`".repeat(pairs) + tail + "\n";
  }

  private static <T> List<T> nCopies(int count, T value) {
    return java.util.Collections.nCopies(count, value);
  }
}
