import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/// Reading and writing the text files a run is made of.
///
/// Every file the orchestrator touches is UTF-8, and every failure to touch one is fatal in the
/// same way: the run cannot say what it did. Keeping that in one place is why no caller carries its
/// own `try` block or its own charset.
final class Text {
  private Text() {}

  static String read(Path file) {
    assert file != null : "a file is required";
    try {
      return Files.readString(file, StandardCharsets.UTF_8);
    } catch (IOException failure) {
      throw new UncheckedIOException("cannot read " + file, failure);
    }
  }

  /// Reads a file that a golem may or may not have written.
  static String readIfPresent(Path file) {
    assert file != null : "a file is required";
    return Files.isRegularFile(file) ? read(file) : "";
  }

  /// Writes a file, creating the directories leading to it.
  static void write(Path file, String text) {
    assert file != null : "a file is required";
    try {
      var parent = file.getParent();
      if (parent != null) {
        Files.createDirectories(parent);
      }
      Files.writeString(file, text == null ? "" : text, StandardCharsets.UTF_8);
      assert Files.isRegularFile(file) : "a written file exists";
    } catch (IOException failure) {
      throw new UncheckedIOException("cannot write " + file, failure);
    }
  }

  /// Appends to a file the host owns, such as a job summary, creating it when the run is the first
  /// to write to it.
  static void append(Path file, String text) {
    assert file != null && text != null : "appending needs a file and text";
    try {
      Files.writeString(
          file, text, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    } catch (IOException failure) {
      throw new UncheckedIOException("cannot append to " + file, failure);
    }
  }
}
