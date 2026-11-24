package toktrak.store;

import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import toktrak.ClockSource;
import toktrak.health.HealthState;
import toktrak.projection.Projection;

public final class Writer implements AutoCloseable {
  private static final int QUEUE_CAPACITY = 1_024;
  private static final Duration DRAIN_TIMEOUT = Duration.ofSeconds(10);
  private static final Duration ABORT_TIMEOUT = Duration.ofSeconds(5);
  private static final Duration WRITE_TIMEOUT = Duration.ofSeconds(10);
  private static final Duration TIMEOUT_MAX = Duration.ofMinutes(1);
  private static final Duration POLL_INTERVAL = Duration.ofMillis(100);

  private final EventLog log;
  private final Projection projection;
  private final HealthState health;
  private final ClockSource clock;
  private final ArrayBlockingQueue<Request> queue;
  private final Duration drainTimeout;
  private final Duration abortTimeout;

  /// Fallback re-check interval; submissions and close wake the writer at once.
  private final long pollMillis;

  private final Runnable afterClaim;
  private final Runnable afterFsync;
  private final AtomicBoolean closed = new AtomicBoolean();
  private final AtomicBoolean abort = new AtomicBoolean();
  private final AtomicReference<Request> inFlight = new AtomicReference<>();
  private final Object stateMonitor = new Object();
  private final Object commitMonitor = new Object();
  private final Object pauseMonitor = new Object();
  private volatile boolean pauseRequested;
  private final AtomicBoolean paused = new AtomicBoolean();

  /// True while the writer waits for work; changes and waits happen under `stateMonitor`.
  private final AtomicBoolean idle = new AtomicBoolean();

  private final Thread thread;

  private Writer(
      EventLog log,
      Projection projection,
      HealthState health,
      ClockSource clock,
      int capacity,
      Duration drainTimeout,
      Duration abortTimeout,
      Duration pollInterval,
      Runnable afterClaim,
      Runnable afterFsync) {
    assert log != null;
    assert projection != null;
    assert health != null;
    assert clock != null;
    this.log = log;
    this.projection = projection;
    this.health = health;
    this.clock = clock;
    if (capacity <= 0 || capacity > QUEUE_CAPACITY) {
      throw new IllegalArgumentException("capacity must be 1.." + QUEUE_CAPACITY);
    }
    this.drainTimeout = requireTimeout(drainTimeout, "drainTimeout");
    this.abortTimeout = requireTimeout(abortTimeout, "abortTimeout");
    // Object.wait(0) waits forever, so the interval needs at least one whole millisecond.
    this.pollMillis = requireTimeout(pollInterval, "pollInterval").toMillis();
    if (pollMillis < 1) throw new IllegalArgumentException("pollInterval must be at least 1 ms");
    assert afterClaim != null;
    assert afterFsync != null;
    this.afterClaim = afterClaim;
    this.afterFsync = afterFsync;
    this.queue = new ArrayBlockingQueue<>(capacity);
    this.thread = Thread.ofPlatform().daemon(true).name("toktrak-writer").start(this::run);
    assert thread.isDaemon();
  }

  public static Writer start(
      EventLog log, Projection projection, HealthState health, ClockSource clock) {
    return new Writer(
        log,
        projection,
        health,
        clock,
        QUEUE_CAPACITY,
        DRAIN_TIMEOUT,
        ABORT_TIMEOUT,
        POLL_INTERVAL,
        Writer::noop,
        Writer::noop);
  }

  public static Writer startForTest(
      EventLog log, Projection projection, HealthState health, ClockSource clock, int capacity) {
    return startForTest(log, projection, health, clock, capacity, POLL_INTERVAL);
  }

  /// A long `pollInterval` turns a missed wake-up into a hang instead of a short delay.
  public static Writer startForTest(
      EventLog log,
      Projection projection,
      HealthState health,
      ClockSource clock,
      int capacity,
      Duration pollInterval) {
    return new Writer(
        log,
        projection,
        health,
        clock,
        capacity,
        DRAIN_TIMEOUT,
        ABORT_TIMEOUT,
        pollInterval,
        Writer::noop,
        Writer::noop);
  }

