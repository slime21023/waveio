package io.waveio.http.internal.body;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class InboundBodyPublisherTest {
    @Test
    void emitsOnlyOnDemandAndRequestsOneTransportReadAtATime() {
        var reads = new AtomicInteger();
        var publisher = new InboundBodyPublisher(16, Runnable::run,
                reads::incrementAndGet, () -> {});
        var subscriber = new RecordingSubscriber();
        publisher.subscribe(subscriber);
        assertTrue(publisher.offer(bytes("one")));
        assertTrue(subscriber.values.isEmpty());
        assertEquals(0, reads.get());
        subscriber.subscription.request(1);
        assertEquals(java.util.List.of("one"), subscriber.values);
        assertEquals(0, reads.get());
        subscriber.subscription.request(1);
        assertEquals(1, reads.get());
        assertTrue(publisher.offer(bytes("two")));
        assertEquals(java.util.List.of("one", "two"), subscriber.values);
    }

    @Test
    void buffersAWholeDecodedBatchAndReplaysItInOrderOnDemand() {
        var publisher = new InboundBodyPublisher(16, Runnable::run, () -> {}, () -> {});
        var subscriber = new RecordingSubscriber();
        publisher.subscribe(subscriber);
        assertTrue(publisher.offer(bytes("one")));
        assertTrue(publisher.offer(bytes("two")));
        assertTrue(publisher.offer(bytes("three")));
        assertTrue(subscriber.values.isEmpty());

        subscriber.subscription.request(1);
        assertEquals(java.util.List.of("one"), subscriber.values);
        subscriber.subscription.request(2);
        assertEquals(java.util.List.of("one", "two", "three"), subscriber.values);
    }

    @Test
    void bufferedBatchStillStopsAtTheConfiguredBodyLimit() {
        var publisher = new InboundBodyPublisher(5, Runnable::run, () -> {}, () -> {});
        var subscriber = new RecordingSubscriber();
        publisher.subscribe(subscriber);
        assertTrue(publisher.offer(bytes("123")));
        assertFalse(publisher.offer(bytes("456")));
        assertInstanceOf(InboundBodyPublisher.BodyTooLargeException.class, subscriber.failure);
    }

    @Test
    void cancelledSubscriptionNeverReceivesAlreadyDecodedChunks() {
        var publisher = new InboundBodyPublisher(16, Runnable::run, () -> {}, () -> {});
        var subscriber = new RecordingSubscriber();
        publisher.subscribe(subscriber);
        subscriber.subscription.request(4);
        subscriber.subscription.cancel();

        assertFalse(publisher.offer(bytes("late")));
        assertTrue(subscriber.values.isEmpty());
        assertTrue(publisher.isTerminal());
    }

    @Test
    void rejectsBodyLimitBeforeDeliveringTheOverflowingChunk() {
        var publisher = new InboundBodyPublisher(5, Runnable::run, () -> {}, () -> {});
        var subscriber = new RecordingSubscriber();
        publisher.subscribe(subscriber);
        subscriber.subscription.request(2);
        assertTrue(publisher.offer(bytes("123")));
        assertFalse(publisher.offer(bytes("456")));
        assertEquals(java.util.List.of("123"), subscriber.values);
        assertInstanceOf(InboundBodyPublisher.BodyTooLargeException.class, subscriber.failure);
    }

    @Test
    void cancellationAndDisconnectAreTerminalAndReleaseTransport() {
        var cancellations = new AtomicInteger();
        var publisher = new InboundBodyPublisher(16, Runnable::run, () -> {},
                cancellations::incrementAndGet);
        var subscriber = new RecordingSubscriber();
        publisher.subscribe(subscriber);
        subscriber.subscription.cancel();
        subscriber.subscription.cancel();
        publisher.disconnect();
        assertEquals(1, cancellations.get());
        assertTrue(subscriber.failure == null);

        var disconnected = new InboundBodyPublisher(16, Runnable::run, () -> {}, () -> {});
        var other = new RecordingSubscriber();
        disconnected.subscribe(other);
        disconnected.disconnect();
        assertInstanceOf(InboundBodyPublisher.DisconnectedException.class, other.failure);
    }

    @Test
    void throwingTerminalSubscriberIsIsolatedAndCancelsTransport() {
        var cancellations = new AtomicInteger();
        var failed = new InboundBodyPublisher(16, Runnable::run, () -> {},
                cancellations::incrementAndGet);
        failed.subscribe(new ThrowingTerminalSubscriber());
        failed.fail(new IllegalStateException("failure"));
        assertEquals(1, cancellations.get());

        var completed = new InboundBodyPublisher(16, Runnable::run, () -> {},
                cancellations::incrementAndGet);
        var subscriber = new ThrowingTerminalSubscriber();
        completed.subscribe(subscriber);
        subscriber.subscription.request(1);
        completed.complete();
        assertEquals(2, cancellations.get());
    }

    @Test
    void reentrantDemandFromOnSubscribeSignalsCompletionExactlyOnce() {
        var publisher = new InboundBodyPublisher(16, Runnable::run, () -> {}, () -> {});
        assertTrue(publisher.offer(bytes("one")));
        publisher.complete();

        var subscriber = new EagerSubscriber();
        publisher.subscribe(subscriber);

        assertEquals(java.util.List.of("one"), subscriber.values);
        assertEquals(1, subscriber.completions);
    }

    @Test
    void reentrantDemandFromOnSubscribeSignalsFailureExactlyOnce() {
        var publisher = new InboundBodyPublisher(16, Runnable::run, () -> {}, () -> {});
        publisher.fail(new IllegalStateException("upstream"));

        var subscriber = new EagerSubscriber();
        publisher.subscribe(subscriber);

        assertEquals(1, subscriber.failures);
    }

    @Test
    void replaysALargeDecodedBatchWithoutRecursiveStackGrowth() {
        int chunks = 50_000;
        var publisher = new InboundBodyPublisher(chunks, Runnable::run, () -> {}, () -> {});
        for (int index = 0; index < chunks; index++) assertTrue(publisher.offer(bytes("x")));
        publisher.complete();

        var subscriber = new EagerSubscriber();
        publisher.subscribe(subscriber);

        assertEquals(chunks, subscriber.values.size());
        assertEquals(1, subscriber.completions);
    }

    private static ByteBuffer bytes(String value) {
        return ByteBuffer.wrap(value.getBytes(StandardCharsets.UTF_8));
    }

    private static final class RecordingSubscriber implements Flow.Subscriber<ByteBuffer> {
        private final ArrayList<String> values = new ArrayList<>();
        private Flow.Subscription subscription;
        private Throwable failure;
        @Override public void onSubscribe(Flow.Subscription value) { subscription = value; }
        @Override public void onNext(ByteBuffer value) {
            var bytes = new byte[value.remaining()];
            value.get(bytes);
            values.add(new String(bytes, StandardCharsets.UTF_8));
        }
        @Override public void onError(Throwable value) { failure = value; }
        @Override public void onComplete() {}
    }

    /** Renews demand from inside every callback, the shape most reactive collectors use. */
    private static final class EagerSubscriber implements Flow.Subscriber<ByteBuffer> {
        private final ArrayList<String> values = new ArrayList<>();
        private Flow.Subscription subscription;
        private int completions;
        private int failures;
        @Override public void onSubscribe(Flow.Subscription value) {
            subscription = value;
            value.request(1);
        }
        @Override public void onNext(ByteBuffer value) {
            var bytes = new byte[value.remaining()];
            value.get(bytes);
            values.add(new String(bytes, StandardCharsets.UTF_8));
            subscription.request(1);
        }
        @Override public void onError(Throwable value) { failures++; }
        @Override public void onComplete() { completions++; }
    }

    private static final class ThrowingTerminalSubscriber implements Flow.Subscriber<ByteBuffer> {
        private Flow.Subscription subscription;
        @Override public void onSubscribe(Flow.Subscription value) { subscription = value; }
        @Override public void onNext(ByteBuffer value) {}
        @Override public void onError(Throwable value) { throw new IllegalStateException("onError"); }
        @Override public void onComplete() { throw new IllegalStateException("onComplete"); }
    }
}
