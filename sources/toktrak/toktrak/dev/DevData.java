package toktrak.dev;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import toktrak.store.EventLog;

public final class DevData {
  private static final int COPY_BUFFER_BYTES = 64 * 1024;

  private DevData() {}

  public static void prepareDisposableCorpus(Path corpus, Path dataDirectory) {
    prepareDisposableCorpus(corpus, dataDirectory, EventLog.MAX_FILE_BYTES);
  }

  public static void prepareDisposableCorpusForTest(
      Path corpus, Path dataDirectory, long fileBytesMax) {
    if (fileBytesMax <= 0 || fileBytesMax > EventLog.MAX_FILE_BYTES) {
      throw new IllegalArgumentException("fileBytesMax must be 1.." + EventLog.MAX_FILE_BYTES);
    }
    prepareDisposableCorpus(corpus, dataDirectory, fileBytesMax);
  }

  private static void prepareDisposableCorpus(Path corpus, Path dataDirectory, long fileBytesMax) {
    Objects.requireNonNull(corpus, "corpus");
    Objects.requireNonNull(dataDirectory, "dataDirectory");
    assert fileBytesMax > 0 && fileBytesMax <= EventLog.MAX_FILE_BYTES;
    Path temporary = dataDirectory.resolve("events.ndjson.tmp");
    Path destination = dataDirectory.resolve("events.ndjson");
    try {
      long sourceBytes = Files.size(corpus);
      if (sourceBytes > fileBytesMax) {
        throw new IllegalStateException("dev corpus exceeds " + fileBytesMax + " bytes");
      }
      Files.createDirectories(dataDirectory);
      Files.deleteIfExists(temporary);
      byte[] buffer = new byte[COPY_BUFFER_BYTES];
      long copiedBytes = 0;
      long readOperations = 0;
      long readOperationsMax = Math.addExact(sourceBytes, 1);
      try (var input = Files.newInputStream(corpus);
          var output = Files.newOutputStream(temporary, StandardOpenOption.CREATE_NEW)) {
        while (copiedBytes < sourceBytes && readOperations < readOperationsMax) {
          int requestedBytes = (int) Math.min(buffer.length, sourceBytes - copiedBytes);
          int readBytes = input.read(buffer, 0, requestedBytes);
          readOperations = Math.addExact(readOperations, 1);
          if (readBytes <= 0) throw new IllegalStateException("dev corpus changed while copying");
          output.write(buffer, 0, readBytes);
          copiedBytes = Math.addExact(copiedBytes, readBytes);
        }
        if (input.read() >= 0) throw new IllegalStateException("dev corpus changed while copying");
      }
      if (copiedBytes != sourceBytes || Files.size(corpus) != sourceBytes) {
        throw new IllegalStateException("dev corpus changed while copying");
      }
      assert Files.size(temporary) == copiedBytes;
      Files.move(
          temporary,
          destination,
          StandardCopyOption.ATOMIC_MOVE,
          StandardCopyOption.REPLACE_EXISTING);
      assert Files.size(destination) == copiedBytes;
    } catch (IllegalStateException exception) {
      deleteTemporary(temporary, exception);
      throw exception;
    } catch (IOException exception) {
      deleteTemporary(temporary, exception);
      throw new IllegalStateException("cannot prepare dev corpus", exception);
    }
  }

  private static void deleteTemporary(Path temporary, Exception failure) {
    assert temporary != null;
    assert failure != null;
    try {
      Files.deleteIfExists(temporary);
    } catch (IOException cleanupFailure) {
      failure.addSuppressed(cleanupFailure);
    }
  }
}
