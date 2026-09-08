package io.waveio.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class ObserverDispatcherTest {
    @Test
    void recordsDeliveredAndFailedObserverCallbacks() throws InterruptedException {
        CountDownLatch observed = new CountDownLatch(1);
        try (ObserverDispatcher dispatcher = new ObserverDispatcher(List.of(
                event -> { }, event -> { throw new IllegalStateException("broken"); },
                event -> observed.countDown()), 1, 1)) {
            assertTrue(dispatcher.submit(new ObservationEvent("request.completed", Instant.EPOCH)));
            assertTrue(observed.await(5, TimeUnit.SECONDS));
            assertEquals(new ObservationMetrics(2, 0, 1), dispatcher.metrics());
        }
    }

    @Test
    void rejectsWorkWhenBoundedQueueIsSaturated() throws InterruptedException {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (ObserverDispatcher dispatcher = new ObserverDispatcher(List.of(event -> {
            entered.countDown();
            try {
                release.await();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }), 1, 1)) {
            ObservationEvent event = new ObservationEvent("request.started", Instant.EPOCH);
            assertTrue(dispatcher.submit(event));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertTrue(dispatcher.submit(event));
            assertFalse(dispatcher.submit(event));
            assertEquals(1, dispatcher.metrics().rejected());
            release.countDown();
        }
    }
}
