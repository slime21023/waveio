package io.waveio.task;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/** Collects a byte-buffer publisher into a bounded byte array. */
public final class ByteBufferCollector {
    private ByteBufferCollector() {
    }

    /** Collects all bytes up to {@code maximumBytes}, cancelling the source on overflow. */
    public static CompletionStage<byte[]> collect(Flow.Publisher<ByteBuffer> publisher, int maximumBytes) {
        Objects.requireNonNull(publisher, "publisher");
        if (maximumBytes < 0) {
            throw new IllegalArgumentException("maximumBytes must not be negative");
        }
        CompletableFuture<byte[]> result = new CompletableFuture<>();
        publisher.subscribe(new Flow.Subscriber<>() {
            private byte[] bytes = new byte[Math.min(32, maximumBytes)];
            private int length;
            private Flow.Subscription subscription;

            @Override
            public void onSubscribe(Flow.Subscription candidate) {
                if (subscription != null) {
                    candidate.cancel();
                    return;
                }
                subscription = Objects.requireNonNull(candidate, "subscription");
                subscription.request(1);
            }

            @Override
            public void onNext(ByteBuffer buffer) {
                if (result.isDone()) {
                    return;
                }
                ByteBuffer source = Objects.requireNonNull(buffer, "buffer").asReadOnlyBuffer();
                int incoming = source.remaining();
                if (incoming > maximumBytes - length) {
                    subscription.cancel();
                    result.completeExceptionally(new IllegalArgumentException("byte-buffer collection limit exceeded"));
                    return;
                }
                ensureCapacity(length + incoming);
                source.get(bytes, length, incoming);
                length += incoming;
                subscription.request(1);
            }

            @Override
            public void onError(Throwable failure) {
                result.completeExceptionally(Objects.requireNonNull(failure, "failure"));
            }

            @Override
            public void onComplete() {
                result.complete(Arrays.copyOf(bytes, length));
            }

            private void ensureCapacity(int required) {
                if (required <= bytes.length) {
                    return;
                }
                int capacity = Math.max(required, Math.min(maximumBytes, Math.max(1, bytes.length * 2)));
                bytes = Arrays.copyOf(bytes, capacity);
            }
        });
        return result;
    }
}
