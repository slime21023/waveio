package io.waveio.task;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import org.junit.jupiter.api.Test;

class ByteBufferCollectorTest {
    @Test
    void requestsOnlyAsDemandedAndCollectsReadOnlyCopies() throws Exception {
        ProbePublisher publisher = new ProbePublisher(List.of(bytes("ab"), bytes("cd")));
        assertArrayEquals(bytes("abcd").array(), ByteBufferCollector.collect(publisher, 4).toCompletableFuture().get());
        assertEquals(3, publisher.requests);
        assertTrue(!publisher.cancelled);
    }

    @Test
    void cancelsOnBoundedCollectionOverflow() {
        ProbePublisher publisher = new ProbePublisher(List.of(bytes("abc")));
        ExecutionException failure = assertThrows(ExecutionException.class,
                () -> ByteBufferCollector.collect(publisher, 2).toCompletableFuture().get());
        assertEquals("byte-buffer collection limit exceeded", failure.getCause().getMessage());
        assertTrue(publisher.cancelled);
    }

    private static ByteBuffer bytes(String value) {
        return ByteBuffer.wrap(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static final class ProbePublisher implements Flow.Publisher<ByteBuffer> {
        private final ArrayDeque<ByteBuffer> items;
        private long requests;
        private boolean cancelled;

        private ProbePublisher(List<ByteBuffer> items) {
            this.items = new ArrayDeque<>(items);
        }

        @Override
        public void subscribe(Flow.Subscriber<? super ByteBuffer> subscriber) {
            subscriber.onSubscribe(new Flow.Subscription() {
                @Override
                public void request(long count) {
                    if (count <= 0) {
                        subscriber.onError(new IllegalArgumentException("non-positive demand"));
                        return;
                    }
                    requests += count;
                    while (count-- > 0 && !items.isEmpty() && !cancelled) {
                        subscriber.onNext(items.removeFirst());
                    }
                    if (items.isEmpty() && !cancelled) {
                        subscriber.onComplete();
                    }
                }

                @Override
                public void cancel() {
                    cancelled = true;
                }
            });
        }
    }
}
