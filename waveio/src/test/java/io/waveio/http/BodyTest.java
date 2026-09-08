package io.waveio.http;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.nio.ByteBuffer;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class BodyTest {
    @Test void bodyCanBeCollectedOnlyOnce() throws Exception {
        AtomicInteger subscriptions = new AtomicInteger();
        Body body = Body.of(subscriber -> { subscriptions.incrementAndGet(); AtomicBoolean emitted = new AtomicBoolean(); subscriber.onSubscribe(new Flow.Subscription() { public void request(long demand) { if (emitted.compareAndSet(false, true)) { subscriber.onNext(ByteBuffer.wrap(new byte[] {1})); subscriber.onComplete(); } } public void cancel() { } }); });
        assertArrayEquals(new byte[] {1}, body.collect(1).toCompletableFuture().get());
        RecordingSubscriber second = new RecordingSubscriber(); body.subscribe(second);
        assertEquals(1, subscriptions.get()); assertTrue(second.failed);
    }
    private static final class RecordingSubscriber implements Flow.Subscriber<ByteBuffer> {
        private boolean failed;
        public void onSubscribe(Flow.Subscription subscription) { subscription.request(1); }
        public void onNext(ByteBuffer item) { }
        public void onError(Throwable throwable) { failed = true; }
        public void onComplete() { }
    }
}
