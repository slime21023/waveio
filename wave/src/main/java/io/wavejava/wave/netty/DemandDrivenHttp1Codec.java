package io.wavejava.wave.netty;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpMessage;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpRequestDecoder;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseEncoder;
import io.netty.handler.codec.http.HttpStatusClass;
import io.netty.handler.codec.http.LastHttpContent;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;

/**
 * Split HTTP/1.1 codec used only for Flow request bodies.
 *
 * <p>Its decoder emits at most one {@code HttpObject} for each socket or explicit manual read.
 * Any further coalesced wire chunks remain in Netty's bounded cumulation until the current body
 * publisher asks the transport for its next item. The encoder mirrors the method queue semantics
 * of Netty's {@code HttpServerCodec}, including HEAD and CONNECT framing.</p>
 */
final class DemandDrivenHttp1Codec {
    private final Queue<HttpMethod> requestMethods = new ArrayDeque<>();
    private final DemandDrivenHttpRequestDecoder requestDecoder;
    private final DemandDrivenHttpResponseEncoder responseEncoder;
    private boolean mustCloseAfterResponse;

    DemandDrivenHttp1Codec(int maximumRequestLineBytes, int maximumRequestHeaderBytes, int maximumChunkBytes) {
        requestDecoder = new DemandDrivenHttpRequestDecoder(
                this, maximumRequestLineBytes, maximumRequestHeaderBytes, maximumChunkBytes);
        responseEncoder = new DemandDrivenHttpResponseEncoder(this);
    }

    DemandDrivenHttpRequestDecoder requestDecoder() {
        return requestDecoder;
    }

    DemandDrivenHttpResponseEncoder responseEncoder() {
        return responseEncoder;
    }

    void recordRequestMethod(HttpMethod method) {
        requestMethods.add(method);
    }

    HttpMethod pollRequestMethod() {
        return requestMethods.poll();
    }

    void requireCloseAfterResponse() {
        mustCloseAfterResponse = true;
    }

    boolean mustCloseAfterResponse() {
        return mustCloseAfterResponse;
    }

    void clearCloseAfterResponse() {
        mustCloseAfterResponse = false;
    }

    /** Manual decoder hook; it is called only from the connection event loop. */
    static final class DemandDrivenHttpRequestDecoder extends HttpRequestDecoder {
        private final DemandDrivenHttp1Codec owner;

        DemandDrivenHttpRequestDecoder(
                DemandDrivenHttp1Codec owner,
                int maximumRequestLineBytes,
                int maximumRequestHeaderBytes,
                int maximumChunkBytes) {
            super(maximumRequestLineBytes, maximumRequestHeaderBytes, maximumChunkBytes);
            this.owner = owner;
            setSingleDecode(true);
        }

        /**
         * Decodes exactly one object already retained by Netty without requesting more socket
         * bytes. The empty buffer is only a trigger; {@link #internalBuffer()} owns the cumulation.
         */
        void decodePending(ChannelHandlerContext context) {
            if (actualReadableBytes() == 0) {
                return;
            }
            try {
                channelRead(context, Unpooled.EMPTY_BUFFER);
            } catch (Exception failure) {
                context.fireExceptionCaught(failure);
            }
        }

        @Override
        protected void decode(ChannelHandlerContext context, io.netty.buffer.ByteBuf input, List<Object> output)
                throws Exception {
            var before = output.size();
            super.decode(context, input, output);
            for (var index = before; index < output.size(); index++) {
                var decoded = output.get(index);
                if (decoded instanceof HttpRequest request) {
                    owner.recordRequestMethod(request.method());
                }
            }
        }

        @Override
        protected void handleTransferEncodingChunkedWithContentLength(HttpMessage message) {
            super.handleTransferEncodingChunkedWithContentLength(message);
            owner.requireCloseAfterResponse();
        }
    }

    /** Response half of {@link io.netty.handler.codec.http.HttpServerCodec} with a split decoder. */
    static final class DemandDrivenHttpResponseEncoder extends HttpResponseEncoder {
        private final DemandDrivenHttp1Codec owner;
        private HttpMethod method;

        DemandDrivenHttpResponseEncoder(DemandDrivenHttp1Codec owner) {
            this.owner = owner;
        }

        @Override
        public void write(ChannelHandlerContext context, Object message, ChannelPromise promise) throws Exception {
            if (owner.mustCloseAfterResponse() && message instanceof LastHttpContent) {
                owner.clearCloseAfterResponse();
                promise = promise.unvoid().addListener(ChannelFutureListener.CLOSE);
            }
            super.write(context, message, promise);
        }

        @Override
        protected boolean isContentAlwaysEmpty(HttpResponse response) {
            if (response.status().codeClass() == HttpStatusClass.INFORMATIONAL) {
                return super.isContentAlwaysEmpty(response);
            }
            method = owner.pollRequestMethod();
            return HttpMethod.HEAD.equals(method) || super.isContentAlwaysEmpty(response);
        }

        @Override
        protected void sanitizeHeadersBeforeEncode(HttpResponse response, boolean isAlwaysEmpty) {
            if (!isAlwaysEmpty && HttpMethod.CONNECT.equals(method)
                    && response.status().codeClass() == HttpStatusClass.SUCCESS) {
                response.headers().remove(HttpHeaderNames.TRANSFER_ENCODING);
                return;
            }
            super.sanitizeHeadersBeforeEncode(response, isAlwaysEmpty);
        }
    }
}
