package toktrak.perf;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Timeout;
import org.openjdk.jmh.annotations.Warmup;
import toktrak.projection.Projection;
import toktrak.store.EventLog;

/// Measures production event-log replay and projection rebuilding against the stable development
/// corpus.
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 1, jvmArgsAppend = "-ea")
@Threads(1)
@Timeout(time = 10)
@State(Scope.Benchmark)
public class CorpusReplayBenchmark {
  private static final Path CORPUS = Path.of("tests/corpus/dev.jsonl");
  private static final long CORPUS_BYTES = 473_834;
  private static final String CORPUS_SHA256 =
      "4172025f12970c4efabb3cb19f6081905ef5d90ea87a1a6102ac741cb757d3e4";
  private static final int EVENT_COUNT = 14;
  private static final BigDecimal COST_USD = new BigDecimal("6439.3490222899988205311");

  @Setup(Level.Trial)
  public void validateCorpus() throws Exception {
    if (!Files.isRegularFile(CORPUS)
        || Files.size(CORPUS) != CORPUS_BYTES
        || !HexFormat.of()
            .formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(CORPUS)))
            .equals(CORPUS_SHA256)) {
      throw new IllegalStateException("development corpus fixture changed");
    }
    Projection projection = replay();
    if (projection.eventCount() != EVENT_COUNT
        || projection.usageSummary().dailyRows() != 300
        || projection.usageSummary().sessionRows() != 315
        || projection.usageSummary().blockRows() != 335
        || projection.usageSummary().costUsd().compareTo(COST_USD) != 0) {
      throw new IllegalStateException("development corpus semantics changed");
    }
  }

  @Benchmark
  public Projection replay() {
    Projection projection = Projection.empty();
    try (EventLog eventLog = EventLog.open(CORPUS)) {
      int events = eventLog.replay(projection::apply);
      if (events != EVENT_COUNT)
        throw new IllegalStateException("development corpus event count changed");
    }
    return projection;
  }
}
