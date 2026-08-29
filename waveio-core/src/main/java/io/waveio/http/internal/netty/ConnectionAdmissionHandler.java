package io.waveio.http.internal.netty;

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.AttributeKey;
import java.util.concurrent.atomic.AtomicInteger;

@ChannelHandler.Sharable
final class ConnectionAdmissionHandler extends ChannelInboundHandlerAdapter {
    private static final System.Logger LOG =
            System.getLogger(ConnectionAdmissionHandler.class.getName());

    private static final AttributeKey<Boolean> ADMITTED =
            AttributeKey.valueOf(ConnectionAdmissionHandler.class, "admitted");

    private final int maximum;
    private final AtomicInteger active = new AtomicInteger();

    ConnectionAdmissionHandler(int maximum) {
        if (maximum <= 0) throw new IllegalArgumentException("maximum must be positive");
        this.maximum = maximum;
    }

    @Override
    public void channelActive(ChannelHandlerContext context) throws Exception {
        if (!tryAcquire()) {
            LOG.log(System.Logger.Level.DEBUG,
                    "Closing connection from {0}: the server already holds its maximum of {1}"
                    + " admitted connections", context.channel().remoteAddress(), maximum);
            context.close();
            return;
        }
        context.channel().attr(ADMITTED).set(true);
        context.fireChannelActive();
    }

    @Override
    public void channelInactive(ChannelHandlerContext context) throws Exception {
        if (Boolean.TRUE.equals(context.channel().attr(ADMITTED).getAndSet(false))) {
            active.decrementAndGet();
        }
        context.fireChannelInactive();
    }

    int activeConnections() {
        return active.get();
    }

    private boolean tryAcquire() {
        int current;
        do {
            current = active.get();
            if (current >= maximum) return false;
        } while (!active.compareAndSet(current, current + 1));
        return true;
    }
}
