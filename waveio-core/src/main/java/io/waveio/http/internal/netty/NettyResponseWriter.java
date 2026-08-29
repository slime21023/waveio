package io.waveio.http.internal.netty;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.concurrent.Future;
import io.waveio.http.HttpMethod;
import io.waveio.http.HttpResponse;
import io.waveio.http.HttpStatus;
import io.waveio.http.body.ResponseBody;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

final class NettyResponseWriter {
    private static final System.Logger LOG =
            System.getLogger(NettyResponseWriter.class.getName());

    private final java.time.Duration bodyStallTimeout;
    private final io.waveio.http.body.BodyCodec bodyCodec;

    NettyResponseWriter() {
        this(java.time.Duration.ofSeconds(30), null);
    }

    NettyResponseWriter(java.time.Duration bodyStallTimeout) {
        this(bodyStallTimeout, null);
    }

    NettyResponseWriter(java.time.Duration bodyStallTimeout,
            io.waveio.http.body.BodyCodec bodyCodec) {
        if (bodyStallTimeout == null || bodyStallTimeout.isNegative()
                || bodyStallTimeout.isZero()) {
            throw new IllegalArgumentException("bodyStallTimeout must be positive");
        }
        this.bodyStallTimeout = bodyStallTimeout;
        this.bodyCodec = bodyCodec;
    }

    WriteHandle write(ChannelHandlerContext context, HttpResponse source,
            boolean keepAlive, Consumer<WriteResult> completion) {
        return write(context, HttpMethod.GET, source, keepAlive, completion);
    }

    WriteHandle write(ChannelHandlerContext context, HttpMethod requestMethod, HttpResponse source,
            boolean keepAlive, Consumer<WriteResult> completion) {
        var handle = new WriteHandle();
        write(context, requestMethod, source, keepAlive, completion, handle);
        return handle;
    }

    void write(ChannelHandlerContext context, HttpMethod requestMethod, HttpResponse source,
            boolean keepAlive, Consumer<WriteResult> completion, WriteHandle handle) {
        source = encodeValueBody(source);
        if (responseBodyForbidden(source)) {
            writeWithoutBody(context, source, keepAlive, completion, null);
            return;
        }
        if (requestMethod == HttpMethod.HEAD) {
            Long representationLength = representationLength(source.body());
            writeWithoutBody(context, source, keepAlive, completion, representationLength);
            return;
        }
        if (source.body() instanceof ResponseBody.Bytes bytes) {
            writeBytes(context, source, bytes.value(), keepAlive, completion);
        } else if (source.body() instanceof ResponseBody.Stream stream) {
            writeStream(context, source, stream, keepAlive, completion, handle);
        } else {
            LOG.log(System.Logger.Level.DEBUG,
                    "Closing connection from {0}: unsupported response body type {1}",
                    context.channel().remoteAddress(),
                    source.body() == null ? null : source.body().getClass().getName());
            completion.accept(new WriteResult.Failure(
                    new IllegalStateException("Unsupported response body")));
            context.close();
        }
    }

    /**
     * Resolves a deferred value body into bytes before any framing decision, so HEAD, the
     * body-forbidden statuses and content-length handling stay identical to a byte body. Encoding
     * failures become a 500 rather than a malformed response.
     */
    private HttpResponse encodeValueBody(HttpResponse source) {
        if (!(source.body() instanceof ResponseBody.Value value)) return source;
        if (bodyCodec == null) {
            LOG.log(System.Logger.Level.WARNING,
                    "Response used HttpResponse.value(...) but no BodyCodec is configured;"
                    + " call HttpServerBuilder.bodyCodec(...) to enable value bodies");
            return errorResponse();
        }
        byte[] encoded;
        try {
            encoded = bodyCodec.encode(value.value());
            if (encoded == null) throw new IllegalStateException("BodyCodec returned null bytes");
        } catch (Throwable failure) {
            LOG.log(System.Logger.Level.DEBUG,
                    () -> "Encoding the response value with " + bodyCodec.getClass().getName()
                            + " failed", failure);
            return errorResponse();
        }
        var builder = HttpResponse.status(source.status());
        source.headers().asMap().forEach((name, values) ->
                values.forEach(header -> builder.header(name, header)));
        if (source.headers().first("content-type").isEmpty()) {
            builder.header("content-type", bodyCodec.contentType());
        }
        return builder.body(encoded);
    }

    private static HttpResponse errorResponse() {
        return HttpResponse.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .header("content-type", "text/plain; charset=utf-8")
                .body("Internal Server Error");
    }

