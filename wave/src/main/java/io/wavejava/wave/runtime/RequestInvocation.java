package io.wavejava.wave.runtime;

import io.wavejava.wave.api.http.CancellationToken;
import io.wavejava.wave.api.http.Deadline;
import io.wavejava.wave.api.http.Request;
import io.wavejava.wave.api.http.RequestContext;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * One application invocation owned by an {@link InvocationRuntime}.
 *
 * <p>A terminal state is chosen exactly once. Cancellation wins only if it races before normal
 * completion or failure; after it wins, the shared {@link CancellationToken} is signalled, the
 * deadline task is cancelled, and the associated virtual thread is interrupted. Interrupting a
 * thread is a cooperative wake-up signal, not a substitute for downstream I/O timeouts.</p>
 *
 * @param <T> result produced by the application invocation
 */
public final class RequestInvocation<T> {
    /** Lifecycle state of one invocation. */
    public enum State {
        QUEUED,
        RUNNING,
        SUCCEEDED,
        FAILED,
        CANCELLED
    }

    private static final String DEADLINE_EXCEEDED = "deadline exceeded";

    private final InvocationRuntime owner;
    private final Request request;
    private final RequestContext context;
    private final InvocationRuntime.RequestTask<T> task;
    private final ScheduledExecutorService scheduler;
    private final Clock clock;
    private final CompletableFuture<T> completion = new CompletableFuture<>();
    private final AtomicReference<State> state = new AtomicReference<>(State.QUEUED);
    private final AtomicReference<Future<?>> executionFuture = new AtomicReference<>();
    private final AtomicReference<ScheduledFuture<?>> deadlineFuture = new AtomicReference<>();

    private volatile Thread executionThread;

    RequestInvocation(
            InvocationRuntime owner,
            Request request,
            RequestContext context,
            InvocationRuntime.RequestTask<T> task,
            ScheduledExecutorService scheduler,
            Clock clock) {
        this.owner = Objects.requireNonNull(owner, "owner");
        this.request = Objects.requireNonNull(request, "request");
        this.context = Objects.requireNonNull(context, "context");
        this.task = Objects.requireNonNull(task, "task");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Returns the invocation-owned request, including this invocation's context. */
    public Request request() {
        return request;
    }

    /** Returns the context shared by the transport, runtime, and application handler. */
    public RequestContext context() {
        return context;
    }

    /** Returns the result of this invocation's application task. */
    public CompletionStage<T> completion() {
        return completion;
    }

    /** Returns this invocation's current lifecycle state. */
    public State state() {
        return state.get();
    }

    /** Returns whether this invocation reached a terminal state. */
    public boolean isDone() {
        return isTerminal(state());
    }

    /** Returns whether cancellation won this invocation's terminal-state race. */
    public boolean isCancelled() {
        return state() == State.CANCELLED;
    }

    /**
     * Requests cancellation with a diagnostic reason.
     *
     * @return {@code true} only when this call chose the terminal cancellation state
     */
    public boolean cancel(String reason) {
        validateCancellationReason(reason);
        while (true) {
            var current = state.get();
            if (isTerminal(current)) {
                return false;
            }
            if (state.compareAndSet(current, State.CANCELLED)) {
                context.cancellationToken().cancel(reason);
                cancelDeadlineTask();
                interruptExecution();
                completion.completeExceptionally(new CancellationException(reason));
                owner.onTerminal(this);
                return true;
            }
        }
    }

    void start(ExecutorService executor) {
        Objects.requireNonNull(executor, "executor");
        if (context.cancellationToken().isCancelled()) {
            cancel(context.cancellationToken().reason().orElse("request already cancelled"));
            return;
        }

        try {
            context.deadline().ifPresent(this::scheduleDeadline);
            var submitted = executor.submit(this::execute);
            executionFuture.set(submitted);
            if (isCancelled()) {
                submitted.cancel(true);
            }
        } catch (RuntimeException failure) {
            failBeforeExecution(failure);
            throw failure;
        }
    }

    boolean belongsTo(InvocationRuntime runtime) {
        return owner == runtime;
    }

    private void execute() {
        executionThread = Thread.currentThread();
        if (!state.compareAndSet(State.QUEUED, State.RUNNING)) {
            executionThread = null;
            return;
        }

        try {
            succeed(RequestContextScope.call(context, () -> task.execute(request)));
        } catch (Throwable failure) {
            fail(failure);
        } finally {
            executionThread = null;
        }
    }

    private void scheduleDeadline(Deadline deadline) {
        var delay = deadline.remaining(clock.instant());
        var scheduled = scheduler.schedule(
                () -> expireDeadline(deadline),
                toNanosSaturated(delay),
                TimeUnit.NANOSECONDS);
        var prior = deadlineFuture.getAndSet(scheduled);
        if (prior != null) {
            prior.cancel(false);
        }
        if (isDone()) {
            scheduled.cancel(false);
        }
    }

    private void expireDeadline(Deadline deadline) {
        if (isDone()) {
            return;
        }
        if (deadline.isExpired(clock.instant())) {
            cancel(DEADLINE_EXCEEDED);
        } else {
            // A saturated delay or a non-monotonic clock may wake us before the actual deadline.
            scheduleDeadline(deadline);
        }
    }

    private void succeed(T result) {
        if (state.compareAndSet(State.RUNNING, State.SUCCEEDED)) {
            cancelDeadlineTask();
            completion.complete(result);
            owner.onTerminal(this);
        }
    }

    private void fail(Throwable failure) {
        if (state.compareAndSet(State.RUNNING, State.FAILED)) {
            cancelDeadlineTask();
            completion.completeExceptionally(failure);
            owner.onTerminal(this);
        }
    }

    private void failBeforeExecution(Throwable failure) {
        if (state.compareAndSet(State.QUEUED, State.FAILED)) {
            cancelDeadlineTask();
            completion.completeExceptionally(failure);
            owner.onTerminal(this);
        }
    }

    private void interruptExecution() {
        var submitted = executionFuture.get();
        if (submitted != null) {
            submitted.cancel(true);
        }
        var thread = executionThread;
        if (thread != null) {
            thread.interrupt();
        }
    }

    private void cancelDeadlineTask() {
        var scheduled = deadlineFuture.getAndSet(null);
        if (scheduled != null) {
            scheduled.cancel(false);
        }
    }

    private static boolean isTerminal(State state) {
        return state == State.SUCCEEDED || state == State.FAILED || state == State.CANCELLED;
    }

    private static long toNanosSaturated(Duration duration) {
        if (duration.isZero() || duration.isNegative()) {
            return 0;
        }
        try {
            return duration.toNanos();
        } catch (ArithmeticException ignored) {
            return Long.MAX_VALUE;
        }
    }

    private static void validateCancellationReason(String reason) {
        Objects.requireNonNull(reason, "reason");
        if (reason.isBlank()) {
            throw new IllegalArgumentException("Cancellation reason must not be blank");
        }
    }
}
