package io.waveio.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ExecutionRuntimeGateTest {
    @Test
    void concurrentExecutionsKeepTheirScopedContextIsolated() throws Exception {
        try (ExecutionRuntime runtime = ExecutionRuntime.create(new ExecutionConfig(3, 2, Duration.ofSeconds(2)))) {
            CountDownLatch entered = new CountDownLatch(2);
            CountDownLatch release = new CountDownLatch(1);
            AtomicBoolean firstCorrect = new AtomicBoolean();
            AtomicBoolean secondCorrect = new AtomicBoolean();
            ExecutionHandle first = runtime.start(execution -> {
                firstCorrect.set(Execution.current() == execution);
                entered.countDown();
                await(release);
                execution.complete();
            });
            ExecutionHandle second = runtime.start(execution -> {
                secondCorrect.set(Execution.current() == execution);
                entered.countDown();
                await(release);
                execution.complete();
            });

            assertTrue(entered.await(1, TimeUnit.SECONDS));
            release.countDown();
            assertEquals(ExecutionState.SUCCEEDED, first.completion().toCompletableFuture().get(1, TimeUnit.SECONDS));
            assertEquals(ExecutionState.SUCCEEDED, second.completion().toCompletableFuture().get(1, TimeUnit.SECONDS));
            assertTrue(firstCorrect.get());
            assertTrue(secondCorrect.get());
        }
        assertThrows(IllegalStateException.class, Execution::current);
    }

    @Test
    void oneExecutionNeverOverlapsItsSegmentsAndCleanupRunsOnce() throws Exception {
        try (ExecutionRuntime runtime = ExecutionRuntime.create(new ExecutionConfig(3, 2, Duration.ofSeconds(2)))) {
            AtomicInteger active = new AtomicInteger();
            AtomicInteger maximum = new AtomicInteger();
            AtomicInteger cleanup = new AtomicInteger();
            ExecutionHandle handle = runtime.start(execution -> {
                execution.onCleanup(cleanup::incrementAndGet);
                execution.ref().execute(() -> {
                    maximum.accumulateAndGet(active.incrementAndGet(), Math::max);
                    active.decrementAndGet();
                    execution.complete();
                });
            });
            assertEquals(ExecutionState.SUCCEEDED, handle.completion().toCompletableFuture().get(1, TimeUnit.SECONDS));
            assertFalse(handle.cancel());
            assertEquals(1, maximum.get());
            assertEquals(1, cleanup.get());
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(1, TimeUnit.SECONDS)) {
                throw new AssertionError("test coordination timed out");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
    }
}
