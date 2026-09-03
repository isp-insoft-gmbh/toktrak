package toktrak.http;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public record Changelog(String currentLabel, Release current, List<Release> earlier) {
  private static final int BYTES_MAX = 64 * 1024;
  private static final int RELEASES_MAX = 256;
  private static final int NOTES_MAX = 1_024;
  private static final int SEGMENTS_MAX = 128;

  public Changelog {
    Objects.requireNonNull(currentLabel, "currentLabel");
    Objects.requireNonNull(current, "current");
    earlier = List.copyOf(earlier);
    if (currentLabel.isBlank() || currentLabel.length() > 32) {
      throw new IllegalArgumentException("current release label is invalid");
    }
  }

  public static Changelog load(String runtimeVersion) {
    Objects.requireNonNull(runtimeVersion, "runtimeVersion");
    try (InputStream input = Changelog.class.getModule().getResourceAsStream("CHANGELOG.md")) {
      if (input == null) throw new IllegalStateException("packaged changelog is missing");
      byte[] bytes = input.readNBytes(BYTES_MAX + 1);
      if (bytes.length > BYTES_MAX)
        throw new IllegalStateException("packaged changelog is too large");
      return parse(runtimeVersion, new String(bytes, StandardCharsets.UTF_8));
    } catch (IOException exception) {
      throw new IllegalStateException("packaged changelog cannot be read", exception);
    }
  }

  public static Changelog parse(String runtimeVersion, String markdown) {
    Objects.requireNonNull(runtimeVersion, "runtimeVersion");
    Objects.requireNonNull(markdown, "markdown");
    if (!runtimeVersion.equals("dev")) versionNumber(runtimeVersion, "runtime version is invalid");
    if (markdown.getBytes(StandardCharsets.UTF_8).length > BYTES_MAX) {
      throw new IllegalArgumentException("changelog is too large");
    }
    List<String> lines = markdown.lines().toList();
    if (lines.isEmpty() || !lines.getFirst().equals("# Changelog")) {
      throw new IllegalArgumentException("changelog title is invalid");
    }

    var releases = new ArrayList<Release>();
    int index = skipBlankLines(lines, 1);
    while (index < lines.size()) {
      if (releases.size() >= RELEASES_MAX) {
        throw new IllegalArgumentException("changelog releases exceed " + RELEASES_MAX);
      }
      String heading = lines.get(index);
      if (!heading.matches("## v(?:0|[1-9][0-9]*)")) {
        throw new IllegalArgumentException("changelog release heading is invalid: " + heading);
      }
      String version = heading.substring("## ".length());
      index = skipBlankLines(lines, Math.addExact(index, 1));
      var notes = new ArrayList<Note>();
      StringBuilder note = null;
      while (index < lines.size() && !lines.get(index).startsWith("## ")) {
        String line = lines.get(index);
        if (line.isBlank()) {
          index = Math.addExact(index, 1);
          continue;
        }
        if (line.startsWith("- ")) {
          if (note != null) notes.add(note(note.toString()));
          if (notes.size() >= NOTES_MAX) {
            throw new IllegalArgumentException("changelog notes exceed " + NOTES_MAX);
          }
          note = new StringBuilder(line.substring(2));
        } else if (line.startsWith("  ") && note != null) {
          note.append(' ').append(line.strip());
        } else {
          throw new IllegalArgumentException("changelog line is unsupported: " + line);
        }
        index = Math.addExact(index, 1);
      }
      if (note != null) notes.add(note(note.toString()));
      if (notes.isEmpty()) throw new IllegalArgumentException(version + " has no release notes");
      releases.add(new Release(version, notes));
    }
    if (releases.isEmpty()) throw new IllegalArgumentException("changelog has no releases");
    requireDescendingVersions(releases);
    Release current = releases.getFirst();
    if (!runtimeVersion.equals("dev") && !current.version().equals(runtimeVersion)) {
      throw new IllegalArgumentException("runtime version does not match newest changelog release");
    }
    return new Changelog(
        runtimeVersion.equals("dev") ? "Unreleased" : "Current release",
        current,
        releases.subList(1, releases.size()));
  }

  public boolean hasEarlier() {
    return !earlier.isEmpty();
  }

  private static int skipBlankLines(List<String> lines, int index) {
    assert lines != null;
    assert index >= 0 && index <= lines.size();
    while (index < lines.size() && lines.get(index).isBlank()) index = Math.addExact(index, 1);
    return index;
  }

  private static Note note(String markdown) {
    assert markdown != null;
    if (markdown.isBlank()) throw new IllegalArgumentException("changelog note is empty");
    var segments = new ArrayList<Segment>();
    int textStart = 0;
    int index = 0;
    while (index < markdown.length()) {
      char character = markdown.charAt(index);
      if (character != '`' && character != '[') {
        index = Math.addExact(index, 1);
        continue;
      }
      addText(segments, markdown.substring(textStart, index));
      if (character == '`') {
        int end = markdown.indexOf('`', Math.addExact(index, 1));
        if (end < 0 || end == index + 1) {
          throw new IllegalArgumentException("changelog inline code is invalid");
        }
        addSegment(segments, Segment.code(markdown.substring(index + 1, end)));
        index = Math.addExact(end, 1);
      } else {
        int labelEnd = markdown.indexOf("](", Math.addExact(index, 1));
        int urlEnd = labelEnd < 0 ? -1 : markdown.indexOf(')', Math.addExact(labelEnd, 2));
        if (labelEnd <= index + 1 || urlEnd < 0) {
          throw new IllegalArgumentException("changelog link is invalid");
        }
        String label = markdown.substring(index + 1, labelEnd);
        String url = markdown.substring(labelEnd + 2, urlEnd);
        requireLink(url);
        addSegment(segments, Segment.link(label, url));
        index = Math.addExact(urlEnd, 1);
      }
      textStart = index;
    }
    addText(segments, markdown.substring(textStart));
    if (segments.isEmpty()) throw new IllegalArgumentException("changelog note is empty");
    return new Note(segments);
  }

  private static void addText(List<Segment> segments, String text) {
    assert segments != null;
    assert text != null;
    if (!text.isEmpty()) addSegment(segments, Segment.text(text));
  }

  private static void addSegment(List<Segment> segments, Segment segment) {
    assert segments != null;
    assert segment != null;
    if (segments.size() >= SEGMENTS_MAX) {
      throw new IllegalArgumentException("changelog note segments exceed " + SEGMENTS_MAX);
    }
    segments.add(segment);
  }

  private static void requireLink(String value) {
    assert value != null;
    try {
      URI uri = URI.create(value);
      if (!uri.isAbsolute()
          || !"https".equalsIgnoreCase(uri.getScheme())
          || uri.getHost() == null) {
        throw new IllegalArgumentException("changelog link is invalid");
      }
    } catch (IllegalArgumentException exception) {
      throw new IllegalArgumentException("changelog link is invalid", exception);
    }
  }

  private static void requireDescendingVersions(List<Release> releases) {
    assert releases != null && !releases.isEmpty();
    int expected = versionNumber(releases.getFirst().version(), "release version is invalid");
    for (Release release : releases) {
      int version = versionNumber(release.version(), "release version is invalid");
      if (version != expected) {
        throw new IllegalArgumentException("changelog versions must be consecutive and descending");
      }
      expected = Math.subtractExact(expected, 1);
    }
    if (expected != -1) {
      throw new IllegalArgumentException("changelog must include v0");
    }
  }

  private static int versionNumber(String value, String message) {
    assert value != null;
    assert message != null && !message.isBlank();
    if (!value.matches("v(?:0|[1-9][0-9]{0,9})")) throw new IllegalArgumentException(message);
    try {
      return Integer.parseInt(value.substring(1));
    } catch (NumberFormatException exception) {
      throw new IllegalArgumentException(message, exception);
    }
  }

  public record Release(String version, List<Note> notes) {
    public Release {
      Objects.requireNonNull(version, "version");
      notes = List.copyOf(notes);
      versionNumber(version, "release version is invalid");
      if (notes.isEmpty() || notes.size() > NOTES_MAX) {
        throw new IllegalArgumentException("release is invalid");
      }
    }
  }

  public record Note(List<Segment> segments) {
    public Note {
      segments = List.copyOf(segments);
      if (segments.isEmpty() || segments.size() > SEGMENTS_MAX) {
        throw new IllegalArgumentException("release note is invalid");
      }
    }
  }

  public record Segment(SegmentKind kind, String text, String url) {
    public Segment {
      Objects.requireNonNull(kind, "kind");
      Objects.requireNonNull(text, "text");
      Objects.requireNonNull(url, "url");
      if (text.isEmpty()
          || (kind == SegmentKind.LINK) != !url.isEmpty()
          || (kind == SegmentKind.CODE && text.isBlank())) {
        throw new IllegalArgumentException("release note segment is invalid");
      }
    }

    public static Segment text(String text) {
      return new Segment(SegmentKind.TEXT, text, "");
    }

    public static Segment code(String text) {
      return new Segment(SegmentKind.CODE, text, "");
    }

    public static Segment link(String text, String url) {
      return new Segment(SegmentKind.LINK, text, url);
    }

    public boolean textSegment() {
      return kind == SegmentKind.TEXT;
    }

    public boolean codeSegment() {
      return kind == SegmentKind.CODE;
    }

    public boolean linkSegment() {
      return kind == SegmentKind.LINK;
    }
  }

  public enum SegmentKind {
    TEXT,
    CODE,
    LINK
  }
}
