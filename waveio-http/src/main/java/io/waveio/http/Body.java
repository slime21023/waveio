package io.waveio.http;

import io.waveio.task.ByteBufferCollector;
import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;

/** A single-consumption HTTP body publisher. */
public final class Body implements Flow.Publisher<ByteBuffer> {
    private final Flow.Publisher<ByteBuffer> source;
    private final AtomicBoolean consumed = new AtomicBoolean();

    private Body(Flow.Publisher<ByteBuffer> source) { this.source = Objects.requireNonNull(source, "source"); }
    /** Wraps a body publisher that may be subscribed to exactly once. */ public static Body of(Flow.Publisher<ByteBuffer> source) { return new Body(source); }
    /** Returns an empty body. */ public static Body empty() { return of(subscriber -> subscriber.onSubscribe(new Flow.Subscription() { public void request(long count) { if (count > 0) { subscriber.onComplete(); } else { subscriber.onError(new IllegalArgumentException("non-positive demand")); } } public void cancel() { } })); }
    /** Subscribes once; a second subscriber receives an error without reaching the source. */
    @Override public void subscribe(Flow.Subscriber<? super ByteBuffer> subscriber) {
        Objects.requireNonNull(subscriber, "subscriber");
        if (!consumed.compareAndSet(false, true)) {
            subscriber.onSubscribe(new Flow.Subscription() { public void request(long count) { } public void cancel() { } });
            subscriber.onError(new IllegalStateException("HTTP body has already been consumed")); return;
        }
        source.subscribe(subscriber);
    }
    /** Collects this body using an explicit maximum byte count. */
    public CompletionStage<byte[]> collect(int maximumBytes) { return ByteBufferCollector.collect(this, maximumBytes); }
}
