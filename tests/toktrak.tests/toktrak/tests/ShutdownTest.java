package toktrak.tests;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import toktrak.*;
import toktrak.health.HealthState;
import toktrak.projection.Projection;
import toktrak.store.EventLog;
import toktrak.store.WriteCommand;
import toktrak.store.Writer;

final class ShutdownTest {
  @TempDir Path dir;

  @Test
  void given_runningApp_when_closingTwice_then_completesWithoutError() {
    var app =
        App.start(
            new String[] {},
            Map.of(
                "TOKTRAK_DEV_AUTH",
                "true",
                "TOKTRAK_PORT",
                "0",
                "TOKTRAK_DATA_DIR",
                dir.toString()));
    assertDoesNotThrow(app::close);
    assertDoesNotThrow(app::close);
  }

  @Test
  void given_pausedWriterWithAcceptedCommand_when_closingWriter_then_drainsCommand()
      throws Exception {
    var log = EventLog.open(dir.resolve("events.ndjson"));
    var writer =
        Writer.startForTest(log, Projection.empty(), new HealthState(), ClockSource.system(), 8);
    writer.pauseForTest();
    var future = writer.submit(WriteCommand.devTest("system"));
    writer.close();
    assertTrue(future.get(2, TimeUnit.SECONDS).eventId().isPresent());
    assertEquals(1, log.replay(_ -> {}));
  }
}
