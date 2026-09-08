package io.waveio.task;

import io.waveio.execution.Execution;
import io.waveio.execution.ExecutionRejectedException;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Callable;

/** Owns bounded virtual-thread work that may block without occupying an execution segment. */
public final class BlockingRuntime implements AutoCloseable {
    private final ThreadPoolExecutor executor;

    /** Creates an owned virtual-thread runtime with explicit slot and queue limits. */
    public BlockingRuntime(BlockingConfig config) {
        Objects.requireNonNull(config, "config");
        BlockingQueue<Runnable> queue = config.queueCapacity() == 0
                ? new SynchronousQueue<>() : new ArrayBlockingQueue<>(config.queueCapacity());
        executor = new ThreadPoolExecutor(config.concurrency(), config.concurrency(), 0L, TimeUnit.MILLISECONDS,
                queue, Thread.ofVirtual().factory(), new ThreadPoolExecutor.AbortPolicy());
    }

    <T> void submit(Execution execution, Callable<? extends T> work, Task.Callback<? super T> callback) {
        Objects.requireNonNull(execution, "execution");
        Objects.requireNonNull(work, "work");
        Objects.requireNonNull(callback, "callback");
        try {
            Future<?> future = executor.submit(() -> {
                try {
                    T value = work.call();
                    execution.ref().execute(() -> callback.succeed(value));
                } catch (Throwable failure) {
                    execution.ref().execute(() -> callback.fail(failure));
                }
            });
            execution.onCleanup(() -> future.cancel(true));
        } catch (RejectedExecutionException exception) {
            callback.fail(new ExecutionRejectedException("blocking runtime capacity exhausted"));
        }
    }

    /** Interrupts queued and owned running work. */
    @Override
    public void close() {
        executor.shutdownNow();
    }
}
