package io.waveio.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.waveio.execution.ExecutionConfig;
import io.waveio.execution.ExecutionRuntime;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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
            int recoveredWithTask = Task.<Integer>failure(new IllegalArgumentException("bad"))
                    .recoverWith(failure -> Task.success(8))
                    .run(runtime).toCompletableFuture().get();
            assertEquals(8, recoveredWithTask);
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
    void taskTimeoutCannotExtendTheParentExecutionDeadline() throws Exception {
        try (ExecutionRuntime runtime = ExecutionRuntime.create(new ExecutionConfig(4, 1, Duration.ofNanos(1)))) {
            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> Task.<Integer>never().timeout(Duration.ofSeconds(1)).run(runtime).toCompletableFuture().get());
            assertEquals(io.waveio.execution.ExecutionDeadlineExceededException.class, failure.getCause().getClass());
        }
    }

    @Test
    void taskHandleCancelsItsOwningExecution() throws Exception {
        try (ExecutionRuntime runtime = ExecutionRuntime.create(new ExecutionConfig(4, 1, Duration.ofSeconds(1)))) {
            TaskHandle<Integer> handle = Task.<Integer>never().start(runtime);
            assertTrue(handle.cancel());
            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> handle.completion().toCompletableFuture().get());
            assertEquals(io.waveio.execution.ExecutionCancelledException.class, failure.getCause().getClass());
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

    @Test
    void blockingRuntimeBoundsSlotsAndReturnsResultToExecution() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (BlockingRuntime blocking = new BlockingRuntime(new BlockingConfig(1, 0));
                ExecutionRuntime runtime = ExecutionRuntime.create(new ExecutionConfig(4, 2, Duration.ofSeconds(2)))) {
            var first = Task.blocking(blocking, () -> {
                started.countDown();
                if (!release.await(1, TimeUnit.SECONDS)) {
                    throw new AssertionError("blocking test coordination timed out");
                }
                return 1;
            }).run(runtime).toCompletableFuture();
            if (!started.await(1, TimeUnit.SECONDS)) {
                throw new AssertionError("blocking work did not start");
            }
            ExecutionException rejected = assertThrows(ExecutionException.class,
                    () -> Task.blocking(blocking, () -> 2).run(runtime).toCompletableFuture().get());
            assertEquals("blocking runtime capacity exhausted", rejected.getCause().getMessage());
            release.countDown();
            assertEquals(1, first.get());
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
