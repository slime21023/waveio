package io.waveio.execution;

import java.util.Objects;
import java.util.concurrent.CompletionStage;

/** Observes and controls the lifetime of one started execution. */
public final class ExecutionHandle {
    private final Execution execution;

    ExecutionHandle(Execution execution) {
        this.execution = Objects.requireNonNull(execution, "execution");
    }

    /** Returns the current lifecycle state. */
    public ExecutionState state() {
        return execution.state();
    }

    /** Returns a callback-safe reference for this execution. */
    public ExecutionRef ref() {
        return execution.ref();
    }

    /** Returns a stage completed once the execution becomes terminal. */
    public CompletionStage<ExecutionState> completion() {
        return execution.completion();
    }

    /** Requests external cancellation; the first terminal signal wins. */
    public boolean cancel() {
        return execution.cancel();
    }
}
