package io.wavejava.wave.netty;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.DefaultHttp2HeadersFrame;
import io.netty.handler.codec.http2.DefaultHttp2ResetFrame;
import io.netty.handler.codec.http2.Http2Error;
import io.netty.handler.codec.http2.Http2DataFrame;
import io.netty.handler.codec.http2.Http2FrameCodecBuilder;
import io.netty.handler.codec.http2.Http2GoAwayFrame;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.handler.codec.http2.Http2HeadersFrame;
import io.netty.handler.codec.http2.Http2MultiplexHandler;
import io.netty.handler.codec.http2.Http2ResetFrame;
import io.netty.handler.codec.http2.Http2StreamChannelBootstrap;
import io.netty.handler.ssl.ApplicationProtocolConfig;
import io.netty.handler.ssl.ApplicationProtocolNames;
import io.netty.handler.ssl.ApplicationProtocolNegotiationHandler;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.DefaultThreadFactory;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Test-only TLS/ALPN HTTP/2 peer that sends raw stream frames without HTTP/1 conversion.
 *
 * <p>It intentionally permits invalid outbound header combinations so transport conformance tests
 * can prove that the server rejects them before application dispatch. The fixture owns one event
 * loop and one parent channel, exposes no production API, and has finite waits at every boundary.</p>
 */
public final class TestHttp2Peer implements AutoCloseable {
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(5);

    private final NioEventLoopGroup group;
    private final CompletableFuture<Void> ready = new CompletableFuture<>();
    private final CompletableFuture<GoAway> goAway = new CompletableFuture<>();
    private final CompletableFuture<Void> parentClosed = new CompletableFuture<>();
    private Channel parent;

    private TestHttp2Peer(Path trustCertificate) {
        try {
            group = new NioEventLoopGroup(1, new DefaultThreadFactory("wave-test-h2-peer", true));
            var tls = SslContextBuilder.forClient()
                    .trustManager(Objects.requireNonNull(trustCertificate, "trustCertificate").toFile())
                    .endpointIdentificationAlgorithm("HTTPS")
                    .applicationProtocolConfig(new ApplicationProtocolConfig(
                            ApplicationProtocolConfig.Protocol.ALPN,
                            ApplicationProtocolConfig.SelectorFailureBehavior.NO_ADVERTISE,
                            ApplicationProtocolConfig.SelectedListenerFailureBehavior.ACCEPT,
                            ApplicationProtocolNames.HTTP_2))
                    .build();
            bootstrap(tls);
        } catch (Exception failure) {
            throw new IllegalStateException("Could not construct raw HTTP/2 peer", failure);
        }
    }

