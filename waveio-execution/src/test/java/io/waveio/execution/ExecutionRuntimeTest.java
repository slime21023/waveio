package io.waveio.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class ExecutionRuntimeTest {
    @Test
    void bindsCurrentOnlyWithinManagedSegments() throws Exception {
        ExecutionConfig config = new ExecutionConfig(2, 1, Duration.ofSeconds(1));
        assertThrows(IllegalStateException.class, Execution::current);
        try (ExecutionRuntime runtime = ExecutionRuntime.create(config)) {
            ExecutionHandle handle = runtime.start(execution -> {
                assertSame(execution, Execution.current());
                execution.ref().execute(() -> {
                    assertSame(execution, Execution.current());
                    execution.complete();
                });
            });
            assertEquals(ExecutionState.SUCCEEDED, handle.completion().toCompletableFuture().get(1, TimeUnit.SECONDS));
            assertEquals(ExecutionState.SUCCEEDED, handle.state());
        }
        assertThrows(IllegalStateException.class, Execution::current);
    }

    @Test
    void configRequiresEveryCapacityAndDeadline() {
        assertThrows(IllegalArgumentException.class, () -> new ExecutionConfig(0, 1, Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> new ExecutionConfig(1, 0, Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> new ExecutionConfig(1, 1, Duration.ZERO));
    }
}
