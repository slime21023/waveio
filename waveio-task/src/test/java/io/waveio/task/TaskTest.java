package io.waveio.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.waveio.execution.ExecutionConfig;
import io.waveio.execution.ExecutionRuntime;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class TaskTest {
    @Test
    void deferDoesNotCreateWorkUntilEachIndependentStart() {
        AtomicInteger starts = new AtomicInteger();
        Task<Integer> task = Task.defer(() -> Task.success(starts.incrementAndGet()));
        assertEquals(0, starts.get());
        RecordingCallback<Integer> first = new RecordingCallback<>();
        RecordingCallback<Integer> second = new RecordingCallback<>();
        task.start(null, first);
        task.start(null, second);
        assertEquals(1, first.value);
        assertEquals(2, second.value);
    }

    @Test
    void composesSuccessFailureRecoveryAndFinalizers() throws Exception {
        AtomicInteger finalizers = new AtomicInteger();
        try (ExecutionRuntime runtime = ExecutionRuntime.create(new ExecutionConfig(4, 1, Duration.ofSeconds(1)))) {
            int value = Task.success(2)
                    .map(number -> number + 1)
                    .flatMap(number -> Task.success(number * 2))
                    .finallyRun(finalizers::incrementAndGet)
                    .run(runtime).toCompletableFuture().get();
            assertEquals(6, value);
            assertEquals(1, finalizers.get());
            int recovered = Task.<Integer>failure(new IllegalArgumentException("bad"))
                    .recover(failure -> 7)
                    .run(runtime).toCompletableFuture().get();
            assertEquals(7, recovered);
        }
    }

    @Test
    void finalizerFailureChangesSuccessAndIsSuppressedOnFailure() throws Exception {
        try (ExecutionRuntime runtime = ExecutionRuntime.create(new ExecutionConfig(4, 1, Duration.ofSeconds(1)))) {
            ExecutionException successFailure = assertThrows(ExecutionException.class,
                    () -> Task.success(1).finallyRun(() -> { throw new IllegalStateException("finalizer"); })
                            .run(runtime).toCompletableFuture().get());
            assertEquals("finalizer", successFailure.getCause().getMessage());
            IllegalArgumentException source = new IllegalArgumentException("source");
            ExecutionException failed = assertThrows(ExecutionException.class,
                    () -> Task.<Integer>failure(source).finallyRun(() -> { throw new IllegalStateException("finalizer"); })
                            .run(runtime).toCompletableFuture().get());
            assertEquals(source, failed.getCause());
            assertEquals(1, source.getSuppressed().length);
        }
    }

    @Test
    void timeoutFailsAnIncompleteTask() throws Exception {
        try (ExecutionRuntime runtime = ExecutionRuntime.create(new ExecutionConfig(4, 1, Duration.ofSeconds(1)))) {
            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> Task.<Integer>never().timeout(Duration.ofNanos(1)).run(runtime).toCompletableFuture().get());
            assertEquals(java.util.concurrent.TimeoutException.class, failure.getCause().getClass());
        }
    }

    @Test
    void importedCompletionStageReturnsThroughManagedExecution() throws Exception {
        CompletableFuture<Integer> source = new CompletableFuture<>();
        try (ExecutionRuntime runtime = ExecutionRuntime.create(new ExecutionConfig(4, 1, Duration.ofSeconds(1)))) {
            var result = Task.fromStage(source).map(value -> value + 1).toStage(runtime).toCompletableFuture();
            source.complete(4);
            assertEquals(5, result.get());
        }
    }

    private static final class RecordingCallback<T> implements Task.Callback<T> {
        private T value;

        @Override
        public void succeed(T result) {
            value = result;
        }

        @Override
        public void fail(Throwable failure) {
            throw new AssertionError(failure);
        }
    }
}
