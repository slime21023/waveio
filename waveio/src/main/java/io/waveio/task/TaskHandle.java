package io.waveio.task;

import io.waveio.execution.ExecutionHandle;
import io.waveio.execution.ExecutionState;
import java.util.Objects;
import java.util.concurrent.CompletionStage;

/** Observes a started task and allows its owning execution to be cancelled. */
public final class TaskHandle<T> {
    private final CompletionStage<T> completion;
    private final ExecutionHandle execution;

    TaskHandle(CompletionStage<T> completion, ExecutionHandle execution) {
        this.completion = Objects.requireNonNull(completion, "completion");
        this.execution = Objects.requireNonNull(execution, "execution");
    }

    /** Returns the task result stage. */
    public CompletionStage<T> completion() {
        return completion;
    }

    /** Returns the current state of the task's owning execution. */
    public ExecutionState state() {
        return execution.state();
    }

    /** Requests cancellation of the task's owning execution. */
    public boolean cancel() {
        return execution.cancel();
    }
}
