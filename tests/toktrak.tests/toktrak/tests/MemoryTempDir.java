package toktrak.tests;

import com.github.marschall.memoryfilesystem.MemoryFileSystemBuilder;
import java.io.IOException;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.extension.AnnotatedElementContext;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.io.TempDirFactory;

/// A temporary directory in a fresh in-memory file system with Linux semantics, the production
/// platform. Tests of real file-system guarantees, such as fsync or cross-process locks, keep the
/// plain `@TempDir`.
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
@TempDir(factory = MemoryTempDir.Factory.class)
@interface MemoryTempDir {
  /// JUnit creates one factory per directory and closes it after cleanup.
  final class Factory implements TempDirFactory {
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
}
