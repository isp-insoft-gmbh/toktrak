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
import java.util.HashMap;
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
    String line = Files.readString(dir.resolve("events.ndjson")).stripTrailing();
    assertTrue(line.contains("\"type\":\"projection-snapshot\""));
    assertTrue(line.contains("\"at\":\"2026-07-10T00:00:00Z\""));
    assertTrue(line.contains("\"actor\":\"system\""));
    assertTrue(line.contains("\"schemaVersion\":1"));
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
  void given_replayLimitMatchingEventCount_when_replayingLog_then_returnsAllEvents() {
    var log = EventLog.open(dir.resolve("events.ndjson"));
    var at = Instant.parse("2026-07-10T00:00:00Z");
    for (int index = 0; index < 3; index++) {
      log.appendAndFsync(EventEnvelope.create("event", at, "system", Map.of()));
    }

    assertEquals(3, log.replayForTest(_ -> {}, 3));
    assertEquals(3, log.replayForTest(_ -> {}, 1_000_000));
  }

  @Test
  void given_largeLinesSharingReadBuffers_when_replayingLog_then_returnsEventsInOrder() {
    var log = EventLog.open(dir.resolve("events.ndjson"));
    var at = Instant.parse("2026-07-10T00:00:00Z");
    var events = new ArrayList<EventEnvelope>();
    for (int index = 0; index < 3; index++) {
      var event =
          EventEnvelope.create(
              "event-" + index, at, "system", Map.of("filler", "a".repeat(30_000)));
      events.add(event);
      log.appendAndFsync(event);
    }

    var replayed = new ArrayList<EventEnvelope>();
    assertEquals(3, log.replay(replayed::add));
    assertEquals(events, replayed);
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
  void given_invalidReplayLimit_when_replayingLog_then_rejectsLimit() {
    var log = EventLog.open(dir.resolve("events.ndjson"));
    for (int eventCountMax : new int[] {0, 1_000_001}) {
      var exception =
          assertThrows(
              IllegalArgumentException.class, () -> log.replayForTest(_ -> {}, eventCountMax));
      assertEquals("eventCountMax must be 1..1000000", exception.getMessage());
    }
  }

  @Test
  void given_closedLog_when_usingLog_then_rejectsOperations() {
    var log = EventLog.open(dir.resolve("events.ndjson"));
    log.close();
    assertThrows(IllegalStateException.class, () -> log.replay(_ -> {}));
    assertThrows(
        IllegalStateException.class,
        () -> log.appendAndFsync(EventEnvelope.create("event", Instant.EPOCH, "system", Map.of())));
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
  void given_emptyLogFile_when_recoveringTornTail_then_reportsNoFragment() throws Exception {
    var path = dir.resolve("events.ndjson");
    Files.createFile(path);

    var recovered = EventLog.recoverTornTail(path);

    assertEquals(0, recovered.truncatedFragments());
    assertEquals(0, Files.size(path));
  }

  @Test
  void given_tornTailAfterLeadingNewline_when_recoveringLog_then_keepsLeadingNewline()
      throws Exception {
    var path = dir.resolve("events.ndjson");
    Files.writeString(path, "\ntorn-fragment");

    var recovered = EventLog.recoverTornTail(path);

    assertEquals(1, recovered.truncatedFragments());
    assertEquals("\n", Files.readString(path));
  }

  @Test
  void given_missingLogFile_when_recoveringTornTail_then_reportsNoFragment() {
    var path = dir.resolve("missing.ndjson");

    var recovered = EventLog.recoverTornTail(path);

    assertEquals(0, recovered.truncatedFragments());
    assertFalse(Files.exists(path));
  }

  @Test
  void given_newlineTerminatedLog_when_recoveringTornTail_then_leavesLogUntouched()
      throws Exception {
    var path = dir.resolve("events.ndjson");
    Files.writeString(path, "first\nsecond\n");

    var recovered = EventLog.recoverTornTail(path);

    assertEquals(0, recovered.truncatedFragments());
    assertEquals("first\nsecond\n", Files.readString(path));
  }

  @Test
  void given_tornTailAfterCompleteLines_when_recoveringLog_then_keepsCompleteLines()
      throws Exception {
    var path = dir.resolve("events.ndjson");
    var at = Instant.parse("2026-07-10T00:00:00Z");
    var first = EventEnvelope.create("first", at, "system", Map.of());
    var second = EventEnvelope.create("second", at, "system", Map.of());
    try (var log = EventLog.open(path)) {
      log.appendAndFsync(first);
      log.appendAndFsync(second);
    }
    String complete = Files.readString(path);
    Files.writeString(path, "{\"id\":\"torn", StandardOpenOption.APPEND);

    var recovered = EventLog.recoverTornTail(path);

    assertEquals(1, recovered.truncatedFragments());
    assertEquals(complete, Files.readString(path));
    var replayed = new ArrayList<EventEnvelope>();
    assertEquals(2, EventLog.open(path).replay(replayed::add));
    assertEquals(List.of(first, second), replayed);
  }

  @Test
  void given_tornTailSpanningReadBuffers_when_recoveringLog_then_keepsCompleteLines()
      throws Exception {
    var path = dir.resolve("events.ndjson");
    String complete = "first\n" + "b".repeat(70_000) + "\n";
    Files.writeString(path, complete + "torn-fragment");

    var recovered = EventLog.recoverTornTail(path);

    assertEquals(1, recovered.truncatedFragments());
    assertEquals(complete, Files.readString(path));
  }

  @Test
  void given_tornTailAboveLineLimit_when_recoveringLog_then_rejectsLog() throws Exception {
    var path = dir.resolve("events.ndjson");
    Files.writeString(path, "a\n" + "y".repeat(EventLog.MAX_LINE_BYTES));

    var ex = assertThrows(IllegalStateException.class, () -> EventLog.recoverTornTail(path));

    assertEquals("event line exceeds 10485760 bytes", ex.getMessage());
    assertEquals(EventLog.MAX_LINE_BYTES + 2L, Files.size(path));
  }

  @Test
  void given_logAtExactFileLimit_when_recoveringTornTail_then_reportsNoFragment() throws Exception {
    var path = dir.resolve("events.ndjson");
    sparseFile(path, EventLog.MAX_FILE_BYTES);

    var recovered = EventLog.recoverTornTail(path);

    assertEquals(0, recovered.truncatedFragments());
    assertEquals(EventLog.MAX_FILE_BYTES, Files.size(path));
  }

  @Test
  void given_appendFillingExactFileLimit_when_appending_then_acceptsEvent() throws Exception {
    var event =
        EventEnvelope.create("event", Instant.parse("2026-07-10T00:00:00Z"), "system", Map.of());
    long lineBytes = appendedLineBytes(event);
    var path = dir.resolve("events.ndjson");
    sparseFile(path, EventLog.MAX_FILE_BYTES - lineBytes);

    try (var log = EventLog.open(path)) {
      log.appendAndFsync(event);
    }

    assertEquals(EventLog.MAX_FILE_BYTES, Files.size(path));
  }

  @Test
  void given_appendAboveFileLimit_when_appending_then_rejectsEvent() throws Exception {
    var event =
        EventEnvelope.create("event", Instant.parse("2026-07-10T00:00:00Z"), "system", Map.of());
    long lineBytes = appendedLineBytes(event);
    var path = dir.resolve("events.ndjson");
    long fileBytes = EventLog.MAX_FILE_BYTES - lineBytes + 1;
    sparseFile(path, fileBytes);

    try (var log = EventLog.open(path)) {
      var ex = assertThrows(IllegalStateException.class, () -> log.appendAndFsync(event));
      assertEquals("event log exceeds 17179869184 bytes", ex.getMessage());
    }

    assertEquals(fileBytes, Files.size(path));
  }

  @Test
  void given_eventLineAtExactLineLimit_when_appendingAndReplaying_then_acceptsLine()
      throws Exception {
    var at = Instant.parse("2026-07-10T00:00:00Z");
    var data = new HashMap<String, Object>();
    for (int index = 0; index < 10; index++) data.put("filler" + index, "a".repeat(1_000_000));
    data.put("pad", "a");
    long probeLineBytes = appendedLineBytes(EventEnvelope.create("event", at, "system", data));
    data.put("pad", "a".repeat((int) (EventLog.MAX_LINE_BYTES - probeLineBytes) + 1));
    var event = EventEnvelope.create("event", at, "system", data);
    var path = dir.resolve("events.ndjson");
    var log = EventLog.open(path);

    log.appendAndFsync(event);

    assertEquals(EventLog.MAX_LINE_BYTES, Files.size(path));
    var replayed = new ArrayList<EventEnvelope>();
    assertEquals(1, log.replay(replayed::add));
    assertEquals(event, replayed.getFirst());
  }

  @Test
  void given_completeLineAboveContentLimit_when_replayingLog_then_rejectsLine() throws Exception {
    var path = dir.resolve("events.ndjson");
    Files.writeString(path, envelopeJson("{}") + "\n" + "x".repeat(EventLog.MAX_LINE_BYTES) + "\n");
    var log = EventLog.open(path);

    var ex = assertThrows(IllegalStateException.class, () -> log.replay(_ -> {}));

    assertEquals("event line exceeds 10485760 bytes", ex.getMessage());
  }

  @Test
  void given_tornTailAtExactLineLimit_when_replayingLog_then_reportsTornTail() throws Exception {
    var path = dir.resolve("events.ndjson");
    Files.writeString(path, "x".repeat(EventLog.MAX_LINE_BYTES));
    var log = EventLog.open(path);

    var ex = assertThrows(IllegalStateException.class, () -> log.replay(_ -> {}));

    assertEquals("event log contains unrecovered torn tail", ex.getMessage());
  }

  @Test
  void given_stalledWriteChannel_when_writingFully_then_rejectsWrite() {
    var channel = new StalledChannel();
    var buffer = ByteBuffer.wrap("stalled write".getBytes(java.nio.charset.StandardCharsets.UTF_8));

    var ex =
        assertThrows(
            IllegalStateException.class, () -> EventLog.writeFullyForTest(channel, buffer));

    assertEquals("event log write made no progress", ex.getMessage());
  }

  private long appendedLineBytes(EventEnvelope event) throws Exception {
    var probePath = dir.resolve("probe.ndjson");
    try (var log = EventLog.open(probePath)) {
      log.appendAndFsync(event);
    }
    long lineBytes = Files.size(probePath);
    Files.delete(probePath);
    return lineBytes;
  }

  private static void sparseFile(Path path, long sizeBytes) throws Exception {
    try (var channel =
        FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
      channel.position(sizeBytes - 1);
      channel.write(ByteBuffer.wrap(new byte[] {'\n'}));
    }
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

  private static final class StalledChannel implements WritableByteChannel {
    @Override
    public int write(ByteBuffer source) {
      return 0;
    }

    @Override
    public boolean isOpen() {
      return true;
    }

    @Override
    public void close() {}
  }
}
