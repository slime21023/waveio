package io.waveio.http.internal.netty;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.concurrent.Future;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.OptionalLong;
import java.util.concurrent.Flow;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

final class NettyBodySubscriber implements Flow.Subscriber<ByteBuffer> {
    private static final System.Logger LOG =
            System.getLogger(NettyBodySubscriber.class.getName());

    private final ChannelHandlerContext context;
    private final boolean keepAlive;
    private final Consumer<WriteResult> completion;
    private final Duration stallTimeout;
    private final long stallTimeoutNanos;
    private final AtomicBoolean terminated = new AtomicBoolean();
    private long remainingLength;
    private volatile Flow.Subscription subscription;
    private ScheduledFuture<?> stallDeadline;

    NettyBodySubscriber(ChannelHandlerContext context, boolean keepAlive,
            OptionalLong contentLength, Duration stallTimeout, Consumer<WriteResult> completion) {
        this.context = context;
        this.keepAlive = keepAlive;
        this.completion = completion;
        this.stallTimeout = stallTimeout;
        stallTimeoutNanos = timeoutNanos(stallTimeout);
        remainingLength = contentLength.orElse(-1);
    }

    private static long timeoutNanos(Duration timeout) {
        try {
            return timeout.toNanos();
        } catch (ArithmeticException failure) {
            return Long.MAX_VALUE;
        }
    }

    /** Starts the stall deadline before the response body publisher is subscribed. */
    void beginBody() {
        executeOnEventLoop(this::armStallDeadline);
    }

    @Override
    public void onSubscribe(Flow.Subscription subscription) {
        if (terminated.get()) {
            subscription.cancel();
            return;
        }
        if (this.subscription != null) {
            subscription.cancel();
            return;
        }
        this.subscription = subscription;
        executeOnEventLoop(this::armStallDeadline);
        subscription.request(1);
    }

    void cancel() {
        if (!terminated.compareAndSet(false, true)) return;
        executeOnEventLoop(this::cancelStallDeadline);
        var current = subscription;
        if (current != null) current.cancel();
    }

    @Override
    public void onNext(ByteBuffer item) {
        if (terminated.get()) return;
        if (item == null) {
            onError(new NullPointerException("Streaming body emitted null"));
            return;
        }
        var view = item.slice();
        var bytes = new byte[view.remaining()];
        view.get(bytes);
        if (remainingLength >= 0) {
            if (bytes.length > remainingLength) {
                onError(new IllegalStateException("Streaming body exceeded Content-Length"));
                return;
            }
            remainingLength -= bytes.length;
        }
        executeOnEventLoop(() -> {
            cancelStallDeadline();
            context.writeAndFlush(new DefaultHttpContent(Unpooled.wrappedBuffer(bytes)))
                    .addListener(future -> {
                        if (!future.isSuccess()) {
                            onError(failureOf(future));
                            return;
                        }
                        if (terminated.get()) return;
                        armStallDeadline();
                        var current = subscription;
                        if (current != null) current.request(1);
                    });
        });
    }

    @Override
    public void onError(Throwable failure) {
        if (!terminated.compareAndSet(false, true)) return;
        var current = subscription;
        if (current != null) current.cancel();
        LOG.log(System.Logger.Level.DEBUG,
                () -> "Closing connection from " + context.channel().remoteAddress()
                        + ": the streaming response body failed", failure);
        executeOnEventLoop(() -> {
            cancelStallDeadline();
            completion.accept(new WriteResult.Failure(failure != null ? failure
                    : new IllegalStateException("Streaming body failed without a cause")));
            context.close();
        });
    }

    @Override
    public void onComplete() {
        if (remainingLength > 0) {
            onError(new IllegalStateException("Streaming body ended before Content-Length"));
            return;
        }
        if (!terminated.compareAndSet(false, true)) return;
        executeOnEventLoop(() -> {
            cancelStallDeadline();
            var future = context.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT);
            future.addListener(result -> completion.accept(result.isSuccess()
                    ? new WriteResult.Success()
                    : new WriteResult.Failure(failureOf(result))));
            if (!keepAlive) future.addListener(ChannelFutureListener.CLOSE);
        });
    }

    /**
     * Bounds the gap between response body chunks. Netty's write deadline only covers writes that
     * were actually issued, so without this a publisher that stops signalling would pin the
     * exchange, its connection and its admission permit until the peer disconnects.
     */
    private void armStallDeadline() {
        cancelStallDeadline();
        if (terminated.get()) return;
        stallDeadline = context.executor().schedule(
                () -> onError(new TimeoutException(
                        "Streaming response body stalled for " + stallTimeout)),
                stallTimeoutNanos, TimeUnit.NANOSECONDS);
    }

    private void cancelStallDeadline() {
        var current = stallDeadline;
        stallDeadline = null;
        if (current != null) current.cancel(false);
    }

    private void executeOnEventLoop(Runnable task) {
        if (context.executor().inEventLoop()) task.run();
        else context.executor().execute(task);
    }

    private static Throwable failureOf(Future<?> future) {
        return future.cause() != null ? future.cause()
                : new IllegalStateException("HTTP response write failed");
    }
}
