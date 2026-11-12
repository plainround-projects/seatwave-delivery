package io.plainround.seatwave.delivery.stack;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

/**
 * An open-loop load: requests leave at a fixed rate whatever the database is doing, as fans' do,
 * each on its own virtual thread, with a bound on how many may be in flight. A slow database
 * therefore shows as long requests, not as fewer requests sent. Every request is recorded with its
 * start, its duration and its outcome. Start once, stop once; the recorded samples are safe to read
 * after {@link #stop()} returns.
 */
public final class OpenLoad implements AutoCloseable {

  /** One request: does its work and returns empty on success or the reason it failed. */
  @FunctionalInterface
  public interface Request {
    /** Runs the request; exceptions other than the database's are programming errors. */
    Optional<String> run() throws java.sql.SQLException;
  }

  /**
   * What one request did.
   *
   * @param startNanos when it was due, on {@link System#nanoTime()}
   * @param durationNanos from due time to its end, so time spent waiting to start counts too
   * @param failure empty on success, or the reason
   */
  public record Sample(long startNanos, long durationNanos, Optional<String> failure) {}

  private static final long NANOS_PER_SECOND = TimeUnit.SECONDS.toNanos(1);
  private static final Duration DRAIN_LIMIT = Duration.ofSeconds(30);

  private final int requestsPerSecond;
  private final Semaphore room;
  private final Request request;
  private final ConcurrentLinkedQueue<Sample> samples = new ConcurrentLinkedQueue<>();
  private final AtomicBoolean isRunning = new AtomicBoolean();
  private final ExecutorService requests = Executors.newVirtualThreadPerTaskExecutor();
  private Thread sender;
  private long sent;

  /** A load of {@code requestsPerSecond}, at most {@code maxInFlight} at once. */
  public OpenLoad(int requestsPerSecond, int maxInFlight, Request request) {
    if (requestsPerSecond < 1 || maxInFlight < 1) {
      throw new IllegalArgumentException("rate and room must be at least 1");
    }
    this.requestsPerSecond = requestsPerSecond;
    this.room = new Semaphore(maxInFlight);
    this.request = Objects.requireNonNull(request, "request");
  }

  /** Starts sending; returns at once. */
  public void start() {
    if (!isRunning.compareAndSet(false, true)) {
      throw new IllegalStateException("a load starts once");
    }
    sender = Thread.ofPlatform().name("open-load").start(this::send);
  }

  /** Stops sending, waits for the requests in flight, and returns every sample. */
  public List<Sample> stop() throws InterruptedException {
    isRunning.set(false);
    sender.join();
    requests.shutdown();
    if (!requests.awaitTermination(DRAIN_LIMIT.toSeconds(), TimeUnit.SECONDS)) {
      throw new IllegalStateException("requests still in flight after " + DRAIN_LIMIT);
    }
    return new ArrayList<>(samples);
  }

  /** Requests sent so far, counted when due (including those refused for lack of room). */
  public synchronized long sent() {
    return sent;
  }

  /**
   * Stops the load if a caller failed before {@link #stop()}: no new request is sent, and the
   * requests in flight are interrupted.
   */
  @Override
  public void close() {
    isRunning.set(false);
    requests.shutdownNow();
    if (sender != null) {
      try {
        sender.join();
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
      }
    }
  }

  private void send() {
    long origin = System.nanoTime();
    long index = 0;
    while (isRunning.get()) {
      long due = origin + index * NANOS_PER_SECOND / requestsPerSecond;
      long wait = due - System.nanoTime();
      if (wait > 0) {
        LockSupport.parkNanos(wait);
        continue;
      }
      index++;
      synchronized (this) {
        sent++;
      }
      if (!room.tryAcquire()) {
        samples.add(new Sample(due, 0, Optional.of("no room: too many requests in flight")));
        continue;
      }
      requests.execute(() -> runOne(due));
    }
  }

  private void runOne(long due) {
    try {
      Optional<String> failure;
      try {
        failure = request.run();
      } catch (java.sql.SQLException refused) {
        failure = Optional.of(reason(refused));
      }
      samples.add(new Sample(due, System.nanoTime() - due, failure));
    } finally {
      room.release();
    }
  }

  /** The SQL state and the first line of the database's message: what a log line would show. */
  static String reason(java.sql.SQLException refused) {
    String message = Objects.requireNonNullElse(refused.getMessage(), "no message");
    int newline = message.indexOf('\n');
    return refused.getSQLState() + " " + (newline < 0 ? message : message.substring(0, newline));
  }
}
