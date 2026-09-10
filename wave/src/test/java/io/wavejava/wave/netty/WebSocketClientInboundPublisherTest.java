package io.wavejava.wave.netty;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.wavejava.wave.api.websocket.WebSocketMessage;
import java.util.ArrayDeque;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class WebSocketClientInboundPublisherTest {
    @Test
    void deliversOnlyDemandedDataThenACompleteSignalOnTheCallbackExecutor() {
        var executor = new QueuedExecutor();
        var listener = new RecordingListener();
        var bridge = new WebSocketClientInboundPublisher(executor, listener);
        var subscriber = new Subscriber();
        bridge.subscribe(subscriber);
        assertEquals(null, subscriber.subscription.get());
        executor.runAll();
        subscriber.subscription.get().request(1);
        assertTrue(bridge.offer(WebSocketMessage.text("one")));
        executor.runAll();
        assertEquals(1, subscriber.messages.get());

        bridge.complete();
        executor.runAll();
        assertEquals(1, subscriber.completions.get());
        assertEquals(0, listener.failures.get());
    }

    @Test
    void rejectsSecondSubscriberInvalidDemandAndUnsupportedMessages() {
        var executor = new QueuedExecutor();
        var listener = new RecordingListener();
        var bridge = new WebSocketClientInboundPublisher(executor, listener);
        var first = new Subscriber();
        bridge.subscribe(first);
        var second = new Subscriber();
        bridge.subscribe(second);
        executor.runAll();
        assertEquals(1, second.errors.get());

        first.subscription.get().request(0);
        executor.runAll();
        assertEquals(1, first.errors.get());
        assertEquals(1, listener.failures.get());
        assertThrows(IllegalArgumentException.class, () -> bridge.offer(WebSocketMessage.ping(new byte[0])));
        assertThrows(IllegalArgumentException.class, () -> bridge.completeAfterPeerClose(WebSocketMessage.text("not-close")));
    }

    @Test
    void closesAfterPeerCloseOnlyWhenDemandAlreadyExistsAndBoundsPendingData() {
        var executor = new QueuedExecutor();
        var listener = new RecordingListener();
        var bridge = new WebSocketClientInboundPublisher(executor, listener);
        var subscriber = new Subscriber();
        bridge.subscribe(subscriber);
        executor.runAll();

        assertTrue(bridge.offer(WebSocketMessage.text("retained")));
        assertFalse(bridge.offer(WebSocketMessage.text("overflow")));
        assertFalse(bridge.offer(WebSocketMessage.text("late")));
        assertEquals(0, listener.failures.get(), "over-limit input is reported to the session through false admission");

        var closing = new WebSocketClientInboundPublisher(executor, listener);
        var closeSubscriber = new Subscriber();
        closing.subscribe(closeSubscriber);
        executor.runAll();
        closeSubscriber.subscription.get().request(1);
        closing.completeAfterPeerClose(WebSocketMessage.close(1000, "done"));
        executor.runAll();
        assertEquals(WebSocketMessage.Type.CLOSE, closeSubscriber.last.get().type());
        assertEquals(1, closeSubscriber.completions.get());
    }

    @Test
    void cancelledSubscriberSuppressesQueuedCallbacks() {
        var executor = new QueuedExecutor();
        var bridge = new WebSocketClientInboundPublisher(executor, new RecordingListener());
        var subscriber = new Subscriber();
        bridge.subscribe(subscriber);
        executor.runAll();
        subscriber.subscription.get().request(1);
        assertTrue(bridge.offer(WebSocketMessage.text("stale")));
        subscriber.subscription.get().cancel();
        executor.runAll();
        assertEquals(0, subscriber.messages.get());
        assertEquals(0, subscriber.errors.get());
    }

    private static final class Subscriber implements Flow.Subscriber<WebSocketMessage> {
        private final AtomicReference<Flow.Subscription> subscription = new AtomicReference<>();
        private final AtomicInteger messages = new AtomicInteger();
        private final AtomicInteger errors = new AtomicInteger();
        private final AtomicInteger completions = new AtomicInteger();
        private final AtomicReference<WebSocketMessage> last = new AtomicReference<>();

        @Override public void onSubscribe(Flow.Subscription value) { subscription.set(value); }
        @Override public void onNext(WebSocketMessage value) { last.set(value); messages.incrementAndGet(); }
        @Override public void onError(Throwable failure) { errors.incrementAndGet(); }
        @Override public void onComplete() { completions.incrementAndGet(); }
    }

    private static final class RecordingListener implements WebSocketClientInboundPublisher.Listener {
        private final AtomicInteger failures = new AtomicInteger();
        @Override public void onFailure(String reason, Throwable cause) { failures.incrementAndGet(); }
        @Override public void onTerminalDeliveryRejected(Throwable cause) { throw new AssertionError(cause); }
    }

    private static final class QueuedExecutor implements Executor {
        private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        @Override public void execute(Runnable command) { tasks.addLast(command); }
        private void runAll() { while (!tasks.isEmpty()) tasks.removeFirst().run(); }
    }
}
