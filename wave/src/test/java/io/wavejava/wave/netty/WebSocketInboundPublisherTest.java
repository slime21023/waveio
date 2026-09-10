package io.wavejava.wave.netty;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.wavejava.wave.api.websocket.WebSocketMessage;
import java.util.ArrayDeque;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** Deterministic callback-generation tests for the bounded WebSocket inbound bridge. */
class WebSocketInboundPublisherTest {
    @Test
    void cancellationBeforeQueuedExecutorDeliverySuppressesStaleOnNext() {
        var executor = new QueuedExecutor();
        var subscriber = new RecordingSubscriber();
        var bridge = new WebSocketInboundPublisher(executor, Listener.NOOP);

        bridge.subscribe(subscriber);
        subscriber.subscription().request(1);
        assertTrue(bridge.offer(WebSocketMessage.text("stale")));
        subscriber.subscription().cancel();
        executor.runAll();

        assertEquals(0, subscriber.messages.get(), "cancelled subscriber must not receive queued onNext");
        assertEquals(0, subscriber.errors.get());
        assertFalse(bridge.canAcceptInput());
    }

    @Test
    void disconnectBeforeQueuedExecutorDeliverySuppressesOnNextAndSignalsOneError() {
        var executor = new QueuedExecutor();
        var subscriber = new RecordingSubscriber();
        var bridge = new WebSocketInboundPublisher(executor, Listener.NOOP);

        bridge.subscribe(subscriber);
        subscriber.subscription().request(1);
        assertTrue(bridge.offer(WebSocketMessage.text("stale")));
        bridge.fail("peer disconnected", new IllegalStateException("peer disconnected"));
        executor.runAll();

        assertEquals(0, subscriber.messages.get(), "disconnect must invalidate queued onNext");
        assertEquals(1, subscriber.errors.get(), "disconnect must signal one terminal error");
    }

    @Test
    void peerCloseWithoutDemandDropsCloseNotificationAndCompletesImmediately() {
        var executor = new QueuedExecutor();
        var subscriber = new RecordingSubscriber();
        var bridge = new WebSocketInboundPublisher(executor, Listener.NOOP);

        bridge.subscribe(subscriber);
        assertTrue(bridge.offer(WebSocketMessage.text("undemanded")));
        bridge.completeAfterPeerClose(WebSocketMessage.close(1000, "done"));
        executor.runAll();

        assertEquals(0, subscriber.messages.get(), "close must not bypass Flow demand");
        assertEquals(1, subscriber.completions.get(), "peer close must never wait for future demand");

        subscriber.subscription().request(1);
        executor.runAll();

        assertEquals(0, subscriber.messages.get(), "late demand cannot resurrect a dropped close notification");
        assertEquals(1, subscriber.completions.get());
    }

    @Test
    void peerCloseWithRemainingDemandDeliversCloseBeforeCompletion() {
        var executor = new QueuedExecutor();
        var subscriber = new RecordingSubscriber();
        var bridge = new WebSocketInboundPublisher(executor, Listener.NOOP);

        bridge.subscribe(subscriber);
        subscriber.subscription().request(1);
        bridge.completeAfterPeerClose(WebSocketMessage.close(1000, "done"));
        executor.runAll();

        assertEquals(1, subscriber.messages.get());
        assertEquals(WebSocketMessage.Type.CLOSE, subscriber.lastMessage.get().type());
        assertEquals(1, subscriber.completions.get(), "CLOSE must be followed by onComplete");
    }

    @Test
    void peerCloseAfterOneDemandWasConsumedByDataPreservesDataThenCompletesWithoutClose() {
        var executor = new QueuedExecutor();
        var subscriber = new RecordingSubscriber();
        var bridge = new WebSocketInboundPublisher(executor, Listener.NOOP);

        bridge.subscribe(subscriber);
        subscriber.subscription().request(1);
        assertTrue(bridge.offer(WebSocketMessage.text("already-admitted")));
        bridge.completeAfterPeerClose(WebSocketMessage.close(1000, "done"));
        executor.runAll();

        assertEquals(1, subscriber.messages.get(), "admitted data delivery must retain Flow order");
        assertEquals(WebSocketMessage.Type.TEXT, subscriber.lastMessage.get().type());
        assertEquals(1, subscriber.completions.get(), "close must not wait for future demand");

        subscriber.subscription().request(1);
        executor.runAll();
        assertEquals(WebSocketMessage.Type.TEXT, subscriber.lastMessage.get().type());
        assertEquals(1, subscriber.messages.get());
        assertEquals(1, subscriber.completions.get());
    }

    @Test
    void terminalExecutorRejectionIsReportedForTransportTeardown() {
        var listener = new RecordingListener();
        var bridge = new WebSocketInboundPublisher(command -> {
            throw new RejectedExecutionException("test executor is shut down");
        }, listener);
        var subscriber = new RecordingSubscriber();

        bridge.subscribe(subscriber);
        bridge.complete();

        assertEquals(1, listener.terminalRejections.get(), "terminal callback rejection cannot be silently lost");
        assertEquals(0, subscriber.completions.get());
    }

    private static final class RecordingSubscriber implements Flow.Subscriber<WebSocketMessage> {
        private final AtomicReference<Flow.Subscription> subscription = new AtomicReference<>();
        private final AtomicInteger messages = new AtomicInteger();
        private final AtomicInteger errors = new AtomicInteger();
        private final AtomicInteger completions = new AtomicInteger();
        private final AtomicReference<WebSocketMessage> lastMessage = new AtomicReference<>();

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription.set(subscription);
        }

        @Override
        public void onNext(WebSocketMessage item) {
            lastMessage.set(item);
            messages.incrementAndGet();
        }

        @Override
        public void onError(Throwable throwable) {
            errors.incrementAndGet();
        }

        @Override
        public void onComplete() {
            completions.incrementAndGet();
        }

        private Flow.Subscription subscription() {
            return subscription.get();
        }
    }

    private static final class QueuedExecutor implements Executor {
        private final ArrayDeque<Runnable> queued = new ArrayDeque<>();

        @Override
        public void execute(Runnable command) {
            queued.addLast(command);
        }

        private void runAll() {
            Runnable task;
            while ((task = queued.pollFirst()) != null) {
                task.run();
            }
        }
    }

    private enum Listener implements WebSocketInboundPublisher.Listener {
        NOOP;

        @Override
        public void onReadCapacityChanged() {
            // No transport is attached in this deterministic bridge test.
        }

        @Override
        public void onFailure(String reason, Throwable cause) {
            // Assertions observe Flow delivery directly.
        }

        @Override
        public void onTerminalDeliveryRejected(Throwable cause) {
            // Assertions observe deterministic executor behavior directly.
        }
    }

    private static final class RecordingListener implements WebSocketInboundPublisher.Listener {
        private final AtomicInteger terminalRejections = new AtomicInteger();

        @Override
        public void onReadCapacityChanged() {
            // No transport is attached in this deterministic bridge test.
        }

        @Override
        public void onFailure(String reason, Throwable cause) {
            // This test drives a graceful terminal transition.
        }

        @Override
        public void onTerminalDeliveryRejected(Throwable cause) {
            terminalRejections.incrementAndGet();
        }
    }
}
