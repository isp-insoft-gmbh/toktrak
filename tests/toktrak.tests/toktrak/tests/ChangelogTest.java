package toktrak.tests;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;
import toktrak.http.Changelog;
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
  }
}
