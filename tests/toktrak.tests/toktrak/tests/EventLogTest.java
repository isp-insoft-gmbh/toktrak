package toktrak.tests;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import toktrak.store.EventEnvelope;
import toktrak.store.EventLog;

final class EventLogTest {
  @TempDir Path dir;

  @Test
  void given_newEnvelope_when_appendingAndReplaying_then_returnsNewlineTerminatedEvent()
      throws Exception {
    var log = EventLog.open(dir.resolve("events.ndjson"));
    var event =
        EventEnvelope.create(
            "projection-snapshot",
            Instant.parse("2026-07-10T00:00:00Z"),
            "system",
            Map.of("eventCount", 0));
    log.appendAndFsync(event);
    assertTrue(Files.readString(dir.resolve("events.ndjson")).endsWith("\n"));
    var replayed = new ArrayList<EventEnvelope>();
    assertEquals(1, log.replay(replayed::add));
    assertEquals("projection-snapshot", replayed.getFirst().type());
  }

  @Test
  void given_twoLoggedEvents_when_replayingLog_then_returnsEventsInOrder() {
    var log = EventLog.open(dir.resolve("events.ndjson"));
    var at = Instant.parse("2026-07-10T00:00:00Z");
    var first = EventEnvelope.create("first", at, "system", Map.of());
    var second = EventEnvelope.create("second", at, "system", Map.of());
    log.appendAndFsync(first);
    log.appendAndFsync(second);

    var replayed = new ArrayList<EventEnvelope>();
    assertEquals(2, log.replay(replayed::add));
    assertEquals(List.of(first, second), replayed);
  }

  @Test
  void given_eventCountAboveReplayLimit_when_replayingLog_then_rejectsReplay() {
    var log = EventLog.open(dir.resolve("events.ndjson"));
    var at = Instant.parse("2026-07-10T00:00:00Z");
    for (int index = 0; index < 4; index++) {
      log.appendAndFsync(EventEnvelope.create("event", at, "system", Map.of()));
    }

    var ex = assertThrows(IllegalStateException.class, () -> log.replayForTest(_ -> {}, 3));
    assertEquals("event log exceeds 3 events", ex.getMessage());
  }

  @Test
  void given_logAboveFileLimit_when_recoveringTornTail_then_rejectsLog() throws Exception {
    var path = dir.resolve("events.ndjson");
    try (var channel =
        FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
      channel.position(EventLog.MAX_FILE_BYTES);
      channel.write(ByteBuffer.wrap(new byte[] {0}));
    }

    var ex = assertThrows(IllegalStateException.class, () -> EventLog.recoverTornTail(path));
    assertEquals("event log exceeds 17179869184 bytes", ex.getMessage());
    assertEquals(EventLog.MAX_FILE_BYTES + 1L, Files.size(path));
  }

  @Test
  void given_partialWriteChannel_when_writingFully_then_writesAllBytes() throws Exception {
    var channel = new ThrottledChannel(3);
    var expected = "partial writes must complete".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    EventLog.writeFullyForTest(channel, ByteBuffer.wrap(expected));
    assertArrayEquals(expected, channel.bytes());
    assertTrue(channel.writeCount() > 1);
  }

  @Test
  void given_finalTornTail_when_recoveringLog_then_truncatesFragment() throws Exception {
    var path = dir.resolve("events.ndjson");
    Files.writeString(path, "{\"id\":\"bad-fragment");
    var recovered = EventLog.recoverTornTail(path);
    assertEquals(1, recovered.truncatedFragments());
    assertEquals("", Files.readString(path));
  }

  @Test
  void given_tornTailAtLineLimit_when_recoveringLog_then_truncatesFragment() throws Exception {
    var path = dir.resolve("events.ndjson");
    Files.writeString(path, "x".repeat(EventLog.MAX_LINE_BYTES));

    var recovered = EventLog.recoverTornTail(path);

    assertEquals(1, recovered.truncatedFragments());
    assertEquals(0, Files.size(path));
  }

