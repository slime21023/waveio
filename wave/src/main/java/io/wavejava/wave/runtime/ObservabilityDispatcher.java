package io.wavejava.wave.runtime;

import io.wavejava.wave.api.observability.AccessLogEvent;
import io.wavejava.wave.api.observability.Observability;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Internal bounded, asynchronous delivery boundary for user-provided observability bridges.
 *
 * <p>Transport EventLoops only construct an immutable {@link AccessLogEvent} and offer it to the
 * finite queue. All user callbacks run on this owned daemon worker. A stalled or failing bridge
 * therefore cannot execute on, or indefinitely retain work from, a socket EventLoop.</p>
 */
public final class ObservabilityDispatcher {
    private final Observability observability;
    private final ArrayBlockingQueue<AccessLogEvent> events;
    private final AtomicBoolean accepting = new AtomicBoolean(true);
    private final AtomicLong droppedEvents = new AtomicLong();
    private final Thread worker;

    /** Creates and starts a worker only when an observability bridge is configured. */
    public ObservabilityDispatcher(Observability observability) {
        this.observability = Objects.requireNonNull(observability, "observability");
        if (!observability.isEnabled()) {
            events = null;
            worker = null;
            return;
        }
        events = new ArrayBlockingQueue<>(observability.limits().maximumQueuedEvents());
        worker = Thread.ofPlatform().daemon().name("wave-observability-", 0).start(this::drain);
    }

    /** Offers an immutable event without invoking application-owned bridge code. */
    public void record(AccessLogEvent event) {
        Objects.requireNonNull(event, "event");
        if (events == null) {
            return;
        }
        if (!accepting.get() || !events.offer(event)) {
            droppedEvents.incrementAndGet();
        }
    }

    /** Returns queue-overflow or post-close drops for deterministic tests and diagnostics. */
    public long droppedEvents() {
        return droppedEvents.get();
    }

    /**
     * Stops accepting events and waits only within {@code timeout} for queued callbacks to drain.
     *
     * <p>A bridge that ignores interruption cannot make server shutdown unbounded: this method
     * returns once the deadline is spent and the daemon worker is interrupted. The queue itself
     * remains finite throughout that exceptional provider behavior.</p>
     */
    public void close(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must not be negative");
        }
        if (worker == null || !accepting.compareAndSet(true, false)) {
            return;
        }
        var interrupted = false;
        var timeoutNanos = toNanosSaturated(timeout);
        var startedAt = System.nanoTime();
        while (worker.isAlive()) {
            var elapsed = System.nanoTime() - startedAt;
            var remaining = elapsed <= 0 ? timeoutNanos : elapsed >= timeoutNanos ? 0 : timeoutNanos - elapsed;
            if (remaining == 0) {
                worker.interrupt();
                break;
            }
            try {
                TimeUnit.NANOSECONDS.timedJoin(worker, remaining);
            } catch (InterruptedException ignored) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private void drain() {
        while (accepting.get() || !events.isEmpty()) {
            final AccessLogEvent event;
            try {
                event = events.poll(100, TimeUnit.MILLISECONDS);
            } catch (InterruptedException ignored) {
                // Close either wants a bounded final drain or has exhausted its own deadline.
                // Continue so a cooperative bridge can finish queued events, then observe the
                // accepting/queue condition on the next loop turn.
                continue;
            }
            if (event != null) {
                deliver(event);
            }
        }
    }

    private void deliver(AccessLogEvent event) {
        for (var accessLog : observability.accessLogs()) {
            invoke(() -> accessLog.accept(event));
        }
        for (var metrics : observability.metrics()) {
            invoke(() -> metrics.record(event));
        }
        for (var tracing : observability.tracing()) {
            invoke(() -> tracing.record(event));
        }
    }

    private static void invoke(Runnable callback) {
        try {
            callback.run();
        } catch (Throwable ignored) {
            // A provider must never terminate this worker or affect transport behavior.
        }
    }

    private static long toNanosSaturated(Duration duration) {
        try {
            return duration.toNanos();
        } catch (ArithmeticException ignored) {
            return Long.MAX_VALUE;
        }
    }
}
