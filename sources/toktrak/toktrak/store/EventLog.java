package toktrak.store;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import toktrak.json.Json;

public final class EventLog implements AutoCloseable {
  public static final long MAX_FILE_BYTES = 16L * 1024 * 1024 * 1024;
  public static final int MAX_LINE_BYTES = 10 * 1024 * 1024;
  public static final int MAX_EVENT_COUNT = 1_000_000;
  private static final int READ_BUFFER_BYTES = 64 * 1024;

  private final Path path;
  private final AtomicBoolean closed = new AtomicBoolean();

  private EventLog(Path path) {
    assert path != null;
    this.path = path;
  }

  public static EventLog open(Path path) {
    Objects.requireNonNull(path, "path");
    try {
      Path absolutePath = path.toAbsolutePath();
      Path parent = absolutePath.getParent();
      if (parent == null) throw new IllegalArgumentException("event log path requires a parent");
      Files.createDirectories(parent);
      if (!Files.exists(path)) Files.createFile(path);
      var eventLog = new EventLog(path);
      assert Files.isRegularFile(path);
      return eventLog;
    } catch (IOException exception) {
      throw new IllegalStateException("cannot open event log", exception);
    }
  }

  public void appendAndFsync(EventEnvelope event) {
    synchronized (this) {
      Objects.requireNonNull(event, "event");
      requireOpen();
      byte[] line = (Json.write(event) + "\n").getBytes(StandardCharsets.UTF_8);
      if (line.length > MAX_LINE_BYTES) {
        throw new IllegalStateException("event line exceeds " + MAX_LINE_BYTES + " bytes");
      }
      try {
        long fileBytes = requireFileSizeWithinLimit();
        long resultingFileBytes = resultingFileBytes(fileBytes, line.length);
        try (var channel =
            FileChannel.open(
                path,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.APPEND)) {
          ByteBuffer buffer = ByteBuffer.wrap(line);
          writeFully(channel, buffer);
          assert !buffer.hasRemaining();
          channel.force(true);
        }
        assert Files.size(path) == resultingFileBytes;
      } catch (IOException exception) {
        throw new IllegalStateException("cannot append event log", exception);
      }
    }
  }

  public int replay(Consumer<EventEnvelope> consumer) {
    synchronized (this) {
      return replay(consumer, MAX_EVENT_COUNT);
    }
  }

  public int replayForTest(Consumer<EventEnvelope> consumer, int eventCountMax) {
    synchronized (this) {
      if (eventCountMax <= 0 || eventCountMax > MAX_EVENT_COUNT) {
        throw new IllegalArgumentException("eventCountMax must be 1.." + MAX_EVENT_COUNT);
      }
      return replay(consumer, eventCountMax);
    }
  }

  private int replay(Consumer<EventEnvelope> consumer, int eventCountMax) {
    Objects.requireNonNull(consumer, "consumer");
    assert eventCountMax > 0 && eventCountMax <= MAX_EVENT_COUNT;
    requireOpen();
    try {
      long fileBytes = requireFileSizeWithinLimit();
      byte[] inputBuffer = new byte[READ_BUFFER_BYTES];
      var lineBuffer = new ByteArrayOutputStream(Math.min(MAX_LINE_BYTES, READ_BUFFER_BYTES));
      long fileBytesRead = 0;
      int eventCount = 0;
      try (var input = Files.newInputStream(path)) {
        while (fileBytesRead < fileBytes) {
          int requestedBytes = (int) Math.min(inputBuffer.length, fileBytes - fileBytesRead);
          int readBytes = input.read(inputBuffer, 0, requestedBytes);
          if (readBytes <= 0) throw new IllegalStateException("event log changed during replay");
          fileBytesRead = Math.addExact(fileBytesRead, readBytes);
          int segmentStart = 0;
          for (int index = 0; index < readBytes; index++) {
            if (inputBuffer[index] != '\n') continue;
            appendLineSegment(lineBuffer, inputBuffer, segmentStart, index - segmentStart, true);
            eventCount = Math.addExact(eventCount, 1);
            if (eventCount > eventCountMax) {
              throw new IllegalStateException("event log exceeds " + eventCountMax + " events");
            }
            parseLine(lineBuffer.toByteArray(), eventCount, consumer);
            lineBuffer.reset();
            segmentStart = index + 1;
          }
          appendLineSegment(lineBuffer, inputBuffer, segmentStart, readBytes - segmentStart, false);
        }
      }
      if (lineBuffer.size() != 0) {
        throw new IllegalStateException("event log contains unrecovered torn tail");
      }
      if (Files.size(path) != fileBytes)
        throw new IllegalStateException("event log changed during replay");
      assert fileBytesRead == fileBytes;
      assert eventCount >= 0 && eventCount <= eventCountMax;
      return eventCount;
    } catch (IOException exception) {
      throw new IllegalStateException("cannot read event log", exception);
    }
  }

  private static void appendLineSegment(
      ByteArrayOutputStream lineBuffer,
      byte[] input,
      int offset,
      int length,
      boolean lineComplete) {
    assert lineBuffer != null;
    assert input != null;
    assert offset >= 0 && length >= 0 && offset + length <= input.length;
    int contentBytesMax = MAX_LINE_BYTES - (lineComplete ? 1 : 0);
    if (length > contentBytesMax - lineBuffer.size()) {
      throw new IllegalStateException("event line exceeds " + MAX_LINE_BYTES + " bytes");
    }
    lineBuffer.write(input, offset, length);
    assert lineBuffer.size() <= contentBytesMax;
  }

