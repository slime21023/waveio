package io.wavejava.wave.netty;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
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
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Test-only TLS/ALPN peer which writes HTTP/2 bytes without a Netty frame encoder.
 *
 * <p>This intentionally exercises decoder protections that a high-level frame writer may
 * normalize away, such as a consecutive zero-length DATA-frame flood. It has one connection,
 * one event loop, finite waits, and is never part of the production artifact.</p>
 */
public final class TestHttp2WirePeer implements AutoCloseable {
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(5);
    private static final byte[] CONNECTION_PREFACE =
            "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
    private static final int FRAME_DATA = 0x0;
    private static final int FRAME_HEADERS = 0x1;
    private static final int FRAME_SETTINGS = 0x4;
    private static final int FRAME_GO_AWAY = 0x7;
    private static final int FLAG_END_HEADERS = 0x4;

    private final NioEventLoopGroup group;
    private final CompletableFuture<Void> ready = new CompletableFuture<>();
    private final CompletableFuture<GoAway> goAway = new CompletableFuture<>();
    private final CompletableFuture<Void> parentClosed = new CompletableFuture<>();
    private final WireFrameObserver observer = new WireFrameObserver(goAway);
    private Bootstrap bootstrap;
    private Channel parent;

    private TestHttp2WirePeer(Path trustCertificate) {
        try {
            group = new NioEventLoopGroup(1, new DefaultThreadFactory("wave-test-h2-wire-peer", true));
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
            throw new IllegalStateException("Could not construct raw HTTP/2 wire peer", failure);
        }
    }

