package io.waveio.http.internal.netty;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.timeout.WriteTimeoutHandler;
import io.netty.util.ReferenceCountUtil;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class NettyWriteDeadlineTest {

    @Test
    void closesChannelWhenAnOutboundWriteDoesNotComplete() {
        var heldMessage = new AtomicReference<Object>();
        var stalledTransport = new ChannelOutboundHandlerAdapter() {
            @Override
            public void write(ChannelHandlerContext context, Object message,
                    ChannelPromise promise) {
                heldMessage.set(message);
            }
        };
        var closeOnFailure = new ChannelInboundHandlerAdapter() {
            @Override
            public void exceptionCaught(ChannelHandlerContext context, Throwable failure) {
                context.close();
            }
        };
        var channel = new EmbeddedChannel(stalledTransport,
                new WriteTimeoutHandler(100, TimeUnit.MILLISECONDS), closeOnFailure);

        channel.writeOutbound(Unpooled.wrappedBuffer(new byte[] { 1 }));
        assertTrue(channel.isActive());

        channel.advanceTimeBy(100, TimeUnit.MILLISECONDS);
        channel.runScheduledPendingTasks();
        assertFalse(channel.isActive());

        ReferenceCountUtil.release(heldMessage.get());
        channel.finishAndReleaseAll();
    }
}
