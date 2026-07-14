package toktrak.store;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import toktrak.ClockSource;
import toktrak.health.HealthState;
import toktrak.projection.Projection;

public final class Writer implements AutoCloseable {
  private final EventLog log;
  private final Projection projection;
  private final HealthState health;
  private final ClockSource clock;
  private final boolean failWrites;
  private final ArrayBlockingQueue<Request> queue;
  private final AtomicBoolean closed = new AtomicBoolean();
  private final Object pauseMonitor = new Object();
  private volatile boolean pauseRequested;
  private volatile boolean paused;
  private final Thread thread;

  private Writer(EventLog log, Projection projection, HealthState health, ClockSource clock, boolean failWrites, int capacity) {
    this.log = log;
    this.projection = projection;
    this.health = health;
    this.clock = clock;
    this.failWrites = failWrites;
    this.queue = new ArrayBlockingQueue<>(capacity);
    this.thread = Thread.ofPlatform().name("toktrak-writer").start(this::run);
  }

  public static Writer start(EventLog log, Projection projection, HealthState health, ClockSource clock, boolean failWrites) {
    return new Writer(log, projection, health, clock, failWrites, 1024);
  }

  public static Writer startForTest(EventLog log, Projection projection, HealthState health, ClockSource clock, boolean failWrites, int capacity) {
    return new Writer(log, projection, health, clock, failWrites, capacity);
  }

  public CompletableFuture<WriteResult> submit(WriteCommand command) {
    Submission submission = trySubmit(command);
    if (!submission.accepted()) return submission.future();
    return submission.future();
  }

  public Submission trySubmit(WriteCommand command) {
    var future = new CompletableFuture<WriteResult>();
    if (closed.get() || !queue.offer(new Request(command, future))) {
      future.completeExceptionally(new IllegalStateException("writer queue is full"));
      return new Submission(false, future);
    }
    return new Submission(true, future);
  }

  public Projection projection() {
    return projection;
  }

  public void pauseForTest() throws InterruptedException {
    pauseRequested = true;
    synchronized (pauseMonitor) {
      pauseMonitor.notifyAll();
    }
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
    while (!paused && System.nanoTime() < deadline) Thread.yield();
    if (!paused) throw new IllegalStateException("writer did not pause");
  }

  private void run() {
    try {
      while (!closed.get() || !queue.isEmpty()) {
        awaitResume();
        Request request = queue.poll(100, TimeUnit.MILLISECONDS);
        if (request != null) process(request);
      }
    } catch (InterruptedException ignored) {
      Thread.currentThread().interrupt();
    }
  }

  private void awaitResume() throws InterruptedException {
    if (!pauseRequested) return;
    synchronized (pauseMonitor) {
      paused = true;
      pauseMonitor.notifyAll();
      while (pauseRequested && !closed.get()) pauseMonitor.wait();
      paused = false;
    }
  }

  private void process(Request request) {
    try {
      if (failWrites) throw new IllegalStateException("writes disabled by --fail-writes");
      EventEnvelope event = request.command.event(Instant.from(clock.instant()), projection);
      log.appendAndFsync(event);
      projection.apply(event);
      request.future.complete(new WriteResult(Optional.of(event.id())));
    } catch (RuntimeException ex) {
      health.degrade("writes_failed");
      request.future.completeExceptionally(ex);
    }
  }

  @Override
  public void close() {
    if (!closed.compareAndSet(false, true)) return;
    synchronized (pauseMonitor) {
      pauseRequested = false;
      pauseMonitor.notifyAll();
    }
    try {
      thread.join(10000);
    } catch (InterruptedException ex) {
      thread.interrupt();
      Thread.currentThread().interrupt();
    }
    if (thread.isAlive()) thread.interrupt();
    Request request;
    while ((request = queue.poll()) != null) request.future.completeExceptionally(new IllegalStateException("writer is closed"));
  }

  private record Request(WriteCommand command, CompletableFuture<WriteResult> future) {}
  public record Submission(boolean accepted, CompletableFuture<WriteResult> future) {}
  public record WriteResult(Optional<java.util.UUID> eventId) {}
}