    /** Opens a TLS connection and requires ALPN HTTP/2 before raw bytes can be written. */
    public static TestHttp2WirePeer connect(int port, Path trustCertificate) throws Exception {
        var peer = new TestHttp2WirePeer(trustCertificate);
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

    /**
     * Sends a valid non-terminal request head followed by exactly {@code emptyFrames} empty DATA
     * frames on stream 1. Frame serialization is deliberately manual so no encoder drops them.
     */
    public void sendConsecutiveEmptyDataFrames(String authority, int emptyFrames) throws Exception {
        Objects.requireNonNull(authority, "authority");
        if (emptyFrames < 1) {
            throw new IllegalArgumentException("emptyFrames must be at least one");
        }
        if (parent == null || !parent.isActive()) {
            throw new IllegalStateException("HTTP/2 wire peer is not connected");
        }

        var bytes = new ByteArrayOutputStream(CONNECTION_PREFACE.length + 9 * (emptyFrames + 2) + authority.length() + 8);
        bytes.writeBytes(CONNECTION_PREFACE);
        writeFrame(bytes, FRAME_SETTINGS, 0, 0, new byte[0]);
        writeFrame(bytes, FRAME_HEADERS, FLAG_END_HEADERS, 1, requestHeaders(authority));
        for (var index = 0; index < emptyFrames; index++) {
            writeFrame(bytes, FRAME_DATA, 0, 1, new byte[0]);
        }

        var written = new CompletableFuture<Void>();
        var payload = bytes.toByteArray();
        parent.eventLoop().execute(() -> parent.writeAndFlush(Unpooled.wrappedBuffer(payload)).addListener(future -> {
            if (future.isSuccess()) {
                written.complete(null);
            } else {
                written.completeExceptionally(future.cause());
            }
        }));
        written.get(DEFAULT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
    }

    /** Waits for the server's fatal protocol GOAWAY. */
    public GoAway awaitGoAway(Duration timeout) throws Exception {
        return goAway.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    /** Verifies that the fatal protocol close is not left as a live parent connection. */
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
                                            "Test wire peer required HTTP/2 ALPN but negotiated " + protocol));
                                    context.close();
                                    return;
                                }
                                context.pipeline().addLast(observer);
                                ready.complete(null);
                            }

                            @Override
                            public void exceptionCaught(ChannelHandlerContext context, Throwable failure) {
                                ready.completeExceptionally(failure);
                                goAway.completeExceptionally(failure);
                                context.close();
                            }
                        });
                    }
                });
    }

    private static byte[] requestHeaders(String authority) {
        var authorityBytes = authority.getBytes(StandardCharsets.US_ASCII);
        if (authorityBytes.length > 127) {
            throw new IllegalArgumentException("test authority exceeds the single-byte HPACK literal encoding");
        }
        // HPACK static-table indexes: :method GET=2, :scheme https=7, :authority=1, :path /=4.
        var headers = new ByteArrayOutputStream(authorityBytes.length + 5);
        headers.write(0x82);
        headers.write(0x87);
        headers.write(0x01); // literal without indexing, indexed name :authority
        headers.write(authorityBytes.length);
        headers.writeBytes(authorityBytes);
        headers.write(0x84);
        return headers.toByteArray();
    }

    private static void writeFrame(ByteArrayOutputStream target, int type, int flags, int streamId, byte[] payload) {
        var length = payload.length;
        target.write((length >>> 16) & 0xff);
        target.write((length >>> 8) & 0xff);
        target.write(length & 0xff);
        target.write(type & 0xff);
        target.write(flags & 0xff);
        target.write((streamId >>> 24) & 0x7f);
        target.write((streamId >>> 16) & 0xff);
        target.write((streamId >>> 8) & 0xff);
        target.write(streamId & 0xff);
        target.writeBytes(payload);
    }

    /** Compact GOAWAY metadata, with the debug buffer deliberately discarded. */
    public record GoAway(long errorCode, int lastStreamId) {
    }

    private static final class WireFrameObserver extends ChannelInboundHandlerAdapter {
        private final CompletableFuture<GoAway> goAway;
        private byte[] pending = new byte[0];

        private WireFrameObserver(CompletableFuture<GoAway> goAway) {
            this.goAway = goAway;
        }

        @Override
        public void channelRead(ChannelHandlerContext context, Object message) {
            if (!(message instanceof ByteBuf buffer)) {
                context.fireChannelRead(message);
                return;
            }
            try {
                var chunk = new byte[buffer.readableBytes()];
                buffer.readBytes(chunk);
                consume(chunk);
            } finally {
                ReferenceCountUtil.release(message);
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext context, Throwable failure) {
            goAway.completeExceptionally(failure);
            context.close();
        }

        @Override
        public void channelInactive(ChannelHandlerContext context) {
            goAway.completeExceptionally(new IllegalStateException("HTTP/2 server closed before sending GOAWAY"));
        }

        private void consume(byte[] chunk) {
            var available = new byte[pending.length + chunk.length];
            System.arraycopy(pending, 0, available, 0, pending.length);
            System.arraycopy(chunk, 0, available, pending.length, chunk.length);

            var offset = 0;
            while (available.length - offset >= 9) {
                var payloadLength = ((available[offset] & 0xff) << 16)
                        | ((available[offset + 1] & 0xff) << 8)
                        | (available[offset + 2] & 0xff);
                if (available.length - offset < 9 + payloadLength) {
                    break;
                }
                var type = available[offset + 3] & 0xff;
                if (type == FRAME_GO_AWAY && payloadLength >= 8) {
                    var lastStreamId = ((available[offset + 9] & 0x7f) << 24)
                            | ((available[offset + 10] & 0xff) << 16)
                            | ((available[offset + 11] & 0xff) << 8)
                            | (available[offset + 12] & 0xff);
                    goAway.complete(new GoAway(unsignedInt(available, offset + 13), lastStreamId));
                }
                offset += 9 + payloadLength;
            }
            pending = Arrays.copyOfRange(available, offset, available.length);
        }

        private static long unsignedInt(byte[] bytes, int offset) {
            return ((long) (bytes[offset] & 0xff) << 24)
                    | ((long) (bytes[offset + 1] & 0xff) << 16)
                    | ((long) (bytes[offset + 2] & 0xff) << 8)
                    | (bytes[offset + 3] & 0xffL);
        }
    }
}
