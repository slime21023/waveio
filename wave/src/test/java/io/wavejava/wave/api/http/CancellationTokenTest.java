package io.wavejava.wave.api.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class CancellationTokenTest {
    @Test
    void cancellationIsOneWayAndPreservesTheFirstReason() {
        var token = CancellationToken.create();

        assertFalse(token.isCancelled());
        assertTrue(token.reason().isEmpty());
        assertTrue(token.cancel("client disconnected"));
        assertTrue(token.isCancelled());
        assertEquals("client disconnected", token.reason().orElseThrow());
        assertFalse(token.cancel("server shutting down"));
        assertEquals("client disconnected", token.reason().orElseThrow());
        assertThrows(IllegalArgumentException.class, () -> CancellationToken.create().cancel(" "));
    }

    @Test
    void exactlyOneConcurrentCancellerWins() throws Exception {
        var token = CancellationToken.create();
        var contenderCount = 24;
        var ready = new CountDownLatch(contenderCount);
        var start = new CountDownLatch(1);
        var completed = new CountDownLatch(contenderCount);
        var reasons = new HashSet<String>();
        var cancellations = new ArrayList<java.util.concurrent.Future<Boolean>>();

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (var index = 0; index < contenderCount; index++) {
                var reason = "reason-" + index;
                reasons.add(reason);
                cancellations.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    try {
                        return token.cancel(reason);
                    } finally {
                        completed.countDown();
                    }
                }));
            }

            assertTrue(ready.await(5, TimeUnit.SECONDS), "all contenders should be ready");
            start.countDown();
            assertTrue(completed.await(5, TimeUnit.SECONDS), "all contenders should finish");
            assertEquals(1, cancellations.stream().filter(future -> get(future)).count());
        }

        assertTrue(token.isCancelled());
        assertTrue(reasons.contains(token.reason().orElseThrow()));
    }

    @Test
    void registrationsReceiveTheWinningReasonAndLateRegistrationsRunImmediately() {
        var token = CancellationToken.create();
        var observed = new AtomicReference<String>();
        var registration = token.onCancellation(observed::set);

        assertTrue(registration.isActive());
        assertTrue(token.cancel("deadline exceeded"));
        assertEquals("deadline exceeded", observed.get());
        assertFalse(registration.isActive());

        var lateReason = new AtomicReference<String>();
        var lateRegistration = token.onCancellation(lateReason::set);
        assertEquals("deadline exceeded", lateReason.get());
        assertFalse(lateRegistration.isActive());
    }

    @Test
    void closingRegistrationPreventsItsCallbackWithoutAffectingOtherCleanup() {
        var token = CancellationToken.create();
        var skipped = new AtomicInteger();
        var delivered = new AtomicInteger();
        var registration = token.onCancellation(ignored -> skipped.incrementAndGet());
        token.onCancellation(ignored -> delivered.incrementAndGet());

        registration.close();
        registration.close();
        assertFalse(registration.isActive());
        assertTrue(token.cancel("client disconnected"));

        assertEquals(0, skipped.get());
        assertEquals(1, delivered.get());
    }

    private static boolean get(java.util.concurrent.Future<Boolean> future) {
        try {
            return future.get();
        } catch (Exception exception) {
            throw new AssertionError("canceller failed", exception);
        }
    }
}
