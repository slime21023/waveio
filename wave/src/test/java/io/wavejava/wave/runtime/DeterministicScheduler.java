package io.wavejava.wave.runtime;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Callable;
import java.util.concurrent.Delayed;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.RunnableScheduledFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * A no-thread test scheduler whose tasks run only when the owning test advances its clock.
 *
 * <p>This deliberately implements only one-shot scheduling because that is the contract used by
 * {@link InvocationRuntime}. Keeping deadline execution in the test thread makes timing and
 * cancellation assertions repeatable.</p>
 */
final class DeterministicScheduler extends AbstractExecutorService implements ScheduledExecutorService {
    private final Object lock = new Object();
    private final Clock clock;
    private final PriorityQueue<ScheduledTask<?>> scheduledTasks = new PriorityQueue<>();
    private boolean shutdown;
    private long nextSequence;

    DeterministicScheduler(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
        Objects.requireNonNull(command, "command");
        return enqueue(new ScheduledTask<>(command, null, dueAt(delay, unit), nextSequence(), clock));
    }

    @Override
    public <T> ScheduledFuture<T> schedule(Callable<T> callable, long delay, TimeUnit unit) {
        Objects.requireNonNull(callable, "callable");
        return enqueue(new ScheduledTask<>(callable, dueAt(delay, unit), nextSequence(), clock));
    }

    @Override
    public ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initialDelay, long period, TimeUnit unit) {
        throw new UnsupportedOperationException("periodic scheduling is not used by InvocationRuntime tests");
    }

    @Override
    public ScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initialDelay, long delay, TimeUnit unit) {
        throw new UnsupportedOperationException("periodic scheduling is not used by InvocationRuntime tests");
    }

    @Override
    public void execute(Runnable command) {
        Objects.requireNonNull(command, "command");
        synchronized (lock) {
            rejectIfShutdown();
        }
        command.run();
    }

    @Override
    public void shutdown() {
        cancelQueuedTasks();
    }

    @Override
    public List<Runnable> shutdownNow() {
        return cancelQueuedTasks();
    }

    @Override
    public boolean isShutdown() {
        synchronized (lock) {
            return shutdown;
        }
    }

    @Override
    public boolean isTerminated() {
        synchronized (lock) {
            discardCancelledTasks();
            return shutdown && scheduledTasks.isEmpty();
        }
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) {
        Objects.requireNonNull(unit, "unit");
        return isTerminated();
    }

    /** Runs every non-cancelled task whose due time is at or before the test clock. */
    void runDueTasks() {
        while (true) {
            var task = pollDueTask();
            if (task == null) {
                return;
            }
            task.run();
        }
    }

    /** Returns the number of non-cancelled tasks awaiting explicit time advancement. */
    int scheduledTaskCount() {
        synchronized (lock) {
            discardCancelledTasks();
            return scheduledTasks.size();
        }
    }

    private <T> ScheduledFuture<T> enqueue(ScheduledTask<T> task) {
        synchronized (lock) {
            rejectIfShutdown();
            scheduledTasks.add(task);
            return task;
        }
    }

    private long nextSequence() {
        synchronized (lock) {
            return nextSequence++;
        }
    }

    private Instant dueAt(long delay, TimeUnit unit) {
        Objects.requireNonNull(unit, "unit");
        if (delay <= 0) {
            return clock.instant();
        }
        return clock.instant().plusNanos(unit.toNanos(delay));
    }

    private ScheduledTask<?> pollDueTask() {
        synchronized (lock) {
            discardCancelledTasks();
            var next = scheduledTasks.peek();
            if (next == null || next.dueAt().isAfter(clock.instant())) {
                return null;
            }
            return scheduledTasks.poll();
        }
    }

    private List<Runnable> cancelQueuedTasks() {
        synchronized (lock) {
            if (shutdown) {
                return List.of();
            }
            shutdown = true;
            var queued = new ArrayList<Runnable>(scheduledTasks);
            for (var task : scheduledTasks) {
                task.cancel(false);
            }
            scheduledTasks.clear();
            return List.copyOf(queued);
        }
    }

    private void discardCancelledTasks() {
        scheduledTasks.removeIf(task -> task.isCancelled());
    }

    private void rejectIfShutdown() {
        if (shutdown) {
            throw new RejectedExecutionException("deterministic scheduler is shut down");
        }
    }

    private static final class ScheduledTask<T> extends FutureTask<T> implements RunnableScheduledFuture<T> {
        private final Instant dueAt;
        private final long sequence;
        private final Clock clock;

        ScheduledTask(Runnable command, T result, Instant dueAt, long sequence, Clock clock) {
            super(command, result);
            this.dueAt = Objects.requireNonNull(dueAt, "dueAt");
            this.sequence = sequence;
            this.clock = Objects.requireNonNull(clock, "clock");
        }

        ScheduledTask(Callable<T> callable, Instant dueAt, long sequence, Clock clock) {
            super(callable);
            this.dueAt = Objects.requireNonNull(dueAt, "dueAt");
            this.sequence = sequence;
            this.clock = Objects.requireNonNull(clock, "clock");
        }

        @Override
        public boolean isPeriodic() {
            return false;
        }

        @Override
        public long getDelay(TimeUnit unit) {
            Objects.requireNonNull(unit, "unit");
            var remaining = Duration.between(clock.instant(), dueAt);
            long nanos;
            try {
                nanos = remaining.toNanos();
            } catch (ArithmeticException ignored) {
                nanos = remaining.isNegative() ? Long.MIN_VALUE : Long.MAX_VALUE;
            }
            return unit.convert(nanos, TimeUnit.NANOSECONDS);
        }

        @Override
        public int compareTo(Delayed other) {
            if (other == this) {
                return 0;
            }
            if (other instanceof ScheduledTask<?> scheduledTask) {
                var byDeadline = dueAt.compareTo(scheduledTask.dueAt);
                return byDeadline != 0 ? byDeadline : Long.compare(sequence, scheduledTask.sequence);
            }
            return Long.compare(getDelay(TimeUnit.NANOSECONDS), other.getDelay(TimeUnit.NANOSECONDS));
        }

        Instant dueAt() {
            return dueAt;
        }
    }
}
