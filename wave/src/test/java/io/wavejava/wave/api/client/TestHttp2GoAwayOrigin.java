package io.wavejava.wave.api.client;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http2.DefaultHttp2GoAwayFrame;
import io.netty.handler.codec.http2.Http2Error;
import io.netty.handler.codec.http2.Http2FrameCodecBuilder;
import io.netty.handler.codec.http2.Http2MultiplexHandler;
import io.netty.handler.codec.http2.Http2StreamFrameToHttpObjectCodec;
import io.netty.handler.ssl.ApplicationProtocolConfig;
import io.netty.handler.ssl.ApplicationProtocolNames;
import io.netty.handler.ssl.ApplicationProtocolNegotiationHandler;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.util.concurrent.DefaultThreadFactory;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Test-scope TLS/ALPN HTTP/2 origin that sends GOAWAY before it completes its first response.
 *
 * <p>Writing GOAWAY before END_STREAM makes the ordering deterministic. A test can optionally
 * hold the first accepted stream after that frame, proving that a client both drains it and opens
 * a replacement parent for later work. The fixture accepts only two parents, owns no reader
 * thread, and gives every observable wait a finite bound.</p>
 */
final class TestHttp2GoAwayOrigin implements AutoCloseable {
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private final NioEventLoopGroup bossGroup = new NioEventLoopGroup(
            1, new DefaultThreadFactory("wave-test-goaway-origin-boss", true));
    private final NioEventLoopGroup workerGroup = new NioEventLoopGroup(
            1, new DefaultThreadFactory("wave-test-goaway-origin-worker", true));
    private final boolean holdFirstResponse;
    private final AtomicInteger parentConnections = new AtomicInteger();
    private final AtomicBoolean firstGoAwayWritten = new AtomicBoolean();
    private final CountDownLatch goAwayWritten = new CountDownLatch(1);
    private final CountDownLatch firstResponseHeld = new CountDownLatch(1);
    private final CountDownLatch secondParentConnected = new CountDownLatch(1);
    private final AtomicReference<HeldResponse> heldFirstResponse = new AtomicReference<>();
    private final AtomicReference<Throwable> failure = new AtomicReference<>();
    private final Channel listener;