  @Test
  void given_malformedCompleteLine_when_replayingLog_then_rejectsLine() throws Exception {
    var path = dir.resolve("events.ndjson");
    Files.writeString(path, "{not-json}\n");
    var log = EventLog.open(path);
    var ex = assertThrows(IllegalStateException.class, () -> log.replay(_ -> {}));
    assertTrue(ex.getMessage().contains("malformed event line 1"));
  }

  @Test
  void given_invalidUtf8CompleteLine_when_replayingLog_then_rejectsLine() throws Exception {
    var path = dir.resolve("events.ndjson");
    var bytes = new ByteArrayOutputStream();
    bytes.writeBytes(
        "{\"id\":\"00000000-0000-4000-8000-000000000001\","
            .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    bytes.writeBytes(
        "\"at\":\"2026-07-10T00:00:00Z\",\"type\":\"event\","
            .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    bytes.writeBytes(
        "\"schemaVersion\":1,\"actor\":\"".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    bytes.write(0xC3);
    bytes.writeBytes("\",\"data\":{}}\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    Files.write(path, bytes.toByteArray());
    var log = EventLog.open(path);

    assertThrows(IllegalStateException.class, () -> log.replay(_ -> {}));
  }

  @Test
  void given_jsonAboveNestingLimit_when_replayingLog_then_rejectsLine() throws Exception {
    var path = dir.resolve("events.ndjson");
    String nested = "[".repeat(33) + "0" + "]".repeat(33);
    Files.writeString(path, envelopeJson("{\"nested\":" + nested + "}") + "\n");
    var log = EventLog.open(path);

    assertThrows(IllegalStateException.class, () -> log.replay(_ -> {}));
  }

  @Test
  void given_jsonNumberAboveLengthLimit_when_replayingLog_then_rejectsLine() throws Exception {
    var path = dir.resolve("events.ndjson");
    Files.writeString(path, envelopeJson("{\"number\":" + "1".repeat(257) + "}") + "\n");
    var log = EventLog.open(path);

    assertThrows(IllegalStateException.class, () -> log.replay(_ -> {}));
  }

  @Test
  void given_lineAboveLengthLimit_when_replayingLog_then_rejectsLine() throws Exception {
    var path = dir.resolve("events.ndjson");
    Files.writeString(path, "{\"x\":\"" + "a".repeat(10 * 1024 * 1024) + "\"}\n");
    var log = EventLog.open(path);
    var ex = assertThrows(IllegalStateException.class, () -> log.replay(_ -> {}));
    assertTrue(ex.getMessage().contains("event line exceeds 10485760 bytes"));
  }

  private static String envelopeJson(String data) {
    return "{\"id\":\"00000000-0000-4000-8000-000000000001\","
        + "\"at\":\"2026-07-10T00:00:00Z\",\"type\":\"event\","
        + "\"schemaVersion\":1,\"actor\":\"system\",\"data\":"
        + data
        + "}";
  }

  private static final class ThrottledChannel implements WritableByteChannel {
    private final int writeBytesMax;
    private final ByteArrayOutputStream output = new ByteArrayOutputStream();
    private boolean open = true;
    private int writeCount;

    private ThrottledChannel(int writeBytesMax) {
      this.writeBytesMax = writeBytesMax;
    }

    @Override
    public int write(ByteBuffer source) {
      int count = Math.min(writeBytesMax, source.remaining());
      byte[] bytes = new byte[count];
      source.get(bytes);
      output.writeBytes(bytes);
      writeCount++;
      return count;
    }

    @Override
    public boolean isOpen() {
      return open;
    }

    @Override
    public void close() {
      open = false;
    }

    private byte[] bytes() {
      return output.toByteArray();
    }

    private int writeCount() {
      return writeCount;
    }
  }
}
