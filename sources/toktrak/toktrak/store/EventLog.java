package toktrak.store;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import toktrak.json.Json;

public final class EventLog implements AutoCloseable {
  public static final int MAX_LINE_BYTES = 10 * 1024 * 1024;
  private final Path path;

  private EventLog(Path path) {
    this.path = path;
  }

  public static EventLog open(Path path) {
    try {
      Files.createDirectories(path.toAbsolutePath().getParent());
      if (!Files.exists(path)) Files.createFile(path);
      return new EventLog(path);
    } catch (IOException ex) {
      throw new IllegalStateException("cannot open event log", ex);
    }
  }

  public synchronized void appendAndFsync(EventEnvelope event) {
    byte[] line = (Json.write(event) + "\n").getBytes(StandardCharsets.UTF_8);
    if (line.length > MAX_LINE_BYTES) throw new IllegalStateException("event line exceeds " + MAX_LINE_BYTES + " bytes");
    try (var channel = java.nio.channels.FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
      channel.write(java.nio.ByteBuffer.wrap(line));
      channel.force(true);
    } catch (IOException ex) {
      throw new IllegalStateException("cannot append event log", ex);
    }
  }

  public List<EventEnvelope> readAll() {
    try {
      byte[] bytes = Files.readAllBytes(path);
      var events = new ArrayList<EventEnvelope>();
      int start = 0;
      int line = 1;
      for (int i = 0; i < bytes.length; i++) {
        if (bytes[i] == '\n') {
          parseLine(bytes, start, i, line, events);
          start = i + 1;
          line++;
        }
      }
      if (start < bytes.length) throw new IllegalStateException("event log contains unrecovered torn tail");
      return List.copyOf(events);
    } catch (IOException ex) {
      throw new IllegalStateException("cannot read event log", ex);
    }
  }

  private static void parseLine(byte[] bytes, int start, int end, int line, List<EventEnvelope> events) {
    int length = end - start;
    if (length > MAX_LINE_BYTES) throw new IllegalStateException("event line exceeds " + MAX_LINE_BYTES + " bytes");
    try {
      String value = new String(bytes, start, length, StandardCharsets.UTF_8);
      events.add(Json.read(value, EventEnvelope.class));
    } catch (RuntimeException ex) {
      throw new IllegalStateException("malformed event line " + line, ex);
    }
  }

  public static Recovery recoverTornTail(Path path) {
    try {
      if (!Files.exists(path)) return new Recovery(0);
      byte[] bytes = Files.readAllBytes(path);
      if (bytes.length == 0 || bytes[bytes.length - 1] == '\n') return new Recovery(0);
      int lastNewline = -1;
      for (int i = bytes.length - 1; i >= 0; i--) {
        if (bytes[i] == '\n') {
          lastNewline = i;
          break;
        }
      }
      try (var channel = java.nio.channels.FileChannel.open(path, StandardOpenOption.WRITE)) {
        channel.truncate(lastNewline + 1L);
        channel.force(true);
      }
      return new Recovery(1);
    } catch (IOException ex) {
      throw new IllegalStateException("cannot recover event log", ex);
    }
  }

  public record Recovery(int truncatedFragments) {}

  @Override
  public void close() {}
}
