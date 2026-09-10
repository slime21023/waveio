package io.wavejava.wave.netty;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpServerExpectContinueHandler;
import io.netty.handler.codec.http.HttpUtil;

/**
 * Suppresses {@code 100 Continue} for a request whose declared body already exceeds Wave's body
 * budget, while still forwarding its head to the sequenced dispatcher for an ordered {@code 413}.
 */
final class LimitAwareExpectContinueHandler extends HttpServerExpectContinueHandler {
    private final long maximumRequestBodyBytes;

    LimitAwareExpectContinueHandler(long maximumRequestBodyBytes) {
        this.maximumRequestBodyBytes = maximumRequestBodyBytes;
    }

    @Override
    public void channelRead(ChannelHandlerContext context, Object message) throws Exception {
        if (message instanceof HttpRequest request
                && HttpUtil.is100ContinueExpected(request)
                && declaredContentLengthExceedsLimit(request)) {
            // Do not call super: it would emit 100 before RequestDispatchHandler can choose 413.
            request.headers().remove(HttpHeaderNames.EXPECT);
            context.fireChannelRead(message);
            return;
        }
        super.channelRead(context, message);
    }

    private boolean declaredContentLengthExceedsLimit(HttpRequest request) {
        try {
            return HttpUtil.getContentLength(request, -1) > maximumRequestBodyBytes;
        } catch (RuntimeException ignored) {
            // Decoder-result validation remains the normal malformed-header path.
            return false;
        }
    }
}
