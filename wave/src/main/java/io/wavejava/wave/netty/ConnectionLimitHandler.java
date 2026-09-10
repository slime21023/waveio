package io.wavejava.wave.netty;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/** Rejects newly active connections once the configured connection budget is exhausted. */
final class ConnectionLimitHandler extends ChannelInboundHandlerAdapter {
    private final AtomicInteger activeConnections;
    private final int maximumConnections;
    private boolean admitted;

    ConnectionLimitHandler(AtomicInteger activeConnections, int maximumConnections) {
        this.activeConnections = Objects.requireNonNull(activeConnections, "activeConnections");
        this.maximumConnections = maximumConnections;
    }

    @Override
    public void channelActive(ChannelHandlerContext context) {
        var current = activeConnections.incrementAndGet();
        if (current > maximumConnections) {
            activeConnections.decrementAndGet();
            context.close();
            return;
        }
        admitted = true;
        context.fireChannelActive();
    }

    @Override
    public void channelInactive(ChannelHandlerContext context) {
        if (admitted) {
            activeConnections.decrementAndGet();
            admitted = false;
        }
        context.fireChannelInactive();
    }
}