  private static void parseLine(byte[] bytes, int lineNumber, Consumer<EventEnvelope> consumer) {
    assert bytes != null;
    assert lineNumber > 0;
    assert consumer != null;
    try {
      EventEnvelope event = Json.read(bytes, EventEnvelope.class);
      assert event != null;
      consumer.accept(event);
    } catch (IllegalStateException exception) {
      throw new IllegalStateException("malformed event line " + lineNumber, exception);
    }
  }

  public static Recovery recoverTornTail(Path path) {
    Objects.requireNonNull(path, "path");
    try {
      if (!Files.exists(path)) return new Recovery(0);
      long fileBytes = requireFileSizeWithinLimit(path);
      if (fileBytes == 0) return new Recovery(0);
      try (var channel =
          FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
        if (readByte(channel, fileBytes - 1) == '\n') return new Recovery(0);
        byte[] bytes = new byte[READ_BUFFER_BYTES];
        long position = fileBytes;
        long scannedBytes = 0;
        long truncateBytes = -1;
        while (position > 0 && scannedBytes < MAX_LINE_BYTES) {
          int blockBytes =
              (int) Math.min(bytes.length, Math.min(position, MAX_LINE_BYTES - scannedBytes));
          assert blockBytes > 0;
          position -= blockBytes;
          ByteBuffer buffer = ByteBuffer.wrap(bytes, 0, blockBytes);
          readFully(channel, buffer, position);
          for (int index = blockBytes - 1; index >= 0; index--) {
            if (bytes[index] == '\n') {
              truncateBytes = Math.addExact(position, index + 1L);
              break;
            }
          }
          scannedBytes = Math.addExact(scannedBytes, blockBytes);
          if (truncateBytes >= 0) break;
        }
        if (truncateBytes < 0) {
          if (fileBytes > MAX_LINE_BYTES) {
            throw new IllegalStateException("event line exceeds " + MAX_LINE_BYTES + " bytes");
          }
          truncateBytes = 0;
        }
        channel.truncate(truncateBytes);
        channel.force(true);
      }
      return new Recovery(1);
    } catch (IOException exception) {
      throw new IllegalStateException("cannot recover event log", exception);
    }
  }

  private static long resultingFileBytes(long fileBytes, int lineBytes) {
    assert fileBytes >= 0 && fileBytes <= MAX_FILE_BYTES;
    assert lineBytes > 0 && lineBytes <= MAX_LINE_BYTES;
    long resultingFileBytes = Math.addExact(fileBytes, lineBytes);
    if (resultingFileBytes > MAX_FILE_BYTES) {
      throw new IllegalStateException("event log exceeds " + MAX_FILE_BYTES + " bytes");
    }
    return resultingFileBytes;
  }

  public static long resultingFileBytesForTest(long fileBytes, int lineBytes) {
    return resultingFileBytes(fileBytes, lineBytes);
  }

  private long requireFileSizeWithinLimit() throws IOException {
    return requireFileSizeWithinLimit(path);
  }

  private static long requireFileSizeWithinLimit(Path path) throws IOException {
    return requireFileBytesWithinLimit(Files.size(path));
  }

  private static long requireFileBytesWithinLimit(long fileBytes) {
    assert fileBytes >= 0;
    if (fileBytes > MAX_FILE_BYTES) {
      throw new IllegalStateException("event log exceeds " + MAX_FILE_BYTES + " bytes");
    }
    return fileBytes;
  }

  public static long requireFileBytesWithinLimitForTest(long fileBytes) {
    return requireFileBytesWithinLimit(fileBytes);
  }

  private static byte readByte(FileChannel channel, long position) throws IOException {
    assert channel != null;
    assert position >= 0;
    ByteBuffer buffer = ByteBuffer.allocate(1);
    readFully(channel, buffer, position);
    return buffer.array()[0];
  }

  private static void readFully(FileChannel channel, ByteBuffer buffer, long position)
      throws IOException {
    assert channel != null;
    assert buffer != null;
    assert position >= 0;
    int readOperations = 0;
    int readOperationsMax = Math.addExact(buffer.remaining(), 1);
    while (buffer.hasRemaining()) {
      int readBytes = channel.read(buffer, position);
      if (readBytes <= 0) throw new IllegalStateException("event log changed during read");
      position = Math.addExact(position, readBytes);
      readOperations = Math.addExact(readOperations, 1);
      if (readOperations > readOperationsMax)
        throw new IllegalStateException("event log read made insufficient progress");
    }
    assert !buffer.hasRemaining();
  }

  private static void writeFully(WritableByteChannel channel, ByteBuffer buffer)
      throws IOException {
    assert channel != null;
    assert buffer != null;
    int writeOperations = 0;
    int writeOperationsMax = Math.addExact(buffer.remaining(), 1);
    while (buffer.hasRemaining()) {
      int writtenBytes = channel.write(buffer);
      if (writtenBytes <= 0) throw new IllegalStateException("event log write made no progress");
      writeOperations = Math.addExact(writeOperations, 1);
      if (writeOperations > writeOperationsMax) {
        throw new IllegalStateException("event log write made insufficient progress");
      }
    }
    assert !buffer.hasRemaining();
  }

  public static void writeFullyForTest(WritableByteChannel channel, ByteBuffer buffer)
      throws IOException {
    assert channel != null;
    assert buffer != null;
    writeFully(channel, buffer);
  }

  private void requireOpen() {
    if (closed.get()) throw new IllegalStateException("event log is closed");
  }

  public record Recovery(int truncatedFragments) {
    public Recovery {
      if (truncatedFragments < 0 || truncatedFragments > 1) {
        throw new IllegalArgumentException("truncatedFragments must be 0..1");
      }
    }
  }

  @Override
  public void close() {
    closed.set(true);
  }
}
