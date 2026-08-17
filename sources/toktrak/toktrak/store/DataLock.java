package toktrak.store;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

public final class DataLock implements AutoCloseable {
  private static final String MESSAGE = "TokTrak data directory is already locked";
  private final FileChannel channel;
  private final FileLock lock;
  private final AtomicBoolean closed = new AtomicBoolean();

  private DataLock(Path dataDirectory) throws IOException {
    channel =
        FileChannel.open(
            dataDirectory.resolve("toktrak.lock"),
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE);
    try {
      lock = channel.tryLock();
    } catch (OverlappingFileLockException exception) {
      closeChannel();
      throw new IllegalStateException(MESSAGE, exception);
    } catch (IOException exception) {
      closeChannel();
      throw exception;
    }
    if (lock == null) {
      closeChannel();
      throw new IllegalStateException(MESSAGE);
    }
    assert channel.isOpen();
    assert lock.isValid();
  }

  public static DataLock acquire(Path dataDirectory) {
    Objects.requireNonNull(dataDirectory, "dataDirectory");
    try {
      Files.createDirectories(dataDirectory);
      return new DataLock(dataDirectory);
    } catch (IOException exception) {
      throw new IllegalStateException("cannot lock TokTrak data directory", exception);
    }
  }

  private void closeChannel() throws IOException {
    channel.close();
  }

  @Override
  public void close() {
    if (!closed.compareAndSet(false, true)) return;
    try {
      lock.release();
      channel.close();
      assert !lock.isValid();
      assert !channel.isOpen();
    } catch (IOException exception) {
      throw new IllegalStateException("cannot release TokTrak data directory lock", exception);
    }
  }
}
