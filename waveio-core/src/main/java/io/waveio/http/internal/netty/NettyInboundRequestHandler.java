package io.waveio.http.internal.netty;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.LastHttpContent;
import io.waveio.http.HttpResponse;
import io.waveio.http.HttpStatus;
import io.waveio.http.body.RequestBody;
import io.waveio.http.internal.body.InboundBodyPublisher;
import io.waveio.http.routing.Router;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

final class NettyInboundRequestHandler extends SimpleChannelInboundHandler<HttpObject> {
    private static final System.Logger LOG =
            System.getLogger(NettyInboundRequestHandler.class.getName());

    private final NettyRequestAdapter adapter;
    private final Router router;
    private final int maximumBodyBytes;
    private InboundState inbound;

    NettyInboundRequestHandler(Router router, int maximumBodyBytes) {
        this(router, maximumBodyBytes, null);
    }

    NettyInboundRequestHandler(Router router, int maximumBodyBytes,
            io.waveio.http.body.BodyCodec codec) {
        if (maximumBodyBytes < 0) {
            throw new IllegalArgumentException("maximumBodyBytes must not be negative");
        }
        this.router = router;
        this.maximumBodyBytes = maximumBodyBytes;
        adapter = new NettyRequestAdapter(codec);
    }

    @Override
    protected void channelRead0(ChannelHandlerContext context, HttpObject message) {
        if (message instanceof io.netty.handler.codec.http.HttpRequest request) {
            try {
                begin(context, request);
            } catch (IllegalArgumentException failure) {
                reject(context, request, failure);
            }
        }
        if (message instanceof HttpContent content) accept(context, content);
    }

    private void begin(ChannelHandlerContext context,
            io.netty.handler.codec.http.HttpRequest source) {
        if (inbound != null) throw new IllegalStateException("Overlapping HTTP requests");
        var request = adapter.adapt(context, source);
        boolean keepAlive = HttpUtil.isKeepAlive(source);
        long declaredLength = HttpUtil.getContentLength(source, -1);
        if (declaredLength > maximumBodyBytes) {
            inbound = Discarding.INSTANCE;
            context.fireChannelRead(new NettyInboundMessage.Rejected(request,
                    HttpResponse.status(HttpStatus.PAYLOAD_TOO_LARGE)
                            .body("Payload Too Large")));
            return;
        }
        if (HttpUtil.is100ContinueExpected(source)) {
            context.fireChannelRead(new NettyInboundMessage.ContinueExpected());
        }
        boolean streaming = router.match(request).map(Router.Match::streaming).orElse(false);
        if (streaming) {
            // Read suspension belongs to the exchange queue, which owns every reason for it; the
            // queue applies it synchronously while handling the message fired below.
            var publisher = new InboundBodyPublisher(maximumBodyBytes,
                    action -> execute(context, action), context::read, context::close);
            inbound = new Streaming(publisher);
            context.fireChannelRead(new NettyInboundMessage.StreamingStart(request, keepAlive,
                    publisher));
        } else {
            inbound = new Buffered(request, keepAlive);
        }
    }

    private void accept(ChannelHandlerContext context, HttpContent content) {
        var current = inbound;
        if (current == null) throw new IllegalStateException("HTTP content without request");
        if (current instanceof Buffered buffered) {
            if (!append(buffered.bytes, content.content())) {
                inbound = Discarding.INSTANCE;
                context.fireChannelRead(new NettyInboundMessage.Rejected(buffered.request,
                        HttpResponse.status(HttpStatus.PAYLOAD_TOO_LARGE)
                                .body("Payload Too Large")));
                return;
            }
            if (content instanceof LastHttpContent) {
                inbound = null;
                context.fireChannelRead(new NettyInboundMessage.Buffered(
                        buffered.request.withBody(RequestBody.of(buffered.bytes.toByteArray())),
                        buffered.keepAlive));
            }
        } else if (current instanceof Streaming streaming) {
            int readable = content.content().readableBytes();
            if (readable > maximumBodyBytes - streaming.received) {
                var failure = new InboundBodyPublisher.BodyTooLargeException(maximumBodyBytes);
                inbound = Discarding.INSTANCE;
                context.fireChannelRead(new NettyInboundMessage.StreamingFailure(
                        streaming.publisher, failure));
                streaming.publisher.fail(failure);
                return;
            }
            streaming.received += readable;
            if (readable > 0 && !streaming.publisher.offer(detached(content.content()))) {
                var failure = streaming.publisher.failure().orElse(null);
                if (failure instanceof InboundBodyPublisher.BodyTooLargeException) {
                    inbound = Discarding.INSTANCE;
                    context.fireChannelRead(new NettyInboundMessage.StreamingFailure(
                            streaming.publisher, failure));
                } else {
                    LOG.log(System.Logger.Level.DEBUG,
                            "Closing connection from {0}: the request body stream is no longer"
                            + " accepting chunks", context.channel().remoteAddress());
                    context.close();
                }
                return;
            }
            if (content instanceof LastHttpContent) {
                inbound = null;
                context.fireChannelRead(new NettyInboundMessage.StreamingComplete(
                        streaming.publisher));
                streaming.publisher.complete();
            }
        } else if (content instanceof LastHttpContent) {
            inbound = null;
        }
    }

    private boolean append(ByteArrayOutputStream target, ByteBuf source) {
        int readable = source.readableBytes();
        if (readable > maximumBodyBytes - target.size()) return false;
        var bytes = new byte[readable];
        source.getBytes(source.readerIndex(), bytes);
        target.writeBytes(bytes);
        return true;
    }

    private static ByteBuffer detached(ByteBuf source) {
        var bytes = new byte[source.readableBytes()];
        source.getBytes(source.readerIndex(), bytes);
        return ByteBuffer.wrap(bytes);
    }

    private static void execute(ChannelHandlerContext context, Runnable action) {
        if (context.executor().inEventLoop()) action.run();
        else context.executor().execute(action);
    }

    private void reject(ChannelHandlerContext context,
            io.netty.handler.codec.http.HttpRequest source, IllegalArgumentException failure) {
        inbound = Discarding.INSTANCE;
        var request = adapter.rejected(context, source);
        context.fireChannelRead(new NettyInboundMessage.Rejected(request,
                HttpResponse.status(HttpStatus.BAD_REQUEST)
                        .header("content-type", "text/plain; charset=utf-8")
                        .body(failure.getMessage() == null ? "Bad Request" : failure.getMessage())));
    }

    @Override
    public void channelInactive(ChannelHandlerContext context) throws Exception {
        if (inbound instanceof Streaming streaming) streaming.publisher.disconnect();
        inbound = null;
        context.fireChannelInactive();
    }

    private sealed interface InboundState permits Buffered, Streaming, Discarding {}
    private static final class Buffered implements InboundState {
        private final io.waveio.http.HttpRequest request;
        private final boolean keepAlive;
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private Buffered(io.waveio.http.HttpRequest request, boolean keepAlive) {
            this.request = request;
            this.keepAlive = keepAlive;
        }
    }
    private static final class Streaming implements InboundState {
        private final InboundBodyPublisher publisher;
        private int received;
        private Streaming(InboundBodyPublisher publisher) { this.publisher = publisher; }
    }
    private enum Discarding implements InboundState { INSTANCE }
}
