package io.wavejava.wave.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.wavejava.wave.api.middleware.Outcome;
import io.wavejava.wave.api.observability.AccessLogEvent;
import io.wavejava.wave.api.observability.Observability;
import io.wavejava.wave.api.observability.ObservabilityLimits;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ObservabilityDispatcherTest {
    @Test
    void isolatesSlowSinksBehindABoundedQueueAndDropsInsteadOfCallerRuns() throws Exception {
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var delivered = new AtomicInteger();
        var callbackThread = new AtomicReference<String>();
        var configuration = Observability.builder()
                .limits(new ObservabilityLimits(1))
                .accessLog(event -> {
                    callbackThread.set(Thread.currentThread().getName());
                    delivered.incrementAndGet();
                    started.countDown();
                    await(release);
                })
                .build();
        var dispatcher = new ObservabilityDispatcher(configuration);
        try {
            dispatcher.record(event("one"));
            assertTrue(started.await(1, TimeUnit.SECONDS));
            dispatcher.record(event("two"));
            dispatcher.record(event("three"));

            assertEquals(1, dispatcher.droppedEvents());
            assertFalse(callbackThread.get().contains("main"));
            assertTrue(callbackThread.get().startsWith("wave-observability-"));
        } finally {
            release.countDown();
            dispatcher.close(Duration.ofSeconds(1));
        }
        assertEquals(2, delivered.get());
    }

    @Test
    void providerFailureDoesNotTerminateDeliveryToLaterBridges() throws Exception {
        var delivered = new CountDownLatch(1);
        var dispatcher = new ObservabilityDispatcher(Observability.builder()
                .accessLog(event -> {
                    throw new IllegalStateException("intentional bridge failure");
                })
                .metrics(event -> delivered.countDown())
                .build());
        try {
            dispatcher.record(event("resilient"));
            assertTrue(delivered.await(1, TimeUnit.SECONDS));
        } finally {
            dispatcher.close(Duration.ofSeconds(1));
        }
    }

    private static AccessLogEvent event(String requestId) {
        return new AccessLogEvent(
                requestId,
                "GET",
                "/test",
                200,
                Instant.EPOCH,
                Duration.ZERO,
                0,
                Outcome.Kind.SUCCESS,
                AccessLogEvent.TransportOutcome.WRITTEN);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(2, TimeUnit.SECONDS)) {
                throw new AssertionError("test sink was not released");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("test sink interrupted", interrupted);
        }
    }
}