    private void writeBytes(ChannelHandlerContext context, HttpResponse source,
            byte[] body, boolean keepAlive, Consumer<WriteResult> completion) {
        var response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1,
                status(source), Unpooled.wrappedBuffer(body));
        copyHeaders(source, response);
        removeFramingHeaders(response);
        response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, body.length);
        HttpUtil.setKeepAlive(response, keepAlive);
        var future = context.writeAndFlush(response);
        future.addListener(result -> complete(result, completion));
        if (!keepAlive) future.addListener(ChannelFutureListener.CLOSE);
    }

    private void writeStream(ChannelHandlerContext context, HttpResponse source,
            ResponseBody.Stream body, boolean keepAlive, Consumer<WriteResult> completion,
            WriteHandle handle) {
        var response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, status(source));
        copyHeaders(source, response);
        removeFramingHeaders(response);
        if (body.contentLength().isPresent()) {
            response.headers().set(HttpHeaderNames.CONTENT_LENGTH, body.contentLength().getAsLong());
        } else {
            HttpUtil.setTransferEncodingChunked(response, true);
        }
        HttpUtil.setKeepAlive(response, keepAlive);
        context.writeAndFlush(response).addListener(headerFuture -> {
            if (handle.cancelled()) return;
            if (!headerFuture.isSuccess()) {
                LOG.log(System.Logger.Level.DEBUG,
                        () -> "Closing connection from " + context.channel().remoteAddress()
                                + ": writing the response headers failed",
                        failureOf(headerFuture));
                completion.accept(new WriteResult.Failure(failureOf(headerFuture)));
                context.close();
                return;
            }
            var subscriber = new NettyBodySubscriber(
                    context, keepAlive, body.contentLength(), bodyStallTimeout, completion);
            handle.attach(subscriber);
            if (handle.cancelled()) return;
            // Armed before subscribing: a publisher that never signals onSubscribe, or throws out
            // of subscribe, must still lose the connection rather than pin the exchange.
            subscriber.beginBody();
            try {
                body.publisher().subscribe(subscriber);
            } catch (Throwable failure) {
                subscriber.onError(failure);
            }
        });
    }

    private void writeWithoutBody(ChannelHandlerContext context, HttpResponse source,
            boolean keepAlive, Consumer<WriteResult> completion, Long representationLength) {
        var response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1,
                status(source), Unpooled.EMPTY_BUFFER);
        copyHeaders(source, response);
        removeFramingHeaders(response);
        if (representationLength != null) {
            response.headers().set(HttpHeaderNames.CONTENT_LENGTH, representationLength);
        }
        HttpUtil.setKeepAlive(response, keepAlive);
        var future = context.writeAndFlush(response);
        future.addListener(result -> complete(result, completion));
        if (!keepAlive) future.addListener(ChannelFutureListener.CLOSE);
    }

    private boolean responseBodyForbidden(HttpResponse source) {
        int code = source.status().code();
        return code >= 100 && code < 200 || code == 204 || code == 304;
    }

    private Long representationLength(ResponseBody body) {
        if (body instanceof ResponseBody.Bytes bytes) return (long) bytes.value().length;
        if (body instanceof ResponseBody.Stream stream && stream.contentLength().isPresent()) {
            return stream.contentLength().getAsLong();
        }
        return null;
    }

    private void removeFramingHeaders(io.netty.handler.codec.http.HttpResponse response) {
        response.headers().remove(HttpHeaderNames.CONTENT_LENGTH);
        response.headers().remove(HttpHeaderNames.TRANSFER_ENCODING);
    }

    private HttpResponseStatus status(HttpResponse source) {
        return new HttpResponseStatus(source.status().code(), source.status().reason());
    }

    private void copyHeaders(HttpResponse source,
            io.netty.handler.codec.http.HttpResponse target) {
        source.headers().asMap().forEach((name, values) ->
                values.forEach(value -> target.headers().add(name, value)));
    }

    private static void complete(Future<?> future, Consumer<WriteResult> completion) {
        completion.accept(future.isSuccess()
                ? new WriteResult.Success()
                : new WriteResult.Failure(failureOf(future)));
    }

    private static Throwable failureOf(Future<?> future) {
        return future.cause() != null ? future.cause()
                : new IllegalStateException("HTTP response write failed");
    }

    static final class WriteHandle {
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private volatile NettyBodySubscriber subscriber;

        void attach(NettyBodySubscriber value) {
            subscriber = value;
            if (cancelled.get()) value.cancel();
        }

        void cancel() {
            if (!cancelled.compareAndSet(false, true)) return;
            var current = subscriber;
            if (current != null) current.cancel();
        }

        boolean cancelled() { return cancelled.get(); }
    }
}