    /** Opens one loopback TLS connection and requires ALPN HTTP/2. */
    public static TestHttp2Peer connect(int port, Path trustCertificate) throws Exception {
        var peer = new TestHttp2Peer(trustCertificate);
        try {
            peer.parent = peer.bootstrap.connect("127.0.0.1", port).sync().channel();
            peer.parent.closeFuture().addListener(ignored -> peer.parentClosed.complete(null));
            peer.ready.get(DEFAULT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            return peer;
        } catch (Exception failure) {
            peer.close();
            throw failure;
        }
    }

    private Bootstrap bootstrap;

    private void bootstrap(SslContext tls) {
        bootstrap = new Bootstrap()
                .group(group)
                .channel(NioSocketChannel.class)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel channel) {
                        channel.pipeline().addLast(tls.newHandler(channel.alloc(), "127.0.0.1", 443));
                        channel.pipeline().addLast(new ApplicationProtocolNegotiationHandler("wave-no-alpn") {
                            @Override
                            protected void configurePipeline(ChannelHandlerContext context, String protocol) {
                                if (!ApplicationProtocolNames.HTTP_2.equals(protocol)) {
                                    ready.completeExceptionally(new IllegalStateException(
                                            "Test peer required HTTP/2 ALPN but negotiated " + protocol));
                                    context.close();
                                    return;
                                }
                                context.pipeline().addLast(Http2FrameCodecBuilder.forClient()
                                        .validateHeaders(false)
                                        .validateRequiredPseudoHeaders(false)
                                        .build());
                                context.pipeline().addLast(new ParentObserver(goAway));
                                context.pipeline().addLast(new Http2MultiplexHandler(new ChannelInboundHandlerAdapter()));
                                ready.complete(null);
                            }

                            @Override
                            public void exceptionCaught(ChannelHandlerContext context, Throwable failure) {
                                ready.completeExceptionally(failure);
                                context.close();
                            }
                        });
                    }
                });
    }

    /** Opens a raw bidirectional stream and sends one headers frame. */
    public RawStream open(Http2Headers headers, boolean endStream) throws Exception {
        Objects.requireNonNull(headers, "headers");
        if (parent == null || !parent.isActive()) {
            throw new IllegalStateException("HTTP/2 peer is not connected");
        }
        var streamFuture = new CompletableFuture<RawStream>();
        new Http2StreamChannelBootstrap(parent)
                .handler(new ChannelInitializer<Channel>() {
                    @Override
                    protected void initChannel(Channel channel) {
                        channel.pipeline().addLast(new RawStreamObserver(streamFuture));
                    }
                })
                .open()
                .addListener(opened -> {
                    if (!opened.isSuccess()) {
                        streamFuture.completeExceptionally(opened.cause());
                        return;
                    }
                    var channel = (Channel) opened.getNow();
                    var stream = streamFuture.getNow(null);
                    if (stream == null) {
                        streamFuture.completeExceptionally(new IllegalStateException("stream observer was not installed"));
                        channel.close();
                        return;
                    }
                    channel.writeAndFlush(new DefaultHttp2HeadersFrame(headers, endStream)).addListener(written -> {
                        if (!written.isSuccess()) {
                            stream.fail(written.cause());
                        }
                    });
                });
        return streamFuture.get(DEFAULT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
    }

    /** Returns a new header set with valid request pseudo-headers in protocol order. */
    public static Http2Headers requestHeaders(String authority, String path) {
        return new DefaultHttp2Headers(false)
                .method("GET")
                .scheme("https")
                .authority(Objects.requireNonNull(authority, "authority"))
                .path(Objects.requireNonNull(path, "path"));
    }

    /** Waits for a peer GOAWAY frame, if a lifecycle test expects one. */
    public GoAway awaitGoAway(Duration timeout) throws Exception {
        return goAway.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    /** Waits for the server to finish the fatal connection-close sequence after a GOAWAY. */
    public void awaitParentClose(Duration timeout) throws Exception {
        parentClosed.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    @Override
    public void close() {
        if (parent != null) {
            parent.close().awaitUninterruptibly(DEFAULT_TIMEOUT.toMillis());
        }
        group.shutdownGracefully(0, DEFAULT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS).awaitUninterruptibly();
    }

    /** Compact immutable response snapshot captured by one raw stream. */
    public record Response(int status, byte[] body) {
        public Response {
            body = Objects.requireNonNull(body, "body").clone();
        }

        @Override
        public byte[] body() {
            return body.clone();
        }

        /** Returns the response payload as UTF-8 for concise protocol assertions. */
        public String text() {
            return new String(body, StandardCharsets.UTF_8);
        }
    }

    /** Compact GOAWAY metadata retained without keeping Netty's reference-counted debug buffer. */
    public record GoAway(long errorCode, int lastStreamId) {
    }

    /** One raw stream's terminal protocol observations. */
    public static final class RawStream {
        private final Channel channel;
        private final CompletableFuture<Response> response = new CompletableFuture<>();
        private final CompletableFuture<Long> reset = new CompletableFuture<>();
        private final CompletableFuture<Void> closed = new CompletableFuture<>();
        private final ByteArrayOutputStream body = new ByteArrayOutputStream();
        private int status = -1;

        private RawStream(Channel channel) {
            this.channel = channel;
        }

        /** Returns a future that completes after a final response END_STREAM. */
        public CompletableFuture<Response> response() {
            return response;
        }

        /** Returns a future that completes when the peer resets this stream. */
        public CompletableFuture<Long> reset() {
            return reset;
        }

        /** Returns a future that completes when the local stream channel is inactive. */
        public CompletableFuture<Void> closed() {
            return closed;
        }

        /**
         * Stops this child stream from consuming more inbound frames.
         *
         * <p>HTTP/2 flow control returns receive-window credit only after child consumption, so
         * this gives a deterministic slow-reader state without pausing the parent connection or
         * unrelated streams. It is deliberately test-only and waits for the EventLoop mutation to
         * become visible before returning.</p>
         */
        public void pauseReads() throws Exception {
            var applied = new CompletableFuture<Void>();
            channel.eventLoop().execute(() -> {
                channel.config().setAutoRead(false);
                applied.complete(null);
            });
            applied.get(DEFAULT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        }

        /** Sends an explicit RST_STREAM(CANCEL) for a frame-rate conformance test. */
        public void sendReset() throws Exception {
            var written = new CompletableFuture<Void>();
            channel.eventLoop().execute(() -> channel.writeAndFlush(new DefaultHttp2ResetFrame(Http2Error.CANCEL))
                    .addListener(future -> {
                        if (future.isSuccess()) {
                            written.complete(null);
                        } else {
                            written.completeExceptionally(future.cause());
                        }
                    }));
            written.get(DEFAULT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        }

        /** Waits for response, reset, or physical stream close without assuming which error form a peer uses. */
        public void awaitTerminal(Duration timeout) throws Exception {
            CompletableFuture.anyOf(response, reset, closed).get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        }

        private void receiveHeaders(Http2HeadersFrame frame) {
            var value = frame.headers().status();
            if (value != null) {
                if (status >= 0) {
                    fail(new IllegalStateException("received multiple final HTTP/2 response heads"));
                    return;
                }
                try {
                    status = Integer.parseInt(value.toString());
                } catch (NumberFormatException malformed) {
                    fail(malformed);
                    return;
                }
            }
            if (frame.isEndStream()) {
                completeResponse();
            }
        }

        private void receiveData(Http2DataFrame frame) {
            ByteBuf content = frame.content();
            var bytes = new byte[content.readableBytes()];
            content.getBytes(content.readerIndex(), bytes);
            body.writeBytes(bytes);
            if (frame.isEndStream()) {
                completeResponse();
            }
        }

        private void completeResponse() {
            if (status < 0) {
                fail(new IllegalStateException("HTTP/2 response ended without a final status"));
                return;
            }
            response.complete(new Response(status, body.toByteArray()));
        }

        private void reset(long errorCode) {
            reset.complete(errorCode);
        }

        private void fail(Throwable failure) {
            response.completeExceptionally(failure);
            reset.completeExceptionally(failure);
        }

        private void markClosed() {
            closed.complete(null);
        }
    }

    private static final class ParentObserver extends ChannelInboundHandlerAdapter {
        private final CompletableFuture<GoAway> goAway;

        private ParentObserver(CompletableFuture<GoAway> goAway) {
            this.goAway = goAway;
        }

        @Override
        public void channelRead(ChannelHandlerContext context, Object message) {
            if (message instanceof Http2GoAwayFrame frame) {
                goAway.complete(new GoAway(frame.errorCode(), frame.lastStreamId()));
                ReferenceCountUtil.release(message);
                return;
            }
            context.fireChannelRead(message);
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext context, Throwable failure) {
            goAway.completeExceptionally(failure);
            context.close();
        }

    }

    private static final class RawStreamObserver extends ChannelInboundHandlerAdapter {
        private final CompletableFuture<RawStream> owner;
        private RawStream stream;

        private RawStreamObserver(CompletableFuture<RawStream> owner) {
            this.owner = owner;
        }

        @Override
        public void handlerAdded(ChannelHandlerContext context) {
            stream = new RawStream(context.channel());
            owner.complete(stream);
        }

        @Override
        public void channelRead(ChannelHandlerContext context, Object message) {
            try {
                if (message instanceof Http2HeadersFrame headers) {
                    stream.receiveHeaders(headers);
                    return;
                }
                if (message instanceof Http2DataFrame data) {
                    stream.receiveData(data);
                    return;
                }
                stream.fail(new IllegalStateException("unexpected HTTP/2 stream frame: " + message.getClass().getName()));
            } finally {
                ReferenceCountUtil.release(message);
            }
        }

        @Override
        public void userEventTriggered(ChannelHandlerContext context, Object event) {
            if (event instanceof Http2ResetFrame reset) {
                stream.reset(reset.errorCode());
                return;
            }
            context.fireUserEventTriggered(event);
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext context, Throwable failure) {
            stream.fail(failure);
            context.close();
        }

        @Override
        public void channelInactive(ChannelHandlerContext context) {
            stream.markClosed();
            context.fireChannelInactive();
        }
    }
}
