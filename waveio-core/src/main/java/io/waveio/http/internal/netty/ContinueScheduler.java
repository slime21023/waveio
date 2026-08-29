package io.waveio.http.internal.netty;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpVersion;
import java.nio.charset.StandardCharsets;

/**
 * Emits {@code 100 Continue} in response order and below the server codec.
 *
 * <p>Two constraints meet here. An interim response must not overtake the final response of an
 * earlier pipelined request, so it waits for the exchanges queued ahead of its own request. And
 * {@link HttpServerCodec}'s encoder consumes one queued request method per encoded response,
 * informational ones included, so an interim response written through it would frame the following
 * final response against the wrong request — a pipelined {@code HEAD} would silently strip the
 * previous response's body. Writing raw bytes from below the codec keeps that queue aligned.
 */
final class ContinueScheduler {
    private static final byte[] CONTINUE_BYTES =
            "HTTP/1.1 100 Continue\r\n\r\n".getBytes(StandardCharsets.US_ASCII);

    private boolean expected;
    private Exchange after;

    /**
     * Records an expectation announced by a request head.
     *
     * <p>One slot suffices: HTTP/1.1 decodes a connection's requests strictly in order, so a later
     * request head cannot be decoded before the previous request's body has arrived. An overwritten
     * expectation therefore always belongs to a request whose body is already present, which
     * RFC 7231 section 5.1.1 permits answering without an interim response.
     */
    void expect(Exchange lastQueued) {
        expected = true;
        after = lastQueued;
    }

    void flushIfReady(ChannelHandlerContext context, ExchangeQueue queue, boolean writeInProgress) {
        if (!expected || writeInProgress) return;
        if (after != null && queue.contains(after)) return;
        expected = false;
        after = null;
        write(context);
    }

    void clear() {
        expected = false;
        after = null;
    }

    private static void write(ChannelHandlerContext context) {
        var codec = context.pipeline().context(HttpServerCodec.class);
        if (codec != null) {
            codec.writeAndFlush(Unpooled.wrappedBuffer(CONTINUE_BYTES));
            return;
        }
        context.writeAndFlush(new DefaultFullHttpResponse(HttpVersion.HTTP_1_1,
                HttpResponseStatus.CONTINUE, Unpooled.EMPTY_BUFFER));
    }
}
