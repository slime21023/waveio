package io.waveio.task;

import io.waveio.execution.Execution;
import io.waveio.execution.ExecutionRuntime;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
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

    /** Creates a task that fails when started. */
    public static <T> Task<T> failure(Throwable failure) {
        Objects.requireNonNull(failure, "failure");
        return new Task<>((execution, callback) -> callback.fail(failure));
    }

    /** Creates a task that remains incomplete until its containing execution terminates. */
    public static <T> Task<T> never() {
        return new Task<>((execution, callback) -> { });
    }

    /** Imports an existing stage; the source may already have started before this task runs. */
    public static <T> Task<T> fromStage(CompletionStage<? extends T> stage) {
        Objects.requireNonNull(stage, "stage");
        return new Task<>((execution, callback) -> stage.whenComplete((value, failure) -> execution.ref().execute(() -> {
            if (failure == null) {
                callback.succeed(value);
            } else {
                callback.fail(failure);
            }
        })));
    }

    /** Defers creation of a task until each independent start. */
    public static <T> Task<T> defer(Supplier<? extends Task<T>> supplier) {
        Objects.requireNonNull(supplier, "supplier");
        return new Task<>((execution, callback) -> {
            Task<T> task = Objects.requireNonNull(supplier.get(), "deferred task");
            task.start(execution, callback);
        });
    }

    /** Transforms a successful task result. */
    public <U> Task<U> map(Function<? super T, ? extends U> mapper) {
        Objects.requireNonNull(mapper, "mapper");
        return new Task<>((execution, callback) -> start(execution, new Callback<>() {
            @Override
            public void succeed(T value) {
                try {
                    callback.succeed(mapper.apply(value));
                } catch (Throwable failure) {
                    callback.fail(failure);
                }
            }

            @Override
            public void fail(Throwable failure) {
                callback.fail(failure);
            }
        }));
    }

    /** Continues a successful task with another lazily started task. */
    public <U> Task<U> flatMap(Function<? super T, ? extends Task<U>> mapper) {
        Objects.requireNonNull(mapper, "mapper");
        return new Task<>((execution, callback) -> start(execution, new Callback<>() {
            @Override
            public void succeed(T value) {
                try {
                    Objects.requireNonNull(mapper.apply(value), "mapped task").start(execution, callback);
                } catch (Throwable failure) {
                    callback.fail(failure);
                }
            }

            @Override
            public void fail(Throwable failure) {
                callback.fail(failure);
            }
        }));
    }

    /** Recovers a failed task with a replacement value. */
    public Task<T> recover(Function<? super Throwable, ? extends T> recovery) {
        Objects.requireNonNull(recovery, "recovery");
        return new Task<>((execution, callback) -> start(execution, new Callback<>() {
            @Override
            public void succeed(T value) {
                callback.succeed(value);
            }

            @Override
            public void fail(Throwable failure) {
                try {
                    callback.succeed(recovery.apply(failure));
                } catch (Throwable recoveryFailure) {
                    callback.fail(recoveryFailure);
                }
            }
        }));
    }

    /** Fails the result if it has not completed before the supplied duration. */
    public Task<T> timeout(Duration duration) {
        Objects.requireNonNull(duration, "duration");
        if (duration.isNegative() || duration.isZero()) {
            throw new IllegalArgumentException("duration must be positive");
        }
        return new Task<>((execution, callback) -> {
            AtomicBoolean delivered = new AtomicBoolean();
            execution.onCleanup(() -> delivered.set(true));
            CompletableFuture.delayedExecutor(duration.toNanos(), java.util.concurrent.TimeUnit.NANOSECONDS).execute(
                    () -> execution.ref().execute(() -> {
                        if (delivered.compareAndSet(false, true)) {
                            callback.fail(new TimeoutException("task timeout exceeded"));
                        }
                    }));
            start(execution, once(delivered, callback));
        });
    }

    /** Runs a finalizer exactly once after success or failure. */
    public Task<T> finallyRun(Runnable finalizer) {
        Objects.requireNonNull(finalizer, "finalizer");
        return new Task<>((execution, callback) -> start(execution, new Callback<>() {
            @Override
            public void succeed(T value) {
                try {
                    finalizer.run();
                    callback.succeed(value);
                } catch (Throwable finalizerFailure) {
                    callback.fail(finalizerFailure);
                }
            }

            @Override
            public void fail(Throwable failure) {
                try {
                    finalizer.run();
                } catch (Throwable finalizerFailure) {
                    failure.addSuppressed(finalizerFailure);
                }
                callback.fail(failure);
            }
        }));
    }

    /** Starts this task in a new managed execution and returns its result stage. */
    public CompletionStage<T> run(ExecutionRuntime runtime) {
        Objects.requireNonNull(runtime, "runtime");
        CompletableFuture<T> result = new CompletableFuture<>();
        runtime.start(execution -> start(execution, new Callback<>() {
            @Override
            public void succeed(T value) {
                if (execution.complete()) {
                    result.complete(value);
                }
            }

            @Override
            public void fail(Throwable failure) {
                if (execution.fail(failure)) {
                    result.completeExceptionally(failure);
                }
            }
        }));
        return result;
    }

    /** Exports this task as a stage started in a new managed execution. */
    public CompletionStage<T> toStage(ExecutionRuntime runtime) {
        return run(runtime);
    }

    void start(Execution execution, Callback<? super T> callback) {
        node.start(execution, callback);
    }

    private static <T> Callback<T> once(AtomicBoolean delivered, Callback<? super T> callback) {
        return new Callback<>() {
            @Override
            public void succeed(T value) {
                if (delivered.compareAndSet(false, true)) {
                    callback.succeed(value);
                }
            }

            @Override
            public void fail(Throwable failure) {
                if (delivered.compareAndSet(false, true)) {
                    callback.fail(failure);
                }
            }
        };
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
