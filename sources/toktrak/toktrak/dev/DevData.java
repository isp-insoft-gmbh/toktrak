package toktrak.dev;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public final class DevData {
  private DevData() {}

  public static void prepareDisposableCorpus(Path corpus, Path dataDir) {
    try {
      Files.createDirectories(dataDir);
      Files.deleteIfExists(dataDir.resolve("events.ndjson"));
      Files.copy(corpus, dataDir.resolve("events.ndjson"), StandardCopyOption.REPLACE_EXISTING);
    } catch (IOException ex) {
      throw new IllegalStateException("cannot prepare dev corpus", ex);
    }
  }
}
