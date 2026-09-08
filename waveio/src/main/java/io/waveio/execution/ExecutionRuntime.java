package io.waveio.execution;

import io.waveio.execution.internal.SerialSegmentDispatcher;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Owns bounded execution dispatch resources. */
public final class ExecutionRuntime implements AutoCloseable {
    private final ExecutionConfig config;
    private final Executor executor;
    private final ExecutorService ownedExecutor;
    private final ScheduledExecutorService deadlineScheduler;

    private ExecutionRuntime(ExecutionConfig config, Executor executor, ExecutorService ownedExecutor,
            ScheduledExecutorService deadlineScheduler) {
        this.config = Objects.requireNonNull(config, "config");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.ownedExecutor = ownedExecutor;
        this.deadlineScheduler = Objects.requireNonNull(deadlineScheduler, "deadlineScheduler");
    }

    /** Creates a runtime with the explicit configuration and an owned fixed-size executor. */
    public static ExecutionRuntime create(ExecutionConfig config) {
        Objects.requireNonNull(config, "config");
        ExecutorService executor = Executors.newFixedThreadPool(config.parallelism());
        return new ExecutionRuntime(config, executor, executor, Executors.newSingleThreadScheduledExecutor());
    }

    /** Starts an execution and schedules its first managed segment. */
    public ExecutionHandle start(Consumer<Execution> initialSegment) {
        Objects.requireNonNull(initialSegment, "initialSegment");
        Execution execution = new Execution(new SerialSegmentDispatcher(executor, config.queueCapacity()), failure -> { });
        ExecutionHandle handle = new ExecutionHandle(execution);
        var deadline = deadlineScheduler.schedule(execution::timeout, config.deadline().toNanos(), TimeUnit.NANOSECONDS);
        execution.setDeadlineCancellation(() -> deadline.cancel(false));
        execution.execute(() -> initialSegment.accept(execution));
        return handle;
    }

    /** Stops the runtime's owned executor. */
    @Override
    public void close() {
        if (ownedExecutor != null) {
            ownedExecutor.shutdownNow();
        }
        deadlineScheduler.shutdownNow();
    }
}
