package io.waveio.execution;

import java.util.Objects;

/** Thread-safe reference used to return an external callback to its execution dispatcher. */
public final class ExecutionRef {
    private final Execution execution;

    ExecutionRef(Execution execution) {
        this.execution = Objects.requireNonNull(execution, "execution");
    }

    /** Schedules a callback as a managed serial segment. */
    public void execute(Runnable callback) {
        execution.schedule(callback);
    }
}
