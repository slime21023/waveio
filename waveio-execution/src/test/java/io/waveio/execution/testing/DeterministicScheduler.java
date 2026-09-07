package io.waveio.execution.testing;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.PriorityQueue;

/** A single-threaded scheduler controlled entirely by a test. */
public final class DeterministicScheduler {
    private final ManualClock clock;
    private final PriorityQueue<ScheduledCallback> callbacks = new PriorityQueue<>();
    private long sequence;

    /** Creates a scheduler backed by the supplied manual clock. */
    public DeterministicScheduler(ManualClock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Schedules a callback for the current test instant. */
    public void execute(Runnable callback) {
        schedule(Duration.ZERO, callback);
    }

    /** Schedules a callback after a non-negative delay. */
    public void schedule(Duration delay, Runnable callback) {
        Objects.requireNonNull(delay, "delay");
        Objects.requireNonNull(callback, "callback");
        if (delay.isNegative()) {
            throw new IllegalArgumentException("delay must not be negative");
        }
        callbacks.add(new ScheduledCallback(clock.now().plus(delay), sequence++, callback));
    }

    /** Runs every callback due at the current test instant in FIFO submission order. */
    public int runDue() {
        int ran = 0;
        while (!callbacks.isEmpty() && !callbacks.peek().dueAt().isAfter(clock.now())) {
            callbacks.remove().callback().run();
            ran++;
        }
        return ran;
    }

    /** Advances time and then runs all due callbacks. */
    public int advanceAndRun(Duration duration) {
        clock.advance(duration);
        return runDue();
    }

    /** Returns the number of callbacks not yet run. */
    public int pendingCount() {
        return callbacks.size();
    }

    private record ScheduledCallback(Instant dueAt, long sequence, Runnable callback)
            implements Comparable<ScheduledCallback> {
        @Override
        public int compareTo(ScheduledCallback other) {
            int timeOrder = dueAt.compareTo(other.dueAt);
            return timeOrder != 0 ? timeOrder : Long.compare(sequence, other.sequence);
        }
    }
}
