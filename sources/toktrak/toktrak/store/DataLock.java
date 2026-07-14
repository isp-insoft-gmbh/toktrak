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

  private DataLock(FileChannel channel, FileLock lock) {
    assert channel != null && channel.isOpen();
    assert lock != null && lock.isValid();
    this.channel = channel;
    this.lock = lock;
  }

  public static DataLock acquire(Path dataDirectory) {
    Objects.requireNonNull(dataDirectory, "dataDirectory");
    try {
      Files.createDirectories(dataDirectory);
      FileChannel channel =
          FileChannel.open(
              dataDirectory.resolve("toktrak.lock"),
              StandardOpenOption.CREATE,
              StandardOpenOption.WRITE);
      try {
        FileLock lock = channel.tryLock();
        if (lock == null) {
          channel.close();
          throw new IllegalStateException(MESSAGE);
        }
        var dataLock = new DataLock(channel, lock);
        assert lock.isValid();
        return dataLock;
      } catch (OverlappingFileLockException exception) {
        channel.close();
        throw new IllegalStateException(MESSAGE, exception);
      }
    } catch (IllegalStateException exception) {
      throw exception;
    } catch (IOException exception) {
      throw new IllegalStateException("cannot lock TokTrak data directory", exception);
    }
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
