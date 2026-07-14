package toktrak.store;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.atomic.AtomicBoolean;

public final class DataLock implements AutoCloseable {
  private static final String MESSAGE = "TokTrak data directory is already locked";
  private final FileChannel channel;
  private final FileLock lock;
  private final AtomicBoolean closed = new AtomicBoolean();

  private DataLock(FileChannel channel, FileLock lock) {
    this.channel = channel;
    this.lock = lock;
  }

  public static DataLock acquire(Path dataDir) {
    try {
      Files.createDirectories(dataDir);
      FileChannel channel = FileChannel.open(
          dataDir.resolve("toktrak.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
      try {
        FileLock lock = channel.tryLock();
        if (lock == null) {
          channel.close();
          throw new IllegalStateException(MESSAGE);
        }
        return new DataLock(channel, lock);
      } catch (OverlappingFileLockException ex) {
        channel.close();
        throw new IllegalStateException(MESSAGE, ex);
      }
    } catch (IllegalStateException ex) {
      throw ex;
    } catch (IOException ex) {
      throw new IllegalStateException("cannot lock TokTrak data directory", ex);
    }
  }

  @Override
  public void close() {
    if (!closed.compareAndSet(false, true)) return;
    try {
      lock.release();
      channel.close();
    } catch (IOException ex) {
      throw new IllegalStateException("cannot release TokTrak data directory lock", ex);
    }
  }
}
