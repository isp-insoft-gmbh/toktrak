package toktrak.tests;

import com.github.marschall.memoryfilesystem.MemoryFileSystemBuilder;
import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.junit.jupiter.api.extension.AnnotatedElementContext;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.io.TempDirFactory;

/// The default factory for every `@TempDir` (see `TestLauncher` and Build's JUnit arguments): a
/// fresh in-memory file system with Linux semantics, the production platform. Tests of real
/// file-system guarantees use `@DiskTempDir` instead. JUnit creates one factory per directory and
/// closes it after cleanup.
final class MemoryTempDirFactory implements TempDirFactory {
  static final String PROPERTY = "junit.jupiter.tempdir.factory.default";

  /// Read-only repository fixture; tests copy it into their in-memory directory.
  static final Path DEV_CORPUS = Path.of("tests/corpus/dev.jsonl");

  /// Fails loudly when `@TempDir` falls back to the disk, for example because a launch path lost
  /// the default-factory property; shared fixtures call it so every launch path checks it.
  static Path requireInMemory(Path directory) {
    if (directory.getFileSystem().equals(FileSystems.getDefault())) {
      throw new IllegalStateException(
          "@TempDir is on the disk; set "
              + PROPERTY
              + " to "
              + MemoryTempDirFactory.class.getName());
    }
    return directory;
  }

  /// Copies the development corpus into `directory`, so an app resolving paths there can read it.
  static Path devCorpusIn(Path directory) throws IOException {
    return Files.copy(
        DEV_CORPUS,
        requireInMemory(directory).resolve("dev.jsonl"),
        StandardCopyOption.REPLACE_EXISTING);
  }

  private FileSystem fileSystem;

  @Override
  public Path createTempDirectory(
      AnnotatedElementContext elementContext, ExtensionContext extensionContext)
      throws IOException {
    assert fileSystem == null;
    fileSystem = MemoryFileSystemBuilder.newLinux().build();
    return Files.createDirectories(fileSystem.getPath("/test"));
  }

  @Override
  public void close() throws IOException {
    if (fileSystem != null) fileSystem.close();
  }
}
