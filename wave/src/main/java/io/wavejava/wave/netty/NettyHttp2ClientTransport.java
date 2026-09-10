package io.wavejava.wave.netty;

import io.wavejava.wave.api.client.*;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoop;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.codec.http2.Http2ConnectionPrefaceAndSettingsFrameWrittenEvent;
import io.netty.handler.codec.http2.DefaultHttp2ResetFrame;
import io.netty.handler.codec.http2.Http2Error;
import io.netty.handler.codec.http2.Http2FrameCodec;
import io.netty.handler.codec.http2.Http2FrameCodecBuilder;
import io.netty.handler.codec.http2.Http2GoAwayFrame;
import io.netty.handler.codec.http2.Http2MultiplexHandler;
import io.netty.handler.codec.http2.Http2ResetFrame;
import io.netty.handler.codec.http2.Http2Settings;
import io.netty.handler.codec.http2.Http2StreamChannelBootstrap;
import io.netty.handler.codec.http2.Http2StreamChannel;
import io.netty.handler.codec.http2.Http2StreamFrameToHttpObjectCodec;
import io.netty.handler.ssl.ApplicationProtocolConfig;
import io.netty.handler.ssl.ApplicationProtocolNames;
import io.netty.handler.ssl.ApplicationProtocolNegotiationHandler;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslHandler;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.DefaultThreadFactory;
import io.wavejava.wave.api.http.Headers;
import io.wavejava.wave.api.http.Http2Config;
import io.wavejava.wave.runtime.client.CancellationBridge;
import io.wavejava.wave.runtime.client.ClientTransport;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.channels.ClosedChannelException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Owned TLS/ALPN HTTP/2 client transport.
 *
 * <p>Parent connections are route-keyed, LRU-bounded, and own many independent stream channels.
 * A stream is the physical lifecycle unit for one Wave exchange: its completion is exposed only
 * after END_STREAM or stream close, so a caller's {@link ClientRequestPool.Lease} cannot be released
 * while cancellation is still waiting to reach the HTTP/2 transport. All connection and stream
 * mutations happen on one private EventLoop; caller continuations remain in {@link WaveClient}'s
 * virtual-thread orchestration layer.</p>
 */
public final class NettyHttp2ClientTransport implements ClientTransport {
    private static final int INITIAL_LINE_LIMIT = 8 * 1024;
    private static final Set<String> TRANSPORT_OWNED_REQUEST_HEADERS = Set.of(
            "host", "content-length", "transfer-encoding", "connection", "keep-alive",
            "proxy-connection", "upgrade", "trailer", "te");

    private final NioEventLoopGroup group;
    private final EventLoop eventLoop;
    private final Bootstrap bootstrap;
    private final SslContext tlsContext;
    private final ClientRequestPool pool;
    private final ProxyPolicy proxyPolicy;
    private final Http2Config config;
    private final NettyHttp1ClientTransport http1Fallback;
    private final int maximumPhysicalChannels;
    private final int maximumResponseBodyBytes;
    private final int maximumResponseHeaders;
    private final int maximumResponseHeaderBytes;

    // Event-loop-owned collections. `connections` selects the newest usable parent per route and
    // retains deterministic LRU ordering for idle eviction. A GOAWAY-draining parent can no
    // longer be selected for its route, but it remains in `liveConnections` until its channel has
    // actually closed; physical-cap accounting and shutdown must include both collections.
    private final LinkedHashMap<RouteKey, Connection> connections = new LinkedHashMap<>(16, 0.75f, true);
    private final LinkedHashSet<Connection> liveConnections = new LinkedHashSet<>();
    private final ArrayDeque<PendingStart> pendingStarts = new ArrayDeque<>();
    private final AtomicBoolean closing = new AtomicBoolean();
    private final CompletableFuture<Void> closeCompletion = new CompletableFuture<>();
    private final CompletableFuture<Void> h2CloseCompletion = new CompletableFuture<>();
    private boolean shutdownStarted;

