package toktrak.store;

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
  private static final long POLL_MILLIS = 100;

  private final EventLog log;
  private final Projection projection;
  private final HealthState health;
  private final ClockSource clock;
  private final boolean failWrites;
  private final ArrayBlockingQueue<Request> queue;
  private final Duration drainTimeout;
  private final Duration abortTimeout;
  private final Runnable afterClaim;
  private final Runnable afterFsync;
  private final AtomicBoolean closed = new AtomicBoolean();
  private final AtomicBoolean abort = new AtomicBoolean();
  private final AtomicReference<Request> inFlight = new AtomicReference<>();
  private final Object stateMonitor = new Object();
  private final Object commitMonitor = new Object();
  private final Object pauseMonitor = new Object();
  private volatile boolean pauseRequested;
  private volatile boolean paused;
  private final Thread thread;

  private Writer(
      EventLog log,
      Projection projection,
      HealthState health,
      ClockSource clock,
      boolean failWrites,
      int capacity,
      Duration drainTimeout,
      Duration abortTimeout,
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
    assert afterClaim != null;
    assert afterFsync != null;
    this.afterClaim = afterClaim;
    this.afterFsync = afterFsync;
    this.failWrites = failWrites;
    this.queue = new ArrayBlockingQueue<>(capacity);
    this.thread = Thread.ofPlatform().daemon(true).name("toktrak-writer").start(this::run);
    assert thread.isDaemon();
  }

  public static Writer start(
      EventLog log,
      Projection projection,
      HealthState health,
      ClockSource clock,
      boolean failWrites) {
    return new Writer(
        log,
        projection,
        health,
        clock,
        failWrites,
        QUEUE_CAPACITY,
        DRAIN_TIMEOUT,
        ABORT_TIMEOUT,
        Writer::noop,
        Writer::noop);
  }

  public static Writer startForTest(
      EventLog log,
      Projection projection,
      HealthState health,
      ClockSource clock,
      boolean failWrites,
      int capacity) {
    return new Writer(
        log,
        projection,
        health,
        clock,
        failWrites,
        capacity,
        DRAIN_TIMEOUT,
        ABORT_TIMEOUT,
        Writer::noop,
        Writer::noop);
  }

  public static Writer startForTest(
      EventLog log,
      Projection projection,
      HealthState health,
      ClockSource clock,
      boolean failWrites,
      int capacity,
      Duration drainTimeout,
      Duration abortTimeout) {
    return new Writer(
        log,
        projection,
        health,
        clock,
        failWrites,
        capacity,
        drainTimeout,
        abortTimeout,
        Writer::noop,
        Writer::noop);
  }

  public static Writer startForTest(
      EventLog log,
      Projection projection,
      HealthState health,
      ClockSource clock,
      boolean failWrites,
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
        failWrites,
        capacity,
        drainTimeout,
        abortTimeout,
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
    } catch (java.util.concurrent.ExecutionException
        | java.util.concurrent.TimeoutException exception) {
      Throwable cause =
          exception instanceof java.util.concurrent.ExecutionException
              ? exception.getCause()
              : exception;
      if (cause instanceof RuntimeException runtimeException) throw runtimeException;
      throw new IllegalStateException("write failed", cause);
    }
  }

  public Submission trySubmit(WriteCommand command) {
    Objects.requireNonNull(command, "command");
    var future = new CompletableFuture<WriteResult>();
    synchronized (stateMonitor) {
      if (closed.get()) {
        assert future.completeExceptionally(new IllegalStateException("writer is closed"));
        return new Submission(false, future);
      }
      if (!queue.offer(new Request(command, future))) {
        assert future.completeExceptionally(new IllegalStateException("writer queue is full"));
        return new Submission(false, future);
      }
      stateMonitor.notifyAll();
    }
    return new Submission(true, future);
  }

  public Projection projection() {
    assert projection != null;
    return projection;
  }

  public void pauseForTest() throws InterruptedException {
    synchronized (pauseMonitor) {
      pauseRequested = true;
      pauseMonitor.notifyAll();
      long deadline = deadlineAfter(Duration.ofSeconds(2));
      while (!paused) {
        long remainingNanos = deadline - System.nanoTime();
        if (remainingNanos <= 0) throw new IllegalStateException("writer did not pause");
        TimeUnit.NANOSECONDS.timedWait(pauseMonitor, remainingNanos);
      }
    }
    assert paused;
  }

  private void run() {
    try {
      while (true) {
        Request request = claimNext();
        if (request == null) return;
        afterClaim.run();
        processClaimed(request);
      }
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      if (!abort.get()) health.degrade("writes_failed");
    } finally {
      failOutstanding("writer stopped");
    }
  }

  private Request claimNext() throws InterruptedException {
    while (true) {
      awaitResume();
      synchronized (stateMonitor) {
        if (queue.isEmpty()) {
          if (closed.get()) return null;
          stateMonitor.wait(POLL_MILLIS);
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

  private void awaitResume() throws InterruptedException {
    if (!pauseRequested) return;
    while (true) {
      synchronized (pauseMonitor) {
        paused = true;
        pauseMonitor.notifyAll();
        if (!pauseRequested || closed.get()) {
          paused = false;
          return;
        }
        pauseMonitor.wait();
      }
    }
  }

  private void processClaimed(Request request) {
    assert request != null;
    assert inFlight.compareAndSet(request, request);
    try {
      process(request);
    } finally {
      boolean cleared = inFlight.compareAndSet(request, null);
      assert cleared;
    }
  }

  private void process(Request request) {
    assert request != null;
    try {
      if (failWrites) throw new IllegalStateException("writes disabled by --fail-writes");
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
    } catch (RuntimeException exception) {
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
      failOutstanding("writer is closed");
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

  private void failOutstanding(String message) {
    assert message != null && !message.isBlank();
    var failure = new IllegalStateException(message);
    synchronized (stateMonitor) {
      Request current = inFlight.get();
      if (current != null) current.future.completeExceptionally(failure);
      int drained = 0;
      Request request;
      while (drained < QUEUE_CAPACITY && (request = queue.poll()) != null) {
        request.future.completeExceptionally(failure);
        drained = Math.addExact(drained, 1);
      }
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

  private static long deadlineAfter(Duration timeout) {
    assert timeout != null && !timeout.isNegative() && !timeout.isZero();
    long now = System.nanoTime();
    try {
      return Math.addExact(now, timeout.toNanos());
    } catch (ArithmeticException exception) {
      return Long.MAX_VALUE;
    }
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
