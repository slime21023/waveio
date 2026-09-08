package io.waveio.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.waveio.execution.internal.SerialSegmentDispatcher;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.Test;

class ExecutionTerminationTest {
    @Test
    void firstTerminalSignalWinsAndLateCallbacksAreDiscarded() {
        List<Runnable> callbacks = new ArrayList<>();
        Executor executor = callbacks::add;
        Execution execution = new Execution(new SerialSegmentDispatcher(executor, 3), failure -> { });
        execution.ref().execute(() -> { });
        assertTrue(execution.cancel());
        assertFalse(execution.complete());
        assertFalse(execution.fail(new IllegalStateException()));
        assertEquals(ExecutionState.CANCELLED, execution.state());
        callbacks.forEach(Runnable::run);
    }

    @Test
    void cleanupIsLifoAndFailuresAreObservedWithoutReplacingTerminalState() {
        List<String> cleanup = new ArrayList<>();
        List<Throwable> failures = new ArrayList<>();
        Execution execution = new Execution(new SerialSegmentDispatcher(Runnable::run, 2), failures::add);
        execution.onCleanup(() -> cleanup.add("first"));
        execution.onCleanup(() -> {
            cleanup.add("second");
            throw new IllegalStateException("cleanup failure");
        });
        assertTrue(execution.complete());
        assertEquals(List.of("second", "first"), cleanup);
        assertEquals(1, failures.size());
        assertEquals(ExecutionState.SUCCEEDED, execution.state());
    }

    @Test
    void deadlineTerminatesAnExecutionWithoutWallClockSleep() throws Exception {
        try (ExecutionRuntime runtime = ExecutionRuntime.create(new ExecutionConfig(1, 1, Duration.ofNanos(1)))) {
            ExecutionHandle handle = runtime.start(execution -> { });
            assertEquals(ExecutionState.TIMED_OUT, handle.completion().toCompletableFuture().get());
        }
    }
}
