package io.waveio.execution;

import io.waveio.execution.internal.SerialSegmentDispatcher;
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

    Execution(SerialSegmentDispatcher dispatcher) {
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
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

    boolean transitionTo(ExecutionState terminal) {
        if (!terminal.isTerminal()) {
            throw new IllegalArgumentException("target state must be terminal");
        }
        if (state.compareAndSet(ExecutionState.ACTIVE, terminal)) {
            completion.complete(terminal);
            return true;
        }
        return false;
    }
}