    private TestHttp2GoAwayOrigin(Path certificateChain, Path privateKey, boolean holdFirstResponse) throws Exception {
        this.holdFirstResponse = holdFirstResponse;
        var tls = SslContextBuilder.forServer(
                        Objects.requireNonNull(certificateChain, "certificateChain").toFile(),
                        Objects.requireNonNull(privateKey, "privateKey").toFile())
                .applicationProtocolConfig(new ApplicationProtocolConfig(
                        ApplicationProtocolConfig.Protocol.ALPN,
                        ApplicationProtocolConfig.SelectorFailureBehavior.NO_ADVERTISE,
                        ApplicationProtocolConfig.SelectedListenerFailureBehavior.ACCEPT,
                        ApplicationProtocolNames.HTTP_2))
                .build();
        listener = new ServerBootstrap()
                .group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ParentInitializer(tls))
                .bind("127.0.0.1", 0)
                .sync()
                .channel();
    }

    static TestHttp2GoAwayOrigin start(Path certificateChain, Path privateKey) throws Exception {
        return new TestHttp2GoAwayOrigin(certificateChain, privateKey, false);
    }

    static TestHttp2GoAwayOrigin startHoldingFirstResponse(Path certificateChain, Path privateKey) throws Exception {
        return new TestHttp2GoAwayOrigin(certificateChain, privateKey, true);
    }

    int port() {
        return ((InetSocketAddress) listener.localAddress()).getPort();
    }

    boolean awaitGoAway(Duration timeout) throws InterruptedException {
        return goAwayWritten.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    boolean awaitSecondParent(Duration timeout) throws InterruptedException {
        return secondParentConnected.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    boolean awaitFirstResponseHeld(Duration timeout) throws InterruptedException {
        return firstResponseHeld.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    void releaseFirstResponse() {
        if (!releaseHeldFirstResponse()) {
            throw new IllegalStateException("first response is not being held");
        }
    }

    int parentConnectionCount() {
        return parentConnections.get();
    }

    void assertHealthy() {
        var observed = failure.get();
        if (observed != null) {
            throw new AssertionError("GOAWAY origin fixture failed", observed);
        }
    }

    @Override
    public void close() {
        releaseHeldFirstResponse();
        listener.close().awaitUninterruptibly(TIMEOUT.toMillis());
        workerGroup.shutdownGracefully(0, TIMEOUT.toMillis(), TimeUnit.MILLISECONDS).awaitUninterruptibly();
        bossGroup.shutdownGracefully(0, TIMEOUT.toMillis(), TimeUnit.MILLISECONDS).awaitUninterruptibly();
    }

    private void respond(ChannelHandlerContext context, int parentNumber, String path) {
        var encoded = ("parent-" + parentNumber + ':' + path).getBytes(StandardCharsets.UTF_8);
        var response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1,
                HttpResponseStatus.OK,
                Unpooled.wrappedBuffer(encoded));
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=UTF-8");
        response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, encoded.length);
        context.writeAndFlush(response).addListener(written -> {
            if (!written.isSuccess()) {
                failure.compareAndSet(null, written.cause());
                context.close();
            }
        });
    }

    private void holdFirstResponse(ChannelHandlerContext context, int parentNumber, String path) {
        if (!heldFirstResponse.compareAndSet(null, new HeldResponse(context, parentNumber, path))) {
            failure.compareAndSet(null, new IllegalStateException("fixture attempted to hold multiple first responses"));
            context.close();
            return;
        }
        firstResponseHeld.countDown();
    }

    private boolean releaseHeldFirstResponse() {
        var held = heldFirstResponse.getAndSet(null);
        if (held == null) {
            return false;
        }
        held.context().executor().execute(() -> respond(held.context(), held.parentNumber(), held.path()));
        return true;
    }

    private final class ParentInitializer extends ChannelInitializer<SocketChannel> {
        private final SslContext tls;

        private ParentInitializer(SslContext tls) {
            this.tls = tls;
        }

        @Override
        protected void initChannel(SocketChannel channel) {
            channel.pipeline().addLast(tls.newHandler(channel.alloc()));
            channel.pipeline().addLast(new ApplicationProtocolNegotiationHandler("wave-no-alpn") {
                @Override
                protected void configurePipeline(ChannelHandlerContext context, String protocol) {
                    if (!ApplicationProtocolNames.HTTP_2.equals(protocol)) {
                        failure.compareAndSet(null, new IllegalStateException(
                                "GOAWAY origin required h2 ALPN but negotiated " + protocol));
                        context.close();
                        return;
                    }
                    var parentNumber = parentConnections.incrementAndGet();
                    if (parentNumber == 2) {
                        secondParentConnected.countDown();
                    }
                    if (parentNumber > 2) {
                        failure.compareAndSet(null, new AssertionError(
                                "client opened more than two HTTP/2 parents after one GOAWAY"));
                        context.close();
                        return;
                    }
                    context.pipeline().addLast(Http2FrameCodecBuilder.forServer().build());
                    context.pipeline().addLast(new Http2MultiplexHandler(new ChannelInitializer<Channel>() {
                        @Override
                        protected void initChannel(Channel stream) {
                            stream.pipeline().addLast(new Http2StreamFrameToHttpObjectCodec(true, true));
                            stream.pipeline().addLast(new StreamHandler(parentNumber));
                        }
                    }));
                }

                @Override
                protected void handshakeFailure(ChannelHandlerContext context, Throwable cause) {
                    failure.compareAndSet(null, cause);
                    context.close();
                }
            });
        }
    }

    private final class StreamHandler extends SimpleChannelInboundHandler<HttpObject> {
        private final int parentNumber;
        private boolean responded;

        private StreamHandler(int parentNumber) {
            this.parentNumber = parentNumber;
        }

        @Override
        protected void channelRead0(ChannelHandlerContext context, HttpObject message) {
            if (responded || !(message instanceof HttpRequest request)) {
                return;
            }
            responded = true;
            if (parentNumber != 1 || !firstGoAwayWritten.compareAndSet(false, true)) {
                respond(context, parentNumber, request.uri());
                return;
            }
            var parent = context.channel().parent();
            parent.writeAndFlush(new DefaultHttp2GoAwayFrame(Http2Error.NO_ERROR)).addListener(written -> {
                if (!written.isSuccess()) {
                    failure.compareAndSet(null, written.cause());
                    context.close();
                    return;
                }
                goAwayWritten.countDown();
                if (holdFirstResponse) {
                    holdFirstResponse(context, parentNumber, request.uri());
                    return;
                }
                respond(context, parentNumber, request.uri());
            });
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext context, Throwable cause) {
            failure.compareAndSet(null, cause);
            context.close();
        }
    }

    private record HeldResponse(ChannelHandlerContext context, int parentNumber, String path) {
    }
}

