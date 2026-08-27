package toktrak.fx;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import toktrak.store.WriteCommand;
import toktrak.store.Writer;

public final class FxService implements AutoCloseable {
  private static final Logger LOG = Logger.getLogger(FxService.class.getName());
  private static final URI ENDPOINT =
      URI.create("https://api.frankfurter.dev/v1/latest?base=USD&symbols=EUR");
  private static final Duration INITIAL_DELAY = Duration.ofMinutes(1);
  private static final Duration INTERVAL = Duration.ofHours(24);
  private final ScheduledExecutorService executor;
  private final ScheduledFuture<?> refreshTask;

  private FxService(Writer writer, FxClient client, Duration initialDelay, Duration interval) {
    Objects.requireNonNull(writer, "writer");
    Objects.requireNonNull(client, "client");
    requireDelay(initialDelay, "initialDelay", true);
    requireDelay(interval, "interval", false);
    executor =
        Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon(true).name("toktrak-fx").factory());
    refreshTask =
        executor.scheduleAtFixedRate(
            () -> refresh(writer, client),
            initialDelay.toMillis(),
            interval.toMillis(),
            TimeUnit.MILLISECONDS);
  }

  public static FxService start(Writer writer) {
    return new FxService(writer, new FxClient(ENDPOINT), INITIAL_DELAY, INTERVAL);
  }

  public static FxService start(
      Writer writer, FxClient client, Duration initialDelay, Duration interval) {
    return new FxService(writer, client, initialDelay, interval);
  }

  private static void refresh(Writer writer, FxClient client) {
    assert writer != null;
    assert client != null;
    try {
      FxClient.Rate rate = client.fetch();
      writer.write(
          WriteCommand.fxRateUpdated(rate.date().toString(), rate.eurPerUsd().toPlainString()));
    } catch (IllegalArgumentException | IllegalStateException exception) {
      LOG.warning("FX refresh failed; retaining last-good rate");
    }
  }

  private static void requireDelay(Duration value, String name, boolean zeroAllowed) {
    Objects.requireNonNull(value, name);
    if (value.isNegative() || (!zeroAllowed && value.isZero()) || value.compareTo(INTERVAL) > 0) {
      throw new IllegalArgumentException(name + " is invalid");
    }
  }

  @Override
  public void close() {
    refreshTask.cancel(true);
    executor.shutdownNow();
    try {
      if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
        LOG.warning("FX scheduler did not terminate");
      }
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
    }
  }
}
