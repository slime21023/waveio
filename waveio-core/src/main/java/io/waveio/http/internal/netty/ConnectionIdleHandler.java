package io.waveio.http.internal.netty;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import java.time.Duration;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

final class ConnectionIdleHandler extends ChannelInboundHandlerAdapter {
    private static final System.Logger LOG =
            System.getLogger(ConnectionIdleHandler.class.getName());

    private final long idleTimeoutNanos;
    private final long readTimeoutNanos;
    private ScheduledFuture<?> deadline;
    private boolean requestActive;

    ConnectionIdleHandler(Duration timeout) {
        this(timeout, timeout);
    }

    ConnectionIdleHandler(Duration idleTimeout, Duration readTimeout) {
        idleTimeoutNanos = positiveNanos(idleTimeout, "idleTimeout");
        readTimeoutNanos = positiveNanos(readTimeout, "readTimeout");
    }

    @Override
    public void channelActive(ChannelHandlerContext context) throws Exception {
        schedule(context, idleTimeoutNanos);
        context.fireChannelActive();
    }

    @Override
    public void channelRead(ChannelHandlerContext context, Object message) throws Exception {
        if (!requestActive) schedule(context, readTimeoutNanos);
        context.fireChannelRead(message);
    }

    void requestStarted(ChannelHandlerContext context) {
        requestActive = true;
        cancelDeadline();
    }

    void requestFinished(ChannelHandlerContext context) {
        requestActive = false;
        if (context.channel().isActive()) schedule(context, idleTimeoutNanos);
    }

    @Override
    public void channelInactive(ChannelHandlerContext context) throws Exception {
        cancelDeadline();
        context.fireChannelInactive();
    }

    private void schedule(ChannelHandlerContext context, long timeoutNanos) {
        cancelDeadline();
        deadline = context.executor().schedule(() -> {
            LOG.log(System.Logger.Level.DEBUG,
                    "Closing connection from {0}: no progress within the {1} ms connection"
                    + " deadline", context.channel().remoteAddress(),
                    java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(timeoutNanos));
            context.close();
        }, timeoutNanos,
                TimeUnit.NANOSECONDS);
    }

    private void cancelDeadline() {
        var current = deadline;
        deadline = null;
        if (current != null) current.cancel(false);
    }

    private static long positiveNanos(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        try {
            return value.toNanos();
        } catch (ArithmeticException failure) {
            return Long.MAX_VALUE;
        }
    }
}
