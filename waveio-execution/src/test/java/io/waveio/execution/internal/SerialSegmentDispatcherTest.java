package io.waveio.execution.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.waveio.execution.ExecutionRejectedException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.Test;

class SerialSegmentDispatcherTest {
    @Test
    void oneExecutionRunsSegmentsSerially() {
        ControlledExecutor executor = new ControlledExecutor();
        SerialSegmentDispatcher dispatcher = new SerialSegmentDispatcher(executor, 3);
        List<String> observed = new ArrayList<>();
        dispatcher.dispatch(() -> observed.add("one"));
        dispatcher.dispatch(() -> observed.add("two"));

        assertEquals(1, executor.pendingCount());
        executor.runNext();
        assertEquals(List.of("one"), observed);
        assertEquals(1, executor.pendingCount());
        executor.runNext();
        assertEquals(List.of("one", "two"), observed);
    }

    @Test
    void separateExecutionsCanBeScheduledConcurrently() {
        ControlledExecutor executor = new ControlledExecutor();
        new SerialSegmentDispatcher(executor, 1).dispatch(() -> { });
        new SerialSegmentDispatcher(executor, 1).dispatch(() -> { });
        assertEquals(2, executor.pendingCount());
    }

    @Test
    void rejectsWhenBoundedOutstandingCapacityIsExhausted() {
        ControlledExecutor executor = new ControlledExecutor();
        SerialSegmentDispatcher dispatcher = new SerialSegmentDispatcher(executor, 1);
        dispatcher.dispatch(() -> { });
        assertThrows(ExecutionRejectedException.class, () -> dispatcher.dispatch(() -> { }));
    }

    @Test
    void rejectsExecutorSaturationExplicitly() {
        Executor rejecting = runnable -> {
            throw new java.util.concurrent.RejectedExecutionException();
        };
        SerialSegmentDispatcher dispatcher = new SerialSegmentDispatcher(rejecting, 1);
        assertThrows(ExecutionRejectedException.class, () -> dispatcher.dispatch(() -> { }));
    }

    private static final class ControlledExecutor implements Executor {
        private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();

        @Override
        public void execute(Runnable command) {
            tasks.addLast(command);
        }

        int pendingCount() {
            return tasks.size();
        }

        void runNext() {
            assertFalse(tasks.isEmpty());
            tasks.removeFirst().run();
        }
    }
}
