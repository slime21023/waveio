package io.wavejava.wave.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class InboundFlowBridgeTest {
    @Test
    void holdsOnlyOneChunkAndIssuesOneManualReadPerDemandCycle() {
        var listener = new RecordingListener();
        var bridge = new InboundFlowBridge(Runnable::run, 6, listener);
        var received = new ArrayList<String>();
        var completionCount = new AtomicInteger();
        var subscription = new AtomicReference<Flow.Subscription>();

        bridge.subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription source) {
                subscription.set(source);
            }

            @Override
            public void onNext(ByteBuffer item) {
                received.add(StandardCharsets.UTF_8.decode(item).toString());
            }

            @Override
            public void onError(Throwable failure) {
                throw new AssertionError(failure);
            }

            @Override
            public void onComplete() {
                completionCount.incrementAndGet();
            }
        });

        subscription.get().request(1);
        assertEquals(1, listener.demands.get());
        subscription.get().request(1);
        assertEquals(1, listener.demands.get(), "additional demand cannot create an unbounded read queue");
        assertTrue(bridge.offer(bytes("one"), false));
        assertEquals(List.of("one"), received);
        assertEquals(2, listener.demands.get());
        assertTrue(bridge.offer(bytes("two"), true));
        assertEquals(List.of("one", "two"), received);
        assertEquals(1, completionCount.get());
        assertTrue(bridge.snapshot().sourceCompleted());
        assertFalse(bridge.snapshot().pendingChunk());
    }

    @Test
    void rejectsASecondBufferedTransportChunkAndSignalsTheSubscriber() {
        var listener = new RecordingListener();
        var bridge = new InboundFlowBridge(Runnable::run, 10, listener);
        var failure = new AtomicReference<Throwable>();

        bridge.subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription source) {
                // Deliberately retain no demand: one chunk is the complete buffer allowance.
            }

            @Override
            public void onNext(ByteBuffer item) {
                throw new AssertionError("no demand was granted");
            }

            @Override
            public void onError(Throwable cause) {
                failure.set(cause);
            }

            @Override
            public void onComplete() {
                throw new AssertionError("the bridge should fail");
            }
        });

        assertTrue(bridge.offer(bytes("one"), false));
        assertFalse(bridge.offer(bytes("two"), false));
        assertInstanceOf(IllegalStateException.class, failure.get());
        assertEquals(1, listener.failures.get());
        assertTrue(bridge.snapshot().cancelled());
    }

    @Test
    void queuedDeliveryIsSkippedWhenTransportCancellationWinsBeforeCallbackExecution() {
        var listener = new RecordingListener();
        var executor = new QueuedExecutor();
        var bridge = new InboundFlowBridge(executor, 10, listener);
        var events = new ArrayList<String>();
        var subscription = new AtomicReference<Flow.Subscription>();

        bridge.subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription source) {
                subscription.set(source);
            }

            @Override
            public void onNext(ByteBuffer item) {
                events.add("next");
            }

            @Override
            public void onError(Throwable failure) {
                events.add("error");
            }

            @Override
            public void onComplete() {
                events.add("complete");
            }
        });

        subscription.get().request(1);
        assertTrue(bridge.offer(bytes("one"), false));
        bridge.cancel("client disconnected");

        executor.runAll();

        assertEquals(List.of("error"), events,
                "a queued onNext must become stale once cancellation schedules onError");
    }

    @Test
    void queuedDeliveryIsSkippedWhenBridgeFailureWinsBeforeCallbackExecution() {
        var listener = new RecordingListener();
        var executor = new QueuedExecutor();
        var bridge = new InboundFlowBridge(executor, 10, listener);
        var events = new ArrayList<String>();
        var subscription = new AtomicReference<Flow.Subscription>();

        bridge.subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription source) {
                subscription.set(source);
            }

            @Override
            public void onNext(ByteBuffer item) {
                events.add("next");
            }

            @Override
            public void onError(Throwable failure) {
                events.add("error");
            }

            @Override
            public void onComplete() {
                events.add("complete");
            }
        });

        subscription.get().request(1);
        assertTrue(bridge.offer(bytes("one"), false));
        assertFalse(bridge.offer(bytes("two"), false));

        executor.runAll();

        assertEquals(List.of("error"), events,
                "a queued onNext must not race ahead of the terminal failure callback");
        assertEquals(1, listener.failures.get());
    }

    @Test
    void blockingOnSubscribeDoesNotHoldTheTransportOfferLockOrRunCallbacksEarly() throws Exception {
        var listener = new RecordingListener();
        var bridge = new InboundFlowBridge(Runnable::run, 10, listener);
        var onSubscribeEntered = new CountDownLatch(1);
        var releaseOnSubscribe = new CountDownLatch(1);
        var subscriptionReturned = new AtomicInteger();
        var callbacksBeforeSubscriptionReturn = new AtomicInteger();
        var received = new ArrayList<String>();

        var subscriberThread = Thread.ofVirtual().start(() -> bridge.subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription source) {
                source.request(1);
                onSubscribeEntered.countDown();
                await(releaseOnSubscribe, "test did not release onSubscribe");
                subscriptionReturned.incrementAndGet();
            }

            @Override
            public void onNext(ByteBuffer item) {
                if (subscriptionReturned.get() == 0) {
                    callbacksBeforeSubscriptionReturn.incrementAndGet();
                }
                received.add(StandardCharsets.UTF_8.decode(item).toString());
            }

            @Override
            public void onError(Throwable failure) {
                throw new AssertionError(failure);
            }

            @Override
            public void onComplete() {
                // The source remains open in this regression.
            }
        }));

        await(onSubscribeEntered, "subscriber did not enter onSubscribe");
        assertTimeoutPreemptively(Duration.ofSeconds(1), () -> assertTrue(bridge.offer(bytes("one"), false)),
                "a Netty offer must not wait for application onSubscribe");
        assertEquals(0, callbacksBeforeSubscriptionReturn.get());

        releaseOnSubscribe.countDown();
        subscriberThread.join(5_000);
        assertFalse(subscriberThread.isAlive(), "subscriber thread should complete after onSubscribe is released");
        assertEquals(List.of("one"), received);
        assertEquals(0, callbacksBeforeSubscriptionReturn.get(),
                "no Flow callback may precede onSubscribe returning");
    }

    @Test
    void invalidDemandOverflowAndSecondSubscriptionHaveOneTerminalFailureEach() {
        var listener = new RecordingListener();
        var bridge = new InboundFlowBridge(Runnable::run, 3, listener);
        var firstSubscription = new AtomicReference<Flow.Subscription>();
        var firstFailure = new AtomicReference<Throwable>();
        bridge.subscribe(new Flow.Subscriber<>() {
            @Override public void onSubscribe(Flow.Subscription subscription) { firstSubscription.set(subscription); }
            @Override public void onNext(ByteBuffer item) { throw new AssertionError("invalid demand must not deliver"); }
            @Override public void onError(Throwable failure) { firstFailure.set(failure); }
            @Override public void onComplete() { throw new AssertionError("invalid demand must fail"); }
        });
        firstSubscription.get().request(0);
        assertInstanceOf(IllegalStateException.class, firstFailure.get());
        assertEquals(1, listener.failures.get());
        assertFalse(bridge.offer(bytes("late"), true));

        var secondFailure = new AtomicReference<Throwable>();
        bridge.subscribe(new Flow.Subscriber<>() {
            @Override public void onSubscribe(Flow.Subscription subscription) { subscription.request(1); }
            @Override public void onNext(ByteBuffer item) { throw new AssertionError("second subscriber cannot receive data"); }
            @Override public void onError(Throwable failure) { secondFailure.set(failure); }
            @Override public void onComplete() { throw new AssertionError("second subscriber must fail"); }
        });
        assertInstanceOf(IllegalStateException.class, secondFailure.get());

        var overflowListener = new RecordingListener();
        var overflow = new InboundFlowBridge(Runnable::run, 2, overflowListener);
        var failure = new AtomicReference<Throwable>();
        overflow.subscribe(new Flow.Subscriber<>() {
            @Override public void onSubscribe(Flow.Subscription subscription) { subscription.request(1); }
            @Override public void onNext(ByteBuffer item) { throw new AssertionError("over-limit data must not deliver"); }
            @Override public void onError(Throwable cause) { failure.set(cause); }
            @Override public void onComplete() { throw new AssertionError("over-limit data must fail"); }
        });
        assertFalse(overflow.offer(bytes("too"), true));
        assertInstanceOf(IllegalStateException.class, failure.get());
        assertEquals(1, overflowListener.failures.get());
    }

    @Test
    void subscriberCancellationDropsPendingBodyAndMakesTransportReleasableAfterSourceEnds() {
        var listener = new RecordingListener();
        var bridge = new InboundFlowBridge(Runnable::run, 8, listener);
        var subscription = new AtomicReference<Flow.Subscription>();
        bridge.subscribe(new Flow.Subscriber<>() {
            @Override public void onSubscribe(Flow.Subscription source) { subscription.set(source); }
            @Override public void onNext(ByteBuffer item) { throw new AssertionError("cancelled subscriber must not receive data"); }
            @Override public void onError(Throwable failure) { throw new AssertionError(failure); }
            @Override public void onComplete() { throw new AssertionError("cancelled subscriber must not complete"); }
        });

        subscription.get().cancel();
        assertTrue(bridge.snapshot().cancelled());
        assertFalse(bridge.offer(bytes("late"), true));
        assertEquals(0, listener.demands.get());
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static void await(CountDownLatch latch, String message) {
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS), message);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(message, interrupted);
        }
    }

    private static final class QueuedExecutor implements Executor {
        private final List<Runnable> tasks = new ArrayList<>();

        @Override
        public void execute(Runnable command) {
            tasks.add(command);
        }

        private void runAll() {
            while (!tasks.isEmpty()) {
                tasks.removeFirst().run();
            }
        }
    }

    private static final class RecordingListener implements InboundFlowBridge.Listener {
        private final AtomicInteger demands = new AtomicInteger();
        private final AtomicInteger failures = new AtomicInteger();

        @Override
        public void onDemandAvailable() {
            demands.incrementAndGet();
        }

        @Override
        public void onComplete() {
            // Counted by the subscriber assertion above.
        }

        @Override
        public void onFailure(String reason, Throwable cause) {
            failures.incrementAndGet();
        }

        @Override
        public void onCancelled() {
            // Nothing to assert for this path.
        }
    }
}
