package io.waveio.execution;

import io.waveio.execution.internal.SerialSegmentDispatcher;
import java.util.ArrayDeque;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;

/** A managed serial execution with context available only inside its segments. */
public final class Execution {
    private static final ScopedValue<Execution> CURRENT = ScopedValue.newInstance();

    private final SerialSegmentDispatcher dispatcher;
    private final AtomicReference<ExecutionState> state = new AtomicReference<>(ExecutionState.ACTIVE);
    private final CompletableFuture<ExecutionState> completion = new CompletableFuture<>();
    private final ExecutionRef ref = new ExecutionRef(this);
    private final CleanupFailureObserver cleanupFailureObserver;
    private final ArrayDeque<Runnable> cleanupActions = new ArrayDeque<>();
    private Runnable cancelDeadline = () -> { };

    Execution(SerialSegmentDispatcher dispatcher, CleanupFailureObserver cleanupFailureObserver) {
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.cleanupFailureObserver = Objects.requireNonNull(cleanupFailureObserver, "cleanupFailureObserver");
    }

    /** Returns the current execution when called inside a managed segment. */
    public static Execution current() {
        if (!CURRENT.isBound()) {
            throw new IllegalStateException("no managed execution is bound to the current thread");
        }
        return CURRENT.get();
    }

    /** Schedules a serial managed segment for this execution. */
    public void execute(Runnable segment) {
        schedule(segment);
    }

    /** Completes this execution successfully if it is still active. */
    public boolean complete() {
        return transitionTo(ExecutionState.SUCCEEDED);
    }

    /** Fails this execution if it is still active. */
    public boolean fail(Throwable failure) {
        Objects.requireNonNull(failure, "failure");
        return transitionTo(ExecutionState.FAILED);
    }

    /** Cancels this execution if it is still active. */
    public boolean cancel() {
        return transitionTo(ExecutionState.CANCELLED);
    }

    /** Registers one cleanup action, executed once in LIFO order at termination. */
    public void onCleanup(Runnable cleanup) {
        Objects.requireNonNull(cleanup, "cleanup");
        synchronized (cleanupActions) {
            if (state().isTerminal()) {
                throw new IllegalStateException("cannot register cleanup after execution termination");
            }
            cleanupActions.addFirst(cleanup);
        }
    }

    /** Returns a callback-safe reference to this execution. */
    public ExecutionRef ref() {
        return ref;
    }

    ExecutionState state() {
        return state.get();
    }

    CompletionStage<ExecutionState> completion() {
        return completion;
    }

    void schedule(Runnable segment) {
        Objects.requireNonNull(segment, "segment");
        if (state().isTerminal()) {
            return;
        }
        dispatcher.dispatch(() -> {
            if (!state().isTerminal()) {
                ScopedValue.where(CURRENT, this).run(segment);
            }
        });
    }

    void setDeadlineCancellation(Runnable cancellation) {
        Objects.requireNonNull(cancellation, "cancellation");
        boolean cancelImmediately;
        synchronized (cleanupActions) {
            cancelImmediately = state().isTerminal();
            if (!cancelImmediately) {
                cancelDeadline = cancellation;
            }
        }
        if (cancelImmediately) {
            cancellation.run();
        }
    }

    boolean timeout() {
        return transitionTo(ExecutionState.TIMED_OUT);
    }

    private boolean transitionTo(ExecutionState terminal) {
        if (!terminal.isTerminal()) {
            throw new IllegalArgumentException("target state must be terminal");
        }
        if (state.compareAndSet(ExecutionState.ACTIVE, terminal)) {
            cancelDeadline.run();
            runCleanupActions();
            completion.complete(terminal);
            return true;
        }
        return false;
    }

    private void runCleanupActions() {
        while (true) {
            Runnable action;
            synchronized (cleanupActions) {
                action = cleanupActions.pollFirst();
            }
            if (action == null) {
                return;
            }
            try {
                action.run();
            } catch (Throwable failure) {
                try {
                    cleanupFailureObserver.onCleanupFailure(failure);
                } catch (Throwable ignored) {
                    // An observer must not replace the execution's terminal result.
                }
            }
        }
    }
}
