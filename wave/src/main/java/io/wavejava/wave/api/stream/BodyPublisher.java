package io.wavejava.wave.api.stream;

import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A single-consumption, backpressure-aware request body source.
 *
 * <p>This is the streaming counterpart to the bounded aggregate {@code Body}. A request owns
 * exactly one of those representations: an aggregate body cannot be read as a publisher, and a
 * streaming body cannot be read through aggregate {@code Body} methods. A request-created
 * publisher accepts one subscriber only.</p>
 *
 * <p>Each emitted buffer transfers its remaining bytes to the subscriber. Publishers must not
 * mutate or reuse those bytes after {@link Flow.Subscriber#onNext(Object)} returns. Subscribers
 * must consume a buffer before asking for more data and must not retain it beyond their own
 * documented ownership boundary. The HTTP transport copies bytes before creating a Netty buffer,
 * so Netty {@code ByteBuf} ownership never crosses this API.</p>
 */
@FunctionalInterface
public interface BodyPublisher extends Flow.Publisher<ByteBuffer> {
    /**
     * Adapts a JDK Flow publisher to the request-body contract.
     *
     * <p>The returned adapter is not itself single-use; {@code Request.Builder} applies that
     * restriction when it attaches a publisher to an individual request.</p>
     */
    static BodyPublisher from(Flow.Publisher<ByteBuffer> publisher) {
        var source = Objects.requireNonNull(publisher, "publisher");
        return subscriber -> source.subscribe(Objects.requireNonNull(subscriber, "subscriber"));
    }

    /** Returns a publisher that completes when its subscriber first requests data. */
    static BodyPublisher empty() {
        return subscriber -> {
            var target = Objects.requireNonNull(subscriber, "subscriber");
            target.onSubscribe(new Flow.Subscription() {
                private final AtomicBoolean terminated = new AtomicBoolean();

                @Override
                public void request(long demand) {
                    if (!terminated.compareAndSet(false, true)) {
                        return;
                    }
                    if (demand <= 0) {
                        target.onError(new IllegalArgumentException("Flow demand must be greater than zero"));
                    } else {
                        target.onComplete();
                    }
                }

                @Override
                public void cancel() {
                    terminated.set(true);
                }
            });
        };
    }

    /**
     * Returns a view that permits exactly one subscriber for the request lifetime.
     *
     * <p>This method is public to support adapters which need the same request-body ownership
     * rule outside {@code Request.Builder}.</p>
     */
    static BodyPublisher singleUse(Flow.Publisher<ByteBuffer> publisher) {
        var source = Objects.requireNonNull(publisher, "publisher");
        var subscribed = new AtomicBoolean();
        return subscriber -> {
            var target = Objects.requireNonNull(subscriber, "subscriber");
            if (subscribed.compareAndSet(false, true)) {
                source.subscribe(target);
                return;
            }
            target.onSubscribe(NoopSubscription.INSTANCE);
            target.onError(new IllegalStateException("Request body publisher may be subscribed only once"));
        };
    }

    /** Minimal subscription used only to report a rejected second subscriber. */
    enum NoopSubscription implements Flow.Subscription {
        INSTANCE;

        @Override
        public void request(long demand) {
            // The subscriber is about to receive an error.
        }

        @Override
        public void cancel() {
            // Nothing was subscribed upstream.
        }
    }
}
