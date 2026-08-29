package io.waveio.http.internal.netty;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.LastHttpContent;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.OptionalLong;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class NettyBodySubscriberTest {

    @Test
    void streamsBuffersAndCompletes() {
        var handler = new io.netty.channel.ChannelInboundHandlerAdapter();
        var channel = new EmbeddedChannel(handler);
        var context = channel.pipeline().context(handler);
        var completed = new AtomicBoolean();

        var subscriber = new NettyBodySubscriber(context, true, OptionalLong.of(5), Duration.ofSeconds(30), ignored -> completed.set(true));

        var subCancelled = new AtomicBoolean();
        var requestedCount = new AtomicBoolean();

        subscriber.onSubscribe(new Flow.Subscription() {
            @Override
            public void request(long n) {
                requestedCount.set(true);
            }

            @Override
            public void cancel() {
                subCancelled.set(true);
            }
        });

        assertTrue(requestedCount.get());

        // Duplicate subscription is immediately cancelled
        var secondCancelled = new AtomicBoolean();
        subscriber.onSubscribe(new Flow.Subscription() {
            @Override public void request(long n) {}
            @Override
            public void cancel() {
                secondCancelled.set(true);
            }
        });
        assertTrue(secondCancelled.get());

        // Send 5 bytes
        subscriber.onNext(ByteBuffer.wrap("hello".getBytes(StandardCharsets.UTF_8)));
        channel.runPendingTasks();

        HttpContent chunk = channel.readOutbound();
        assertTrue(chunk != null);
        chunk.release();

        // Complete
        subscriber.onComplete();
        channel.runPendingTasks();

        LastHttpContent last = channel.readOutbound();
        assertTrue(last != null);
        last.release();
        assertTrue(completed.get());
    }

    @Test
    void rejectsNullItemOnNext() {
        var handler = new io.netty.channel.ChannelInboundHandlerAdapter();
        var channel = new EmbeddedChannel(handler);
        var context = channel.pipeline().context(handler);
        var completed = new AtomicBoolean();

        var subscriber = new NettyBodySubscriber(context, true, OptionalLong.empty(), Duration.ofSeconds(30), ignored -> completed.set(true));
        subscriber.onSubscribe(new Flow.Subscription() {
            @Override public void request(long n) {}
            @Override public void cancel() {}
        });

        subscriber.onNext(null);
        channel.runPendingTasks();

        assertTrue(completed.get());
        assertFalse(channel.isOpen());
    }

    @Test
    void reportsErrorWhenPayloadExceedsContentLength() {
        var handler = new io.netty.channel.ChannelInboundHandlerAdapter();
        var channel = new EmbeddedChannel(handler);
        var context = channel.pipeline().context(handler);
        var completed = new AtomicBoolean();

        var subscriber = new NettyBodySubscriber(context, true, OptionalLong.of(2), Duration.ofSeconds(30), ignored -> completed.set(true));
        subscriber.onSubscribe(new Flow.Subscription() {
            @Override public void request(long n) {}
            @Override public void cancel() {}
        });

        subscriber.onNext(ByteBuffer.wrap("too-long".getBytes(StandardCharsets.UTF_8)));
        channel.runPendingTasks();

        assertTrue(completed.get());
        assertFalse(channel.isOpen());
    }

    @Test
    void reportsErrorWhenStreamCompletesEarly() {
        var handler = new io.netty.channel.ChannelInboundHandlerAdapter();
        var channel = new EmbeddedChannel(handler);
        var context = channel.pipeline().context(handler);
        var completed = new AtomicBoolean();

        var subscriber = new NettyBodySubscriber(context, true, OptionalLong.of(10), Duration.ofSeconds(30), ignored -> completed.set(true));
        subscriber.onSubscribe(new Flow.Subscription() {
            @Override public void request(long n) {}
            @Override public void cancel() {}
        });

        subscriber.onNext(ByteBuffer.wrap("short".getBytes(StandardCharsets.UTF_8)));
        channel.runPendingTasks();

        subscriber.onComplete();
        channel.runPendingTasks();

        assertTrue(completed.get());
        assertFalse(channel.isOpen());
    }

    @Test
    void onErrorClosesContextAndRunsCompletion() {
        var handler = new io.netty.channel.ChannelInboundHandlerAdapter();
        var channel = new EmbeddedChannel(handler);
        var context = channel.pipeline().context(handler);
        var completed = new AtomicBoolean();
        var subCancelled = new AtomicBoolean();

        var subscriber = new NettyBodySubscriber(context, true, OptionalLong.empty(), Duration.ofSeconds(30), ignored -> completed.set(true));
        subscriber.onSubscribe(new Flow.Subscription() {
            @Override public void request(long n) {}
            @Override
            public void cancel() {
                subCancelled.set(true);
            }
        });

        subscriber.onError(new RuntimeException("stream failed"));
        channel.runPendingTasks();

        assertTrue(subCancelled.get());
        assertTrue(completed.get());
        assertFalse(channel.isOpen());
    }
}
