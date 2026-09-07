package io.waveio.task;

import io.waveio.execution.Execution;
import java.util.Objects;
import java.util.function.Supplier;

/** A lazily started description of one asynchronous result. */
public final class Task<T> {
    private final Node<T> node;

    private Task(Node<T> node) {
        this.node = Objects.requireNonNull(node, "node");
    }

    /** Creates a task that produces a value when started. */
    public static <T> Task<T> success(T value) {
        return new Task<>((execution, callback) -> callback.succeed(value));
    }

    /** Defers creation of a task until each independent start. */
    public static <T> Task<T> defer(Supplier<? extends Task<T>> supplier) {
        Objects.requireNonNull(supplier, "supplier");
        return new Task<>((execution, callback) -> {
            Task<T> task = Objects.requireNonNull(supplier.get(), "deferred task");
            task.start(execution, callback);
        });
    }

    void start(Execution execution, Callback<? super T> callback) {
        node.start(execution, callback);
    }

    @FunctionalInterface
    interface Node<T> {
        void start(Execution execution, Callback<? super T> callback);
    }

    interface Callback<T> {
        void succeed(T value);

        void fail(Throwable failure);
    }
}
