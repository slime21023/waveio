package io.waveio.execution.internal;

import io.waveio.execution.ExecutionRejectedException;
import java.util.ArrayDeque;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/** Serializes segments for one execution while sharing an executor with other executions. */
public final class SerialSegmentDispatcher {
    private final Executor executor;
    private final int capacity;
    private final ArrayDeque<Runnable> queue = new ArrayDeque<>();
    private boolean draining;
    private int outstanding;

    /** Creates a dispatcher with an explicit maximum number of outstanding segments. */
    public SerialSegmentDispatcher(Executor executor, int capacity) {
        this.executor = Objects.requireNonNull(executor, "executor");
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
    }

    /** Queues one segment, or rejects it when the per-execution capacity is exhausted. */
    public synchronized void dispatch(Runnable segment) {
        Objects.requireNonNull(segment, "segment");
        if (outstanding == capacity) {
            throw new ExecutionRejectedException("execution segment capacity exhausted");
        }
        queue.addLast(segment);
        outstanding++;
        if (!draining) {
            draining = true;
            submitNext();
        }
    }

    private void submitNext() {
        try {
            executor.execute(this::runOne);
        } catch (RejectedExecutionException exception) {
            queue.removeLast();
            outstanding--;
            draining = false;
            throw new ExecutionRejectedException("execution executor rejected a segment");
        }
    }

    private void runOne() {
        Runnable segment;
        synchronized (this) {
            segment = queue.removeFirst();
        }
        try {
            segment.run();
        } finally {
            synchronized (this) {
                outstanding--;
                if (queue.isEmpty()) {
                    draining = false;
                } else {
                    submitNext();
                }
            }
        }
    }
}