  public static Writer startForTest(
      EventLog log,
      Projection projection,
      HealthState health,
      ClockSource clock,
      int capacity,
      Duration drainTimeout,
      Duration abortTimeout) {
    return new Writer(
        log,
        projection,
        health,
        clock,
        capacity,
        drainTimeout,
        abortTimeout,
        POLL_INTERVAL,
        Writer::noop,
        Writer::noop);
  }

  public static Writer startForTest(
      EventLog log,
      Projection projection,
      HealthState health,
      ClockSource clock,
      int capacity,
      Duration drainTimeout,
      Duration abortTimeout,
      Runnable afterClaim,
      Runnable afterFsync) {
    return new Writer(
        log,
        projection,
        health,
        clock,
        capacity,
        drainTimeout,
        abortTimeout,
        POLL_INTERVAL,
        afterClaim,
        afterFsync);
  }

  public CompletableFuture<WriteResult> submit(WriteCommand command) {
    return trySubmit(command).future();
  }

  public WriteResult write(WriteCommand command) {
    Submission submission = trySubmit(command);
    if (!submission.accepted()) throw new IllegalStateException("writer is unavailable");
    try {
      return submission.future().get(WRITE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("write interrupted", exception);
    } catch (java.util.concurrent.ExecutionException exception) {
      Throwable cause = exception.getCause();
      String message = cause == null ? "write failed" : cause.getMessage();
      throw new IllegalStateException(message, exception);
    } catch (java.util.concurrent.TimeoutException exception) {
      throw new IllegalStateException("write failed", exception);
    }
  }

  public Submission trySubmit(WriteCommand command) {
    Objects.requireNonNull(command, "command");
    var future = new CompletableFuture<WriteResult>();
    synchronized (stateMonitor) {
      if (closed.get()) {
        return new Submission(
            false, CompletableFuture.failedFuture(new IllegalStateException("writer is closed")));
      }
      if (!queue.offer(new Request(command, future))) {
        return new Submission(
            false,
            CompletableFuture.failedFuture(new IllegalStateException("writer queue is full")));
      }
      stateMonitor.notifyAll();
    }
    return new Submission(true, future);
  }

  public boolean runningForTest() {
    return thread.isAlive();
  }

  public Projection projection() {
    assert projection != null;
    return projection;
  }

  /// Asks the writer to pause before it claims the next command; tests wait for `pausedForTest`.
  public void requestPauseForTest() {
    pauseRequested = true;
    // An idle writer rechecks the request under stateMonitor before it waits, so this wake-up
    // cannot be lost.
    synchronized (stateMonitor) {
      stateMonitor.notifyAll();
    }
  }

  public boolean pausedForTest() {
    return paused.get();
  }

  /// True while the writer waits for work, so a following submission or close must wake it.
  public boolean idleForTest() {
    return idle.get();
  }

  private void run() {
    boolean drained = false;
    try {
      while (true) {
        Request request = claimNext();
        if (request == null) {
          drained = true;
          return;
        }
        afterClaim.run();
        processClaimed(request);
      }
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
    } finally {
      stop(drained);
    }
  }

  /// Ends the writer thread. Any exit other than draining after close, such as an unexpected
  /// exception, degrades health. Nobody processes commands afterwards, so new ones are refused and
  /// outstanding ones fail at once; a claimed command may already be durable.
  private void stop(boolean drained) {
    if (!drained) health.degrade("writes_failed");
    synchronized (stateMonitor) {
      closed.set(true);
      failOutstanding("writer stopped; outcome is unknown", "writer stopped");
    }
  }

  private Request claimNext() throws InterruptedException {
    while (true) {
      awaitResume();
      synchronized (stateMonitor) {
        if (queue.isEmpty()) {
          if (closed.get()) return null;
          if (pauseRequested) continue;
          idle.set(true);
          try {
            stateMonitor.wait(pollMillis);
          } finally {
            idle.set(false);
          }
          continue;
        }
        Request request = queue.remove();
        if (!inFlight.compareAndSet(null, request)) {
          throw new IllegalStateException("writer already has an in-flight request");
        }
        return request;
      }
    }
  }

  /// Holds the writer while a test pause is requested; only close ends a pause.
  private void awaitResume() throws InterruptedException {
    synchronized (pauseMonitor) {
      while (pauseRequested && !closed.get()) {
        paused.set(true);
        pauseMonitor.wait(pollMillis);
      }
    }
  }

  private void processClaimed(Request request) {
    assert request != null;
    assert inFlight.compareAndSet(request, request);
    process(request);
    // An exception keeps the request in flight, so the stopping writer fails it at once.
    boolean cleared = inFlight.compareAndSet(request, null);
    assert cleared;
  }

  private void process(Request request) {
    assert request != null;
    try {
      health.requireWritable();
      Instant at = Objects.requireNonNull(clock.instant(), "clock instant");
      if (abort.get()) throw new IllegalStateException("writer shutdown aborted write");
      EventEnvelope event = request.command.event(at, projection);
      assert event != null;
      Projection.Transition transition = projection.prepare(event);
      log.appendAndFsync(event);
      afterFsync.run();
      synchronized (commitMonitor) {
        if (abort.get()) throw new IllegalStateException("writer shutdown outcome is unknown");
        projection.commit(transition);
        boolean completed = request.future.complete(new WriteResult(Optional.of(event.id())));
        assert completed;
      }
    } catch (WriteCommand.RejectedException exception) {
      request.future.completeExceptionally(exception);
    } catch (ArithmeticException
        | ArrayStoreException
        | ClassCastException
        | DateTimeException
        | EnumConstantNotPresentException
        | IllegalArgumentException
        | IllegalMonitorStateException
        | IllegalStateException
        | IndexOutOfBoundsException
        | NegativeArraySizeException
        | SecurityException
        | TypeNotPresentException
        | UnsupportedOperationException exception) {
      health.degrade("writes_failed");
      request.future.completeExceptionally(exception);
    }
  }

  @Override
  public void close() {
    synchronized (stateMonitor) {
      if (!closed.compareAndSet(false, true)) return;
      stateMonitor.notifyAll();
    }
    synchronized (pauseMonitor) {
      pauseRequested = false;
      pauseMonitor.notifyAll();
    }
    boolean interrupted = false;
    try {
      join(drainTimeout);
      if (thread.isAlive()) {
        synchronized (commitMonitor) {
          abort.set(true);
        }
        health.degrade("writes_failed");
        thread.interrupt();
        join(abortTimeout);
      }
    } catch (InterruptedException exception) {
      interrupted = true;
      synchronized (commitMonitor) {
        abort.set(true);
      }
      health.degrade("writes_failed");
      thread.interrupt();
    } finally {
      failOutstanding("writer is closed", "writer is closed");
      if (interrupted) Thread.currentThread().interrupt();
    }
  }

  private void join(Duration timeout) throws InterruptedException {
    assert timeout != null && !timeout.isNegative() && !timeout.isZero();
    long timeoutNanos = timeout.toNanos();
    long timeoutMillis = timeoutNanos / 1_000_000;
    int additionalNanos = (int) (timeoutNanos % 1_000_000);
    thread.join(timeoutMillis, additionalNanos);
  }

  private void failOutstanding(String inFlightMessage, String queuedMessage) {
    assert inFlightMessage != null && !inFlightMessage.isBlank();
    assert queuedMessage != null && !queuedMessage.isBlank();
    var failure = new IllegalStateException(queuedMessage);
    synchronized (stateMonitor) {
      Request current = inFlight.get();
      if (current != null) {
        current.future.completeExceptionally(new IllegalStateException(inFlightMessage));
      }
      // The queue holds at most its capacity, so draining it is bounded.
      var waiting = new java.util.ArrayList<Request>(queue.size());
      queue.drainTo(waiting);
      for (Request request : waiting) request.future.completeExceptionally(failure);
      assert queue.isEmpty();
    }
  }

  private static void noop() {}

  private static Duration requireTimeout(Duration timeout, String name) {
    Objects.requireNonNull(timeout, name);
    if (timeout.isNegative() || timeout.isZero() || timeout.compareTo(TIMEOUT_MAX) > 0) {
      throw new IllegalArgumentException(name + " must be positive and at most " + TIMEOUT_MAX);
    }
    return timeout;
  }

  private record Request(WriteCommand command, CompletableFuture<WriteResult> future) {
    private Request {
      assert command != null;
      assert future != null;
    }
  }

  public record Submission(boolean accepted, CompletableFuture<WriteResult> future) {
    public Submission {
      Objects.requireNonNull(future, "future");
      assert accepted || future.isDone();
    }
  }

  public record WriteResult(Optional<java.util.UUID> eventId) {
    public WriteResult {
      Objects.requireNonNull(eventId, "eventId");
    }
  }
}