    public NettyHttp2ClientTransport(
            ClientRequestPool pool,
            ProxyPolicy proxyPolicy,
            Http2Config config,
            ClientTlsConfig tls) {
        this.pool = Objects.requireNonNull(pool, "pool");
        this.proxyPolicy = Objects.requireNonNull(proxyPolicy, "proxyPolicy");
        this.config = Objects.requireNonNull(config, "config");
        Objects.requireNonNull(tls, "tls");
        if (!config.isEnabled()) {
            throw new IllegalArgumentException("HTTP/2 transport requires an enabled Http2Config");
        }
        maximumPhysicalChannels = pool.maximumConcurrentRequests();
        maximumResponseBodyBytes = pool.maximumResponseBodyBytes();
        maximumResponseHeaders = pool.maximumResponseHeaders();
        maximumResponseHeaderBytes = pool.maximumResponseHeaderBytes();
        try {
            var builder = SslContextBuilder.forClient()
                    .endpointIdentificationAlgorithm("HTTPS")
                    .applicationProtocolConfig(new ApplicationProtocolConfig(
                            ApplicationProtocolConfig.Protocol.ALPN,
                            ApplicationProtocolConfig.SelectorFailureBehavior.NO_ADVERTISE,
                            ApplicationProtocolConfig.SelectedListenerFailureBehavior.ACCEPT,
                            ApplicationProtocolNames.HTTP_2,
                            ApplicationProtocolNames.HTTP_1_1));
            tls.trustCertificateChain().ifPresent(path -> builder.trustManager(path.toFile()));
            tlsContext = builder.build();
        } catch (Exception failure) {
            throw new IllegalStateException("Could not initialize the Netty HTTP/2 TLS client context", failure);
        }
        // HTTP/1.1 remains the deterministic PREFER fallback and handles ordinary HTTP origins.
        http1Fallback = new NettyHttp1ClientTransport(pool, proxyPolicy, tls);
        group = new NioEventLoopGroup(1, new DefaultThreadFactory("wave-client-h2-io", true));
        eventLoop = (EventLoop) group.next();
        bootstrap = new Bootstrap()
                .group(group)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.TCP_NODELAY, true)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, timeoutMillis(pool.connectTimeout()));
    }

    @Override
    public ClientTransport.Exchange send(ClientRequest request) {
        Objects.requireNonNull(request, "request");
        validateRequest(request);
        if (closing.get()) {
            throw new IllegalStateException("WaveClient transport is closed");
        }
        if (!request.uri().getScheme().equalsIgnoreCase("https")) {
            return http1Fallback.send(request);
        }
        var exchange = new TransportExchange(this);
        var route = RouteKey.from(request.uri(), proxyPolicy);
        if (!submit(() -> begin(exchange, request, route))) {
            exchange.completeExceptionally(new IllegalStateException("WaveClient transport is closed"));
        }
        return exchange;
    }

    @Override
    public CompletionStage<Void> closeAsync() {
        if (closing.compareAndSet(false, true)) {
            var fallbackClose = http1Fallback.closeAsync();
            if (!submit(this::beginShutdown)) {
                h2CloseCompletion.complete(null);
            }
            CompletableFuture.allOf(h2CloseCompletion, fallbackClose.toCompletableFuture())
                    .whenComplete((ignored, failure) -> {
                        if (failure == null) {
                            closeCompletion.complete(null);
                        } else {
                            closeCompletion.completeExceptionally(failure);
                        }
                    });
        }
        return closeCompletion;
    }

    private void begin(TransportExchange exchange, ClientRequest request, RouteKey route) {
        if (exchange.abortRequested()) {
            exchange.completeCancelledWithoutStream();
            return;
        }
        if (closing.get()) {
            exchange.completeExceptionally(new CancellationException("WaveClient transport closed"));
            return;
        }
        var connection = connections.get(route);
        if (connection != null && connection.acceptingNewStreams()) {
            connection.enqueue(exchange, request);
            return;
        }
        if (liveConnections.size() >= maximumPhysicalChannels) {
            var idle = oldestIdleConnection();
            pendingStarts.addLast(new PendingStart(exchange, request, route));
            if (idle != null) {
                idle.closeForEviction();
            }
            return;
        }
        openConnection(exchange, request, route);
    }

    private void openConnection(TransportExchange exchange, ClientRequest request, RouteKey route) {
        if (exchange.abortRequested()) {
            exchange.completeCancelledWithoutStream();
            return;
        }
        if (closing.get()) {
            exchange.completeExceptionally(new CancellationException("WaveClient transport closed"));
            return;
        }
        var connection = new Connection(route);
        liveConnections.add(connection);
        connections.put(route, connection);
        connection.enqueue(exchange, request);
        connection.connect();
    }

    private void abort(TransportExchange exchange) {
        var fallback = exchange.fallbackExchange();
        if (fallback != null) {
            fallback.cancellationHandle().requestAbort();
            return;
        }
        for (var iterator = pendingStarts.iterator(); iterator.hasNext();) {
            var pending = iterator.next();
            if (pending.exchange() == exchange) {
                iterator.remove();
                exchange.completeCancelledWithoutStream();
                return;
            }
        }
        var connection = exchange.connection();
        if (connection == null) {
            exchange.completeCancelledWithoutStream();
            return;
        }
        connection.abort(exchange);
    }

    private void beginShutdown() {
        for (var pending : pendingStarts) {
            pending.exchange().completeExceptionally(new CancellationException("WaveClient transport closed"));
        }
        pendingStarts.clear();
        for (var connection : List.copyOf(liveConnections)) {
            connection.closeForShutdown();
        }
        finishH2ShutdownIfDrained();
    }

    private void connectionClosed(Connection connection) {
        if (liveConnections.remove(connection)) {
            connections.remove(connection.route, connection);
            connection.failAll(new ClosedChannelException());
        }
        drainPendingStarts();
        finishH2ShutdownIfDrained();
    }

    private void drainPendingStarts() {
        while (!pendingStarts.isEmpty()) {
            var pending = pendingStarts.removeFirst();
            if (pending.exchange().abortRequested()) {
                pending.exchange().completeCancelledWithoutStream();
                continue;
            }
            if (closing.get()) {
                pending.exchange().completeExceptionally(new CancellationException("WaveClient transport closed"));
                continue;
            }
            var known = connections.get(pending.route());
            if (known != null && known.acceptingNewStreams()) {
                known.enqueue(pending.exchange(), pending.request());
                continue;
            }
            if (liveConnections.size() < maximumPhysicalChannels) {
                openConnection(pending.exchange(), pending.request(), pending.route());
                continue;
            }
            var idle = oldestIdleConnection();
            pendingStarts.addFirst(pending);
            if (idle != null) {
                idle.closeForEviction();
            }
            return;
        }
    }

    private Connection oldestIdleConnection() {
        for (var connection : connections.values()) {
            if (connection.isIdle()) {
                return connection;
            }
        }
        return null;
    }

    private void finishH2ShutdownIfDrained() {
        if (!closing.get() || !liveConnections.isEmpty() || shutdownStarted) {
            return;
        }
        shutdownStarted = true;
        group.shutdownGracefully(0, 5, TimeUnit.SECONDS).addListener(future -> {
            if (future.isSuccess()) {
                h2CloseCompletion.complete(null);
            } else {
                h2CloseCompletion.completeExceptionally(future.cause());
            }
        });
    }

    private boolean submit(Runnable action) {
        try {
            eventLoop.execute(action);
            return true;
        } catch (RejectedExecutionException rejected) {
            return false;
        }
    }

    private static void validateRequest(ClientRequest request) {
        var scheme = request.uri().getScheme();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            throw new IllegalArgumentException("Client request must use HTTP(S): " + request.uri());
        }
        if (request.method().name().equals("CONNECT")) {
            throw new UnsupportedOperationException("CONNECT requires a tunnel API and is not supported by WaveClient");
        }
        for (var name : request.headers().names()) {
            if (TRANSPORT_OWNED_REQUEST_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("Client request must not set transport-owned header: " + name);
            }
        }
    }

    private static int timeoutMillis(Duration duration) {
        try {
            return Math.toIntExact(Math.max(1L, duration.toMillis()));
        } catch (ArithmeticException ignored) {
            return Integer.MAX_VALUE;
        }
    }

    private static String originForm(URI uri) {
        var path = uri.getRawPath();
        if (path == null || path.isEmpty()) {
            path = "/";
        }
        return uri.getRawQuery() == null ? path : path + '?' + uri.getRawQuery();
    }

    private static String hostHeader(URI uri) {
        var host = uri.getHost();
        var displayHost = host.indexOf(':') >= 0 ? '[' + host + ']' : host;
        var port = uri.getPort();
        return port < 0 || port == 443 ? displayHost : displayHost + ':' + port;
    }

    private static long headerWireBytes(String value) {
        long bytes = 0;
        for (var index = 0; index < value.length(); index++) {
            var character = value.charAt(index);
            if (character <= 0x7f) {
                bytes++;
            } else if (character <= 0x7ff) {
                bytes += 2;
            } else if (Character.isHighSurrogate(character)
                    && index + 1 < value.length()
                    && Character.isLowSurrogate(value.charAt(index + 1))) {
                bytes += 4;
                index++;
            } else {
                bytes += 3;
            }
        }
        return bytes;
    }

    private static boolean isInternalHttp2Header(String name) {
        for (var extension : io.netty.handler.codec.http2.HttpConversionUtil.ExtensionHeaderNames.values()) {
            if (extension.text().contentEqualsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    private final class Connection {
        private final RouteKey route;
        private final ArrayDeque<PendingStream> waiting = new ArrayDeque<>();
        private final Map<TransportExchange, Channel> activeStreams = new LinkedHashMap<>();
        private final int maximumInboundResponseBytes;
        private final int maximumOutboundRequestBytes;
        private Channel channel;
        private ChannelFuture connectFuture;
        private boolean ready;
        private boolean closeRequested;
        private boolean handedOffToHttp1;
        private boolean goAwayReceived;

        private Connection(RouteKey route) {
            this.route = route;
            var inboundPartition = config.maximumInboundBytesPerConnection() / config.maximumConcurrentStreams();
            maximumInboundResponseBytes = (int) Math.min(
                    maximumResponseBodyBytes, Math.max(1L, Math.min((long) Integer.MAX_VALUE, inboundPartition)));
            // The client currently materializes finite byte request bodies. Equal static
            // partitions are deliberate: every concurrently writable stream can retain at most
            // this amount, proving the sum stays inside the configured H2 connection budget.
            maximumOutboundRequestBytes = (int) Math.min(
                    Math.min((long) pool.maximumRequestBodyBytes(), config.maximumOutboundBytesPerStream()),
                    Math.max(1L, Math.min((long) Integer.MAX_VALUE,
                            config.maximumOutboundBytesPerConnection() / config.maximumConcurrentStreams())));
        }

        private void connect() {
            final ChannelFuture future;
            try {
                future = bootstrap.clone()
                        .handler(new ParentChannelInitializer(this))
                        .connect(route.host(), route.port());
            } catch (Throwable failure) {
                failAll(failure);
                connections.remove(route, this);
                liveConnections.remove(this);
                drainPendingStarts();
                finishH2ShutdownIfDrained();
                return;
            }
            connectFuture = future;
            channel = future.channel();
            channel.closeFuture().addListener(ignored -> {
                if (!submit(() -> connectionClosed(this))) {
                    connectionClosed(this);
                }
            });
            future.addListener(connected -> {
                if (!connected.isSuccess()) {
                    failAll(connected.cause());
                    requestClose();
                }
            });
        }

        private boolean acceptingNewStreams() {
            return !closeRequested && !handedOffToHttp1 && !goAwayReceived;
        }

        private boolean isIdle() {
            return ready && waiting.isEmpty() && activeStreams.isEmpty() && !closeRequested && !handedOffToHttp1;
        }

        private void enqueue(TransportExchange exchange, ClientRequest request) {
            if (exchange.abortRequested()) {
                exchange.completeCancelledWithoutStream();
                return;
            }
            if (!acceptingNewStreams()) {
                pendingStarts.addLast(new PendingStart(exchange, request, route));
                return;
            }
            exchange.bindConnection(this);
            waiting.addLast(new PendingStream(exchange, request));
            drainStreams();
        }

        private void ready() {
            if (closeRequested || handedOffToHttp1) {
                return;
            }
            ready = true;
            drainStreams();
        }

        private void drainStreams() {
            if (!ready || closeRequested || channel == null || !channel.isActive()) {
                return;
            }
            while (activeStreams.size() < config.maximumConcurrentStreams() && !waiting.isEmpty()) {
                var pending = waiting.removeFirst();
                if (pending.exchange().abortRequested()) {
                    pending.exchange().completeCancelledWithoutStream();
                    continue;
                }
                openStream(pending);
            }
        }

        private void openStream(PendingStream pending) {
            var bootstrap = new Http2StreamChannelBootstrap(channel)
                    .handler(new ChannelInitializer<Channel>() {
                        @Override
                        protected void initChannel(Channel stream) {
                            stream.pipeline().addLast(new Http2StreamFrameToHttpObjectCodec(false, true));
                            stream.pipeline().addLast(new ResponseHandler(Connection.this, pending.exchange()));
                        }
                    });
            bootstrap.open().addListener(opened -> {
                if (!opened.isSuccess()) {
                    failStream(pending.exchange(), opened.cause());
                    return;
                }
                var stream = (Channel) opened.getNow();
                if (pending.exchange().abortRequested()) {
                    stream.close();
                    pending.exchange().completeCancelledWithoutStream();
                    return;
                }
                activeStreams.put(pending.exchange(), stream);
                pending.exchange().bindStream(stream);
                writeRequest(pending.exchange(), pending.request(), stream);
            });
        }

        private void writeRequest(TransportExchange exchange, ClientRequest request, Channel stream) {
            final FullHttpRequest outbound;
            try {
                var body = request.body();
                if (body.length > maximumOutboundRequestBytes) {
                    throw new ClientLimitExceededException(
                            "HTTP/2 outbound stream bytes", maximumOutboundRequestBytes, body.length);
                }
                outbound = new DefaultFullHttpRequest(
                        HttpVersion.HTTP_1_1,
                        HttpMethod.valueOf(request.method().name()),
                        originForm(request.uri()),
                        Unpooled.wrappedBuffer(body));
                request.headers().asMap().forEach((name, values) ->
                        values.forEach(value -> outbound.headers().add(name, value)));
                outbound.headers().set(HttpHeaderNames.HOST, hostHeader(request.uri()));
                outbound.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, body.length);
            } catch (Throwable failure) {
                failStream(exchange, failure);
                return;
            }
            stream.writeAndFlush(outbound).addListener(written -> {
                if (!written.isSuccess()) {
                    failStream(exchange, written.cause());
                }
            });
        }

        private void completeStream(TransportExchange exchange, ClientTransport.TransportResponse response) {
            var stream = activeStreams.remove(exchange);
            if (stream == null) {
                return;
            }
            exchange.complete(response);
            closeAfterGoAwayIfDrained();
            drainStreams();
            drainPendingStarts();
        }

        private void failStream(TransportExchange exchange, Throwable failure) {
            var stream = activeStreams.remove(exchange);
            if (stream != null && stream.isOpen()) {
                stream.close();
            }
            if (!exchange.isDone()) {
                exchange.completeExceptionally(failure == null ? new ClosedChannelException() : failure);
            }
            closeAfterGoAwayIfDrained();
            drainStreams();
            drainPendingStarts();
        }

        private void streamClosed(TransportExchange exchange) {
            if (activeStreams.containsKey(exchange) && !exchange.isDone()) {
                failStream(exchange, new ClosedChannelException());
            }
        }

        private void abort(TransportExchange exchange) {
            for (var iterator = waiting.iterator(); iterator.hasNext();) {
                var pending = iterator.next();
                if (pending.exchange() == exchange) {
                    iterator.remove();
                    exchange.completeCancelledWithoutStream();
                    drainPendingStarts();
                    return;
                }
            }
            var stream = activeStreams.remove(exchange);
            if (stream == null) {
                exchange.completeCancelledWithoutStream();
                return;
            }
            stream.closeFuture().addListener(ignored -> exchange.completeExceptionally(
                    new CancellationException("HTTP/2 transport exchange cancelled")));
            // A child-channel close alone is not a protocol-level cancellation in every Netty
            // state (notably after request END_STREAM). Emit an explicit RST_STREAM first so the
            // remote invocation observes cancellation promptly, then release the local channel.
            if (stream instanceof Http2StreamChannel http2Stream) {
                // Http2StreamFrameToHttpObjectCodec accepts outbound HttpObject instances only.
                // Use the child unsafe transport boundary, as Netty's own close-with-error path
                // does, so the Http2StreamFrame reaches the multiplex parent without an HTTP/1
                // object codec attempting to cast it.
                stream.unsafe().write(
                        new DefaultHttp2ResetFrame(Http2Error.CANCEL).stream(http2Stream.stream()),
                        stream.unsafe().voidPromise());
                stream.unsafe().flush();
                stream.close();
            } else {
                stream.close();
            }
            drainStreams();
            drainPendingStarts();
        }

        private void onAlpnHttp1() {
            if (!config.requiresHttp2()) {
                handedOffToHttp1 = true;
                connections.remove(route, this);
                while (!waiting.isEmpty()) {
                    var pending = waiting.removeFirst();
                    if (pending.exchange().abortRequested()) {
                        pending.exchange().completeCancelledWithoutStream();
                        continue;
                    }
                    try {
                        pending.exchange().bindFallback(http1Fallback.send(pending.request()));
                    } catch (RuntimeException failure) {
                        pending.exchange().completeExceptionally(failure);
                    }
                }
                requestClose();
                drainPendingStarts();
                return;
            }
            failAll(new UnsupportedOperationException("TLS peer did not negotiate required HTTP/2 ALPN"));
            requestClose();
        }

        /**
         * Retires a parent after a peer GOAWAY without discarding streams that the peer promised
         * to process. Requests which have not opened a stream are handed to the normal bounded
         * pending-start path; requests above the advertised last stream ID become ordinary
         * transport failures and are retried only when the caller's idempotency-safe policy says
         * that is allowed.
         */
        private void onGoAway(Http2GoAwayFrame frame) {
            if (goAwayReceived || closeRequested || handedOffToHttp1) {
                return;
            }
            goAwayReceived = true;
            var lastAcceptedStreamId = frame.lastStreamId();
            while (!waiting.isEmpty()) {
                var pending = waiting.removeFirst();
                if (pending.exchange().abortRequested()) {
                    pending.exchange().completeCancelledWithoutStream();
                } else {
                    pendingStarts.addLast(new PendingStart(pending.exchange(), pending.request(), route));
                }
            }
            for (var entry : List.copyOf(activeStreams.entrySet())) {
                var stream = entry.getValue();
                if (stream instanceof Http2StreamChannel http2Stream
                        && http2Stream.stream().id() > lastAcceptedStreamId) {
                    failStream(entry.getKey(), new IllegalStateException(
                            "HTTP/2 peer GOAWAY did not accept stream " + http2Stream.stream().id()));
                }
            }
            closeAfterGoAwayIfDrained();
            drainPendingStarts();
        }

        private void closeAfterGoAwayIfDrained() {
            if (goAwayReceived && activeStreams.isEmpty()) {
                requestClose();
            }
        }

        private void failAll(Throwable failure) {
            if (handedOffToHttp1) {
                return;
            }
            while (!waiting.isEmpty()) {
                waiting.removeFirst().exchange().completeExceptionally(failure);
            }
            var active = List.copyOf(activeStreams.entrySet());
            activeStreams.clear();
            for (var entry : active) {
                entry.getKey().completeExceptionally(failure);
                entry.getValue().close();
            }
        }

        private void closeForEviction() {
            if (!isIdle()) {
                return;
            }
            requestClose();
        }

        private void closeForShutdown() {
            failAll(new CancellationException("WaveClient transport closed"));
            requestClose();
        }

        private void requestClose() {
            if (closeRequested) {
                return;
            }
            closeRequested = true;
            if (connectFuture != null && !connectFuture.isDone()) {
                connectFuture.cancel(false);
            }
            if (channel != null) {
                channel.close();
            }
        }

        private Headers toWaveHeaders(HttpHeaders source) {
            var headers = Headers.builder();
            long count = 0;
            long bytes = 0;
            for (var entry : source) {
                if (isInternalHttp2Header(entry.getKey())) {
                    continue;
                }
                count++;
                if (count > maximumResponseHeaders) {
                    throw new ClientLimitExceededException(
                            "response header fields", maximumResponseHeaders, count);
                }
                var fieldBytes = headerWireBytes(entry.getKey()) + 1L + headerWireBytes(entry.getValue()) + 2L;
                if (fieldBytes > maximumResponseHeaderBytes - bytes) {
                    throw new ClientLimitExceededException(
                            "response header bytes", maximumResponseHeaderBytes, bytes + fieldBytes);
                }
                bytes += fieldBytes;
                headers.add(entry.getKey(), entry.getValue());
            }
            return headers.build();
        }
    }

    private final class ParentChannelInitializer extends ChannelInitializer<SocketChannel> {
        private final Connection connection;

        private ParentChannelInitializer(Connection connection) {
            this.connection = connection;
        }

        @Override
        protected void initChannel(SocketChannel channel) {
            if (connection.route.proxy()) {
                channel.pipeline().addLast(new HttpClientCodec(INITIAL_LINE_LIMIT, maximumResponseHeaderBytes, INITIAL_LINE_LIMIT));
                channel.pipeline().addLast(new ProxyConnectHandler(connection));
                return;
            }
            configureTlsAlpn(channel.pipeline(), connection);
        }
    }

    private void configureTlsAlpn(io.netty.channel.ChannelPipeline pipeline, Connection connection) {
        var channel = pipeline.channel();
        pipeline.addLast(tlsContext.newHandler(
                channel.alloc(), connection.route.originHost(), connection.route.originPort()));
            var fallback = config.requiresHttp2() ? "wave-no-alpn" : ApplicationProtocolNames.HTTP_1_1;
            pipeline.addLast(new ApplicationProtocolNegotiationHandler(fallback) {
                @Override
                protected void configurePipeline(ChannelHandlerContext context, String protocol) {
                    if (ApplicationProtocolNames.HTTP_2.equals(protocol)) {
                        configureHttp2(context, connection);
                        return;
                    }
                    if (ApplicationProtocolNames.HTTP_1_1.equals(protocol)) {
                        connection.onAlpnHttp1();
                        return;
                    }
                    connection.failAll(new UnsupportedOperationException(
                            "TLS peer selected unsupported ALPN protocol: " + protocol));
                    context.close();
                }
            });
    }

    /** Completes one bounded HTTP CONNECT handshake before installing TLS/ALPN into its tunnel. */
    private final class ProxyConnectHandler extends ChannelInboundHandlerAdapter {
        private final Connection connection;
        private boolean accepted;

        private ProxyConnectHandler(Connection connection) {
            this.connection = connection;
        }

        @Override
        public void channelActive(ChannelHandlerContext context) {
            var authority = connection.route.originAuthority();
            var connect = new DefaultFullHttpRequest(
                    HttpVersion.HTTP_1_1, HttpMethod.CONNECT, authority, Unpooled.EMPTY_BUFFER);
            connect.headers().set(HttpHeaderNames.HOST, authority);
            connect.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, 0);
            context.writeAndFlush(connect).addListener(written -> {
                if (!written.isSuccess()) {
                    connection.failAll(written.cause());
                    context.close();
                }
            });
            context.fireChannelActive();
        }

        @Override
        public void channelRead(ChannelHandlerContext context, Object message) {
            try {
                if (message instanceof HttpResponse response) {
                    if (accepted || response.status().code() != 200) {
                        connection.failAll(new IllegalStateException(
                                "HTTP proxy CONNECT failed with status " + response.status().code()));
                        context.close();
                        return;
                    }
                    accepted = true;
                    if (message instanceof LastHttpContent) {
                        establishTunnel(context);
                    }
                    return;
                }
                if (message instanceof LastHttpContent) {
                    if (!accepted) {
                        connection.failAll(new IllegalStateException("HTTP proxy CONNECT ended before a 200 response"));
                        context.close();
                        return;
                    }
                    establishTunnel(context);
                    return;
                }
                connection.failAll(new IllegalStateException("HTTP proxy CONNECT returned unexpected content"));
                context.close();
            } finally {
                ReferenceCountUtil.release(message);
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext context, Throwable failure) {
            connection.failAll(failure);
            context.close();
        }

        private void establishTunnel(ChannelHandlerContext context) {
            var pipeline = context.pipeline();
            if (pipeline.get(HttpClientCodec.class) != null) {
                pipeline.remove(HttpClientCodec.class);
            }
            pipeline.remove(this);
            configureTlsAlpn(pipeline, connection);
        }
    }

    private void configureHttp2(ChannelHandlerContext context, Connection connection) {
        var settings = Http2Settings.defaultSettings()
                .maxConcurrentStreams((long) config.maximumConcurrentStreams())
                .maxHeaderListSize((long) config.maximumHeaderListBytes())
                .maxFrameSize(config.maximumFrameBytes())
                .initialWindowSize(config.initialStreamWindowBytes());
        var frameCodec = Http2FrameCodecBuilder.forClient()
                .initialSettings(settings)
                .validateHeaders(true)
                .validateRequiredPseudoHeaders(true)
                .decoderEnforceMaxConsecutiveEmptyDataFrames(8)
                .decoderEnforceMaxRstFramesPerWindow(16, 30)
                .build();
        context.pipeline().addLast(frameCodec);
        context.pipeline().addLast(new InitialConnectionWindowHandler(frameCodec, config.initialConnectionWindowBytes()));
        context.pipeline().addLast(new ParentLifecycleHandler(connection));
        context.pipeline().addLast(new Http2MultiplexHandler(new ChannelInboundHandlerAdapter()));
    }

    private final class ParentLifecycleHandler extends ChannelInboundHandlerAdapter {
        private final Connection connection;

        private ParentLifecycleHandler(Connection connection) {
            this.connection = connection;
        }

        @Override
        public void userEventTriggered(ChannelHandlerContext context, Object event) {
            if (event instanceof Http2ConnectionPrefaceAndSettingsFrameWrittenEvent) {
                connection.ready();
            }
            context.fireUserEventTriggered(event);
        }

        @Override
        public void channelRead(ChannelHandlerContext context, Object message) {
            if (message instanceof Http2GoAwayFrame goAway) {
                try {
                    connection.onGoAway(goAway);
                } finally {
                    ReferenceCountUtil.release(message);
                }
                return;
            }
            context.fireChannelRead(message);
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext context, Throwable failure) {
            connection.failAll(failure);
            context.close();
        }

        @Override
        public void channelInactive(ChannelHandlerContext context) {
            connectionClosed(connection);
            context.fireChannelInactive();
        }
    }

    private final class ResponseHandler extends ChannelInboundHandlerAdapter {
        private final Connection connection;
        private final TransportExchange exchange;
        private HttpResponse response;
        private ByteArrayOutputStream body;
        private boolean terminal;

        private ResponseHandler(Connection connection, TransportExchange exchange) {
            this.connection = connection;
            this.exchange = exchange;
        }

        @Override
        public void channelRead(ChannelHandlerContext context, Object message) {
            try {
                if (message instanceof HttpResponse next) {
                    receiveHead(next);
                    if (next instanceof LastHttpContent last) {
                        receiveContent(last);
                    }
                    return;
                }
                if (message instanceof HttpContent content) {
                    receiveContent(content);
                    return;
                }
                throw new IllegalStateException("Unexpected HTTP/2 stream message");
            } catch (Throwable failure) {
                terminal = true;
                connection.failStream(exchange, failure);
            } finally {
                ReferenceCountUtil.release(message);
            }
        }

        @Override
        public void userEventTriggered(ChannelHandlerContext context, Object event) {
            if (event instanceof Http2ResetFrame reset) {
                terminal = true;
                connection.failStream(exchange,
                        new IllegalStateException("HTTP/2 peer reset stream: " + reset.errorCode()));
                return;
            }
            context.fireUserEventTriggered(event);
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext context, Throwable failure) {
            terminal = true;
            connection.failStream(exchange, failure);
        }

        @Override
        public void channelInactive(ChannelHandlerContext context) {
            if (!terminal) {
                connection.streamClosed(exchange);
            }
            context.fireChannelInactive();
        }

        private void receiveHead(HttpResponse next) {
            var status = next.status().code();
            if (status >= 100 && status < 200) {
                throw new IllegalStateException("HTTP/2 informational responses are not supported by the byte client");
            }
            if (response != null) {
                throw new IllegalStateException("Received multiple final HTTP/2 response heads");
            }
            connection.toWaveHeaders(next.headers());
            response = next;
            body = new ByteArrayOutputStream(Math.min(INITIAL_LINE_LIMIT, connection.maximumInboundResponseBytes));
        }

        private void receiveContent(HttpContent content) {
            if (response == null) {
                throw new IllegalStateException("Received HTTP/2 content before a response head");
            }
            ByteBuf bytes = content.content();
            var readable = bytes.readableBytes();
            if (readable > connection.maximumInboundResponseBytes - body.size()) {
                throw new ClientLimitExceededException(
                        "HTTP/2 inbound stream bytes",
                        connection.maximumInboundResponseBytes,
                        (long) body.size() + readable);
            }
            var copy = new byte[readable];
            bytes.getBytes(bytes.readerIndex(), copy);
            body.writeBytes(copy);
            if (!(content instanceof LastHttpContent)) {
                return;
            }
            terminal = true;
            connection.completeStream(exchange,
                    new ClientTransport.TransportResponse(response.status().code(), connection.toWaveHeaders(response.headers()), body.toByteArray()));
        }
    }

    /** Raises the Netty connection receive window only when the explicit bounded setting needs it. */
    private static final class InitialConnectionWindowHandler extends ChannelInboundHandlerAdapter {
        private final Http2FrameCodec codec;
        private final int configuredWindow;

        private InitialConnectionWindowHandler(Http2FrameCodec codec, int configuredWindow) {
            this.codec = codec;
            this.configuredWindow = configuredWindow;
        }

        @Override
        public void handlerAdded(ChannelHandlerContext context) {
            var controller = codec.decoder().flowController();
            var connectionStream = codec.connection().connectionStream();
            var delta = configuredWindow - controller.windowSize(connectionStream);
            if (delta > 0) {
                try {
                    controller.incrementWindowSize(connectionStream, delta);
                } catch (io.netty.handler.codec.http2.Http2Exception failure) {
                    context.close();
                }
            }
        }
    }

    static final class TransportExchange extends CancellationBridge.CancellationHandle implements ClientTransport.Exchange {
        private final NettyHttp2ClientTransport owner;
        private final CompletableFuture<ClientTransport.TransportResponse> completion = new CompletableFuture<>();
        private final AtomicBoolean abortRequested = new AtomicBoolean();
        private volatile Connection connection;
        private volatile Channel stream;
        private volatile ClientTransport.Exchange fallbackExchange;

        private TransportExchange(NettyHttp2ClientTransport owner) {
            this.owner = owner;
        }

        @Override
        public CompletableFuture<ClientTransport.TransportResponse> completion() {
            return completion;
        }

        @Override
        public CancellationBridge.CancellationHandle cancellationHandle() {
            return this;
        }

        @Override
        public boolean isDone() {
            return completion.isDone();
        }

        @Override
        public void requestAbort() {
            if (completion.isDone() || !abortRequested.compareAndSet(false, true)) {
                return;
            }
            var fallback = fallbackExchange;
            if (fallback != null) {
                fallback.cancellationHandle().requestAbort();
                return;
            }
            if (!owner.submit(() -> owner.abort(this))) {
                completeExceptionally(new CancellationException("HTTP/2 transport exchange cancelled"));
            }
        }

        boolean abortRequested() {
            return abortRequested.get();
        }

        Connection connection() {
            return connection;
        }

        ClientTransport.Exchange fallbackExchange() {
            return fallbackExchange;
        }

        void bindConnection(Connection connection) {
            this.connection = Objects.requireNonNull(connection, "connection");
        }

        void bindStream(Channel stream) {
            this.stream = Objects.requireNonNull(stream, "stream");
        }

        void bindFallback(ClientTransport.Exchange exchange) {
            fallbackExchange = Objects.requireNonNull(exchange, "exchange");
            exchange.completion().whenComplete((response, failure) -> {
                if (failure == null) {
                    completion.complete(response);
                } else {
                    completion.completeExceptionally(failure);
                }
            });
            if (abortRequested()) {
                exchange.cancellationHandle().requestAbort();
            }
        }

        void complete(ClientTransport.TransportResponse response) {
            completion.complete(response);
        }

        void completeExceptionally(Throwable failure) {
            completion.completeExceptionally(failure);
        }

        void completeCancelledWithoutStream() {
            completion.completeExceptionally(new CancellationException("HTTP/2 transport exchange cancelled before stream open"));
        }
    }

    private record PendingStart(TransportExchange exchange, ClientRequest request, RouteKey route) {
    }

    private record PendingStream(TransportExchange exchange, ClientRequest request) {
    }

    private record RouteKey(
            String host,
            int port,
            String originHost,
            int originPort,
            boolean proxy) {
        private static RouteKey from(URI uri, ProxyPolicy proxyPolicy) {
            var originPort = uri.getPort() < 0 ? 443 : uri.getPort();
            if (proxyPolicy.isDirect()) {
                return new RouteKey(uri.getHost(), originPort, uri.getHost(), originPort, false);
            }
            var proxy = proxyPolicy.endpoint().orElseThrow();
            var proxyPort = proxy.getPort() < 0 ? 80 : proxy.getPort();
            return new RouteKey(proxy.getHost(), proxyPort, uri.getHost(), originPort, true);
        }

        private String originAuthority() {
            var displayHost = originHost.indexOf(':') >= 0 ? '[' + originHost + ']' : originHost;
            return originPort == 443 ? displayHost : displayHost + ':' + originPort;
        }
    }
}



