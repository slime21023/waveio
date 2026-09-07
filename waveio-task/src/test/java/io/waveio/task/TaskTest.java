package io.waveio.task;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
