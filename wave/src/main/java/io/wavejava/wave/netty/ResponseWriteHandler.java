package io.wavejava.wave.netty;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.LastHttpContent;
import java.util.Objects;

/**
 * Event-loop-only bridge that writes an already prepared HTTP response.
 *
 * <p>It deliberately accepts {@link PreparedResponse}, never a mutable framework {@code Response}:
 * serialization and application work must have completed before this boundary is crossed.</p>
 */
final class ResponseWriteHandler {
    private ResponseWriteHandler() {
    }

    static ChannelFuture write(ChannelHandlerContext context, PreparedResponse response) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(response, "response");
        return context.writeAndFlush(response.toNettyResponse());
    }

    /** Writes just the response head before a Flow-controlled chunked entity begins. */
    static ChannelFuture writeStreamHeaders(ChannelHandlerContext context, PreparedResponse response) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(response, "response");
        return context.writeAndFlush(response.toNettyStreamHeaders());
    }

    /**
     * Writes one framework-owned Flow buffer as HTTP content.
     *
     * <p>{@link FlowBridge} has already copied the publisher buffer before this call. Keeping the
     * resulting {@code ByteBuf} exclusively inside this package avoids a second peak-size copy
     * while preserving the public API's ownership boundary.</p>
     */
    static ChannelFuture writeStreamChunk(ChannelHandlerContext context, byte[] bytes) {
        Objects.requireNonNull(context, "context");
        return context.writeAndFlush(new DefaultHttpContent(Unpooled.wrappedBuffer(
                Objects.requireNonNull(bytes, "bytes"))));
    }

    /** Writes the terminal HTTP chunk after upstream Flow completion. */
    static ChannelFuture writeStreamEnd(ChannelHandlerContext context) {
        Objects.requireNonNull(context, "context");
        return context.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT);
    }
}
