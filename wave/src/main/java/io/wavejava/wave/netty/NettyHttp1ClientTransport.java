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
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslHandler;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.DefaultThreadFactory;
import io.wavejava.wave.api.http.Headers;
import io.wavejava.wave.runtime.client.CancellationBridge;
import io.wavejava.wave.runtime.client.ClientTransport;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.channels.ClosedChannelException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
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
 * Package-private Netty HTTP/1.1 transport owned by one {@link WaveClient}.
 *
 * <p>The transport deliberately serializes connection state on one private Netty event loop. An
 * exchange's {@linkplain TransportExchange#completion() physical completion} is not its logical
 * caller future: it completes only after a complete response has returned an HTTP/1.1 channel to
 * this bounded idle pool, or after the channel's {@code closeFuture} has fired. That distinction
 * is what lets {@link WaveClient} retain a {@link ClientRequestPool.Lease} until cancellation has reached
 * the actual socket.</p>
 *
 * <p>Channels are capped at {@link ClientRequestPool#maximumConcurrentRequests()} per client instance. Idle
 * channels are route-keyed for reuse and globally LRU-evicted across origins before a new route
 * may allocate another channel; the origin map therefore cannot grow without bound. A caller-owned
 * {@link ClientRequestPool} shares admission only. Each {@code WaveClient} still owns an independent,
 * identically bounded physical channel cache.</p>
 */
public final class NettyHttp1ClientTransport implements ClientTransport {
    private static final int INITIAL_LINE_LIMIT = 8 * 1024;
    private static final int RESPONSE_CHUNK_LIMIT = 8 * 1024;
    private static final Set<String> TRANSPORT_OWNED_REQUEST_HEADERS = Set.of(
            "host",
            "content-length",
            "transfer-encoding",
            "connection",
            "keep-alive",
            "proxy-connection",
            "upgrade",
            "trailer",
            "te");

    private final NioEventLoopGroup group;
    private final EventLoop eventLoop;
    private final Bootstrap bootstrap;
    private final SslContext tlsContext;
    private final ProxyPolicy proxyPolicy;
    private final int maximumPhysicalChannels;
    private final int maximumResponseBodyBytes;
    private final int maximumResponseHeaders;
    private final int maximumResponseHeaderBytes;
    private final int decoderHeaderBytes;

    // Every member below is accessed only from eventLoop.
    private final Map<RouteKey, ArrayDeque<Connection>> idleByRoute = new HashMap<>();
    private final ArrayDeque<Connection> idleLru = new ArrayDeque<>();
    private final Set<Connection> openConnections = new HashSet<>();
    private final ArrayDeque<PendingStart> pendingStarts = new ArrayDeque<>();
    private final AtomicBoolean closing = new AtomicBoolean();
    private final CompletableFuture<Void> closeCompletion = new CompletableFuture<>();
    private boolean shutdownStarted;

    NettyHttp1ClientTransport(ClientRequestPool pool, ProxyPolicy proxyPolicy) {
        this(pool, proxyPolicy, ClientTlsConfig.system());
    }

    public NettyHttp1ClientTransport(ClientRequestPool pool, ProxyPolicy proxyPolicy, ClientTlsConfig tls) {
        Objects.requireNonNull(pool, "pool");
        this.proxyPolicy = Objects.requireNonNull(proxyPolicy, "proxyPolicy");
        Objects.requireNonNull(tls, "tls");
        maximumPhysicalChannels = pool.maximumConcurrentRequests();
        maximumResponseBodyBytes = pool.maximumResponseBodyBytes();
        maximumResponseHeaders = pool.maximumResponseHeaders();
        maximumResponseHeaderBytes = pool.maximumResponseHeaderBytes();
        decoderHeaderBytes = Math.max(8 * 1024, maximumResponseHeaderBytes);
        try {
            // SNI is supplied to newHandler below; endpoint identification makes a direct HTTPS
            // route obey the same hostname-verification contract as a normal HTTPS client.
            var builder = SslContextBuilder.forClient().endpointIdentificationAlgorithm("HTTPS");
            tls.trustCertificateChain().ifPresent(path -> builder.trustManager(path.toFile()));
            tlsContext = builder.build();
        } catch (Exception failure) {
            throw new IllegalStateException("Could not initialize the Netty TLS client context", failure);
        }
        group = new NioEventLoopGroup(1, new DefaultThreadFactory("wave-client-io", true));
        eventLoop = (EventLoop) group.next();
        bootstrap = new Bootstrap()
                .group(group)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.TCP_NODELAY, true)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, timeoutMillis(pool.connectTimeout()));
    }

    /** Starts one physical HTTP/1.1 exchange or returns an exchange awaiting bounded capacity. */
    @Override
    public TransportExchange send(ClientRequest request) {
        Objects.requireNonNull(request, "request");
        validateRequest(request);
        if (closing.get()) {
            throw new IllegalStateException("WaveClient transport is closed");
        }
        var exchange = new TransportExchange(this);
        var route = RouteKey.from(request.uri(), proxyPolicy);
        if (!submit(() -> begin(exchange, request, route))) {
            exchange.completeExceptionally(new IllegalStateException("WaveClient transport is closed"));
        }
        return exchange;
    }

    /**
     * Starts transport shutdown and returns only after every owned channel has reached
     * {@code closeFuture} and the Netty event loop has stopped.
     */
    @Override
    public CompletionStage<Void> closeAsync() {
        if (closing.compareAndSet(false, true)) {
            if (!submit(this::beginShutdown)) {
                // A rejected task can only occur after the loop has already begun stopping. There
                // is no remaining event-loop work to keep alive; callers must not hang in close().
                closeCompletion.complete(null);
            }
        }
        return closeCompletion;
    }

    /** Package-private deterministic probe for the physical channel-cap contract. */
    TransportSnapshot snapshot() {
        if (eventLoop.inEventLoop()) {
            return snapshotOnEventLoop();
        }
        if (closeCompletion.isDone()) {
            return new TransportSnapshot(0, 0, 0);
        }
        var result = new CompletableFuture<TransportSnapshot>();
        if (!submit(() -> result.complete(snapshotOnEventLoop()))) {
            return new TransportSnapshot(0, 0, 0);
        }
        return result.join();
    }

    private TransportSnapshot snapshotOnEventLoop() {
        return new TransportSnapshot(openConnections.size(), idleLru.size(), pendingStarts.size());
    }

    private void begin(TransportExchange exchange, ClientRequest request, RouteKey route) {
        if (exchange.abortRequested()) {
            exchange.completeCancelledWithoutChannel();
            return;
        }
        if (closing.get()) {
            exchange.completeExceptionally(new CancellationException("WaveClient transport closed"));
            return;
        }
        var idle = takeIdle(route);
        if (idle != null) {
            idle.activate(exchange, request);
            return;
        }
        if (openConnections.size() < maximumPhysicalChannels) {
            openAndActivate(exchange, request, route);
            return;
        }

        // No idle connection for the route. Closing the least-recently-idle channel first keeps
        // total live + idle channels at the configured cap, even across an unbounded sequence of
        // distinct target origins.
        var evicted = takeOldestIdle();
        pendingStarts.addLast(new PendingStart(exchange, request, route));
        if (evicted != null) {
            evicted.closeForEviction();
        }
    }

    private void openAndActivate(TransportExchange exchange, ClientRequest request, RouteKey route) {
        if (exchange.abortRequested()) {
            exchange.completeCancelledWithoutChannel();
            return;
        }
        if (closing.get()) {
            exchange.completeExceptionally(new CancellationException("WaveClient transport closed"));
            return;
        }
        var connection = new Connection(route);
        openConnections.add(connection);
        connection.activate(exchange, request);
        connection.connect();
    }

    private void abort(TransportExchange exchange) {
        removePending(exchange);
        var connection = exchange.connection();
        if (connection == null) {
            exchange.completeCancelledWithoutChannel();
            return;
        }
        connection.abort(exchange);
    }

    private void abortAfterRejectedSubmission(TransportExchange exchange) {
        var connection = exchange.connection();
        if (connection == null) {
            exchange.completeCancelledWithoutChannel();
            return;
        }
        connection.abortAfterLoopRejected(exchange);
    }

    private void returnIdle(
            Connection connection, TransportExchange exchange, ClientTransport.TransportResponse response) {
        if (closing.get() || !connection.isConfirmedReusable(exchange)) {
            connection.completeAfterClose(exchange, response, null);
            return;
        }
        connection.clearActive(exchange);
        idleByRoute.computeIfAbsent(connection.route, ignored -> new ArrayDeque<>()).addLast(connection);
        idleLru.addLast(connection);
        // Returning to the bounded idle cache is the physical completion point for a normal
        // keep-alive response. Only now may WaveClient release its admission lease.
        exchange.complete(response);
        drainPendingStarts();
    }

    private void connectionClosed(Connection connection) {
        if (!openConnections.remove(connection)) {
            return;
        }
        removeIdle(connection);
        connection.finishAfterClose();
        drainPendingStarts();
        completeShutdownIfDrained();
    }

    private void drainPendingStarts() {
        while (!pendingStarts.isEmpty()) {
            var pending = pendingStarts.removeFirst();
            if (pending.exchange.abortRequested()) {
                pending.exchange.completeCancelledWithoutChannel();
                continue;
            }
            if (closing.get()) {
                pending.exchange.completeExceptionally(new CancellationException("WaveClient transport closed"));
                continue;
            }
            var idle = takeIdle(pending.route);
            if (idle != null) {
                idle.activate(pending.exchange, pending.request);
                continue;
            }
            if (openConnections.size() < maximumPhysicalChannels) {
                openAndActivate(pending.exchange, pending.request, pending.route);
                continue;
            }
            var evicted = takeOldestIdle();
            pendingStarts.addFirst(pending);
            if (evicted != null) {
                evicted.closeForEviction();
            }
            return;
        }
    }

    private Connection takeIdle(RouteKey route) {
        var candidates = idleByRoute.get(route);
        if (candidates == null) {
            return null;
        }
        while (!candidates.isEmpty()) {
            var connection = candidates.removeFirst();
            idleLru.remove(connection);
            if (connection.channelIsUsable()) {
                if (candidates.isEmpty()) {
                    idleByRoute.remove(route);
                }
                return connection;
            }
            connection.closeForEviction();
        }
        idleByRoute.remove(route);
        return null;
    }

    private Connection takeOldestIdle() {
        while (!idleLru.isEmpty()) {
            var connection = idleLru.removeFirst();
            var candidates = idleByRoute.get(connection.route);
            if (candidates != null) {
                candidates.remove(connection);
                if (candidates.isEmpty()) {
                    idleByRoute.remove(connection.route);
                }
            }
            if (connection.channelIsUsable()) {
                return connection;
            }
            connection.closeForEviction();
        }
        return null;
    }

    private void removeIdle(Connection connection) {
        idleLru.remove(connection);
        var candidates = idleByRoute.get(connection.route);
        if (candidates != null) {
            candidates.remove(connection);
            if (candidates.isEmpty()) {
                idleByRoute.remove(connection.route);
            }
        }
    }

    private void removePending(TransportExchange exchange) {
        pendingStarts.removeIf(pending -> pending.exchange == exchange);
    }

    private void beginShutdown() {
        for (var pending : pendingStarts) {
            pending.exchange.completeExceptionally(new CancellationException("WaveClient transport closed"));
        }
        pendingStarts.clear();
        for (var connection : Set.copyOf(openConnections)) {
            connection.closeForShutdown();
        }
        completeShutdownIfDrained();
    }

    private void completeShutdownIfDrained() {
        if (!closing.get() || !openConnections.isEmpty() || shutdownStarted) {
            return;
        }
        shutdownStarted = true;
        group.shutdownGracefully(0, 5, TimeUnit.SECONDS).addListener(future -> {
            if (future.isSuccess()) {
                closeCompletion.complete(null);
            } else {
                closeCompletion.completeExceptionally(future.cause());
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
        if (!request.uri().getScheme().equalsIgnoreCase("http")
                && !request.uri().getScheme().equalsIgnoreCase("https")) {
            throw new IllegalArgumentException("Client request must use HTTP(S): " + request.uri());
        }
        if (request.method().name().equals("CONNECT")) {
            // HttpClientCodec intentionally enters raw tunnel mode after a successful CONNECT.
            // Wave's byte-response API has no tunnel contract, so accepting it here could return
            // a non-HTTP channel to the reusable cache.
            throw new UnsupportedOperationException(
                    "CONNECT requires a tunnel API and is not supported by the 0.4 HTTP/1.1 client");
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
        var defaultPort = uri.getScheme().equalsIgnoreCase("https") ? 443 : 80;
        return port < 0 || port == defaultPort ? displayHost : displayHost + ':' + port;
    }

    /**
     * The physical transport handle registered with {@link CancellationBridge}.
     *
     * <p>It intentionally is not a {@code Future}. A cancellation request is logically immediate,
     * but its completion stage remains pending until a channel is either returned idle or closed.</p>
     */
    static final class TransportExchange extends CancellationBridge.CancellationHandle implements ClientTransport.Exchange {
        private final NettyHttp1ClientTransport owner;
        private final CompletableFuture<ClientTransport.TransportResponse> completion = new CompletableFuture<>();
        private final AtomicBoolean abortRequested = new AtomicBoolean();
        private volatile Connection connection;

        private TransportExchange(NettyHttp1ClientTransport owner) {
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
            if (!owner.submit(() -> owner.abort(this))) {
                owner.abortAfterRejectedSubmission(this);
            }
        }

        boolean abortRequested() {
            return abortRequested.get();
        }

        Connection connection() {
            return connection;
        }

        void bind(Connection connection) {
            if (this.connection != null) {
                throw new IllegalStateException("Transport exchange already owns a channel");
            }
            this.connection = Objects.requireNonNull(connection, "connection");
        }

        void complete(ClientTransport.TransportResponse response) {
            completion.complete(response);
        }

        void completeExceptionally(Throwable failure) {
            completion.completeExceptionally(failure);
        }

        void completeCancelledWithoutChannel() {
            completion.completeExceptionally(new CancellationException("transport exchange cancelled before connect"));
        }
    }

    private final class Connection {
        private final RouteKey route;
        private volatile Channel channel;
        private ChannelFuture connectFuture;
        private TransportExchange active;
        private ClientRequest activeRequest;
        private ClientResponseHandler responseHandler;
        private ClientTransport.TransportResponse terminalResponse;
        private Throwable terminalFailure;
        private boolean connected;
        private boolean closeRequested;

        private Connection(RouteKey route) {
            this.route = route;
        }

        private void activate(TransportExchange exchange, ClientRequest request) {
            if (active != null) {
                throw new IllegalStateException("HTTP/1.1 channel already has an active exchange");
            }
            active = exchange;
            activeRequest = request;
            exchange.bind(this);
            if (exchange.abortRequested()) {
                abort(exchange);
                return;
            }
            if (connected) {
                writeRequest(exchange, request);
            }
        }

        private void connect() {
            final ChannelFuture future;
            try {
                future = bootstrap.clone()
                        .handler(new ClientChannelInitializer(this))
                        .connect(route.connectHost, route.connectPort);
            } catch (Throwable failure) {
                terminalFailure = failure;
                // Bootstrap/channel-initializer failures can occur synchronously before a channel
                // exists. Remove the reservation and complete the physical stage immediately so
                // its ClientRequestPool lease cannot remain stranded.
                connectionClosed(this);
                return;
            }
            connectFuture = future;
            channel = future.channel();
            channel.closeFuture().addListener(ignored -> {
                // Netty completes closeFuture before it fires channelInactive. Queue terminal
                // accounting behind the current close task so a valid close-delimited response
                // can first record its accumulated body in ClientResponseHandler.channelInactive.
                if (!submit(() -> connectionClosed(this))) {
                    connectionClosed(this);
                }
            });
            future.addListener(connectedFuture -> {
                if (!connectedFuture.isSuccess()) {
                    if (terminalFailure == null) {
                        terminalFailure = active != null && active.abortRequested()
                                ? new CancellationException("transport exchange cancelled")
                                : connectedFuture.cause();
                    }
                    requestClose();
                    return;
                }
                connected = true;
                if (active == null) {
                    requestClose();
                    return;
                }
                if (active.abortRequested()) {
                    abort(active);
                    return;
                }
                if (route.tls) {
                    var ssl = channel.pipeline().get(SslHandler.class);
                    ssl.handshakeFuture().addListener(handshake -> {
                        if (!handshake.isSuccess()) {
                            fail(active, handshake.cause());
                            return;
                        }
                        if (active == null || active.abortRequested()) {
                            if (active != null) {
                                abort(active);
                            } else {
                                requestClose();
                            }
                            return;
                        }
                        writeRequest(active, activeRequest);
                    });
                    return;
                }
                writeRequest(active, activeRequest);
            });
        }

        private void writeRequest(TransportExchange exchange, ClientRequest request) {
            if (active != exchange || exchange.abortRequested()) {
                if (active == exchange) {
                    abort(exchange);
                }
                return;
            }
            final DefaultFullHttpRequest outbound;
            try {
                var body = request.body();
                var target = route.proxy ? request.uri().toASCIIString() : originForm(request.uri());
                outbound = new DefaultFullHttpRequest(
                        HttpVersion.HTTP_1_1,
                        HttpMethod.valueOf(request.method().name()),
                        target,
                        Unpooled.wrappedBuffer(body));
                request.headers().asMap().forEach((name, values) -> values.forEach(value -> outbound.headers().add(name, value)));
                outbound.headers().set(HttpHeaderNames.HOST, hostHeader(request.uri()));
                outbound.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, body.length);
                outbound.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
            } catch (Throwable failure) {
                fail(exchange, failure);
                return;
            }
            channel.writeAndFlush(outbound).addListener(write -> {
                if (!write.isSuccess()) {
                    fail(exchange, write.cause());
                }
            });
        }

        private void receiveResponse(HttpResponse response, byte[] body, boolean reusable) {
            var exchange = active;
            if (exchange == null) {
                requestClose();
                return;
            }
            var result = new ClientTransport.TransportResponse(response.status().code(), toWaveHeaders(response.headers()), body);
            if (reusable) {
                returnIdle(this, exchange, result);
            } else {
                completeAfterClose(exchange, result, null);
            }
        }

        private void fail(TransportExchange exchange, Throwable failure) {
            if (active != exchange || terminalFailure != null || terminalResponse != null) {
                return;
            }
            terminalFailure = failure == null ? new ClosedChannelException() : failure;
            requestClose();
        }

        private void abort(TransportExchange exchange) {
            if (active != exchange || terminalResponse != null || terminalFailure != null) {
                return;
            }
            terminalFailure = new CancellationException("transport exchange cancelled");
            if (connectFuture != null && !connectFuture.isDone()) {
                connectFuture.cancel(false);
            }
            requestClose();
        }

        private void abortAfterLoopRejected(TransportExchange exchange) {
            var currentChannel = channel;
            if (currentChannel == null) {
                exchange.completeCancelledWithoutChannel();
                return;
            }
            var cancellation = new CancellationException("transport exchange cancelled");
            if (currentChannel.closeFuture().isDone()) {
                exchange.completeExceptionally(cancellation);
                return;
            }
            // Channel.close() is safe from a caller thread. If its event loop is already stopping,
            // the close future still supplies the only valid physical release signal.
            currentChannel.closeFuture().addListener(ignored -> exchange.completeExceptionally(cancellation));
            currentChannel.close();
        }

        private void completeAfterClose(
                TransportExchange exchange, ClientTransport.TransportResponse response, Throwable failure) {
            if (active != exchange || terminalResponse != null || terminalFailure != null) {
                return;
            }
            terminalResponse = response;
            terminalFailure = failure;
            requestClose();
        }

        private void closeForEviction() {
            if (active != null) {
                throw new IllegalStateException("Only idle channels may be evicted");
            }
            requestClose();
        }

        private void closeForShutdown() {
            if (active != null && terminalResponse == null && terminalFailure == null) {
                terminalFailure = new CancellationException("WaveClient transport closed");
            }
            requestClose();
        }

        private void requestClose() {
            if (closeRequested) {
                return;
            }
            closeRequested = true;
            if (channel == null) {
                return;
            }
            channel.close();
        }

        private boolean channelIsUsable() {
            return channel != null && connected && channel.isActive() && !closeRequested && active == null;
        }

        private boolean channelIsOpenForCurrentExchange() {
            return channel != null && connected && channel.isActive() && !closeRequested;
        }

        private boolean isConfirmedReusable(TransportExchange exchange) {
            return active == exchange
                    && channel != null
                    && channel.isActive()
                    && !closeRequested
                    && connected
                    && route.http11();
        }

        private void clearActive(TransportExchange exchange) {
            if (active != exchange) {
                throw new IllegalStateException("Unexpected active HTTP/1.1 exchange");
            }
            active = null;
            activeRequest = null;
        }

        private void finishAfterClose() {
            var exchange = active;
            active = null;
            activeRequest = null;
            if (exchange == null) {
                return;
            }
            if (terminalResponse == null && terminalFailure == null && responseHandler != null) {
                try {
                    terminalResponse = responseHandler.takeCloseDelimitedResponse();
                } catch (Throwable failure) {
                    terminalFailure = failure;
                }
            }
            if (terminalResponse != null) {
                exchange.complete(terminalResponse);
                return;
            }
            if (terminalFailure != null) {
                exchange.completeExceptionally(terminalFailure);
                return;
            }
            exchange.completeExceptionally(new ClosedChannelException());
        }

        private Headers toWaveHeaders(HttpHeaders nettyHeaders) {
            var headers = Headers.builder();
            long count = 0;
            long bytes = 0;
            for (var entry : nettyHeaders) {
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

    private final class ClientChannelInitializer extends ChannelInitializer<SocketChannel> {
        private final Connection connection;

        private ClientChannelInitializer(Connection connection) {
            this.connection = connection;
        }

        @Override
        protected void initChannel(SocketChannel channel) {
            if (connection.route.tls) {
                channel.pipeline().addLast(tlsContext.newHandler(
                        channel.alloc(), connection.route.originHost, connection.route.originPort));
            }
            channel.pipeline().addLast(new HttpClientCodec(
                    INITIAL_LINE_LIMIT, decoderHeaderBytes, RESPONSE_CHUNK_LIMIT));
            var responseHandler = new ClientResponseHandler(connection);
            connection.responseHandler = responseHandler;
            channel.pipeline().addLast(responseHandler);
        }
    }

    private final class ClientResponseHandler extends ChannelInboundHandlerAdapter {
        private final Connection connection;
        private HttpResponse response;
        private ByteArrayOutputStream body;
        private boolean reusable;

        private ClientResponseHandler(Connection connection) {
            this.connection = connection;
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
                connection.fail(connection.active, new IllegalStateException("Unexpected HTTP client message"));
            } catch (Throwable failure) {
                connection.fail(connection.active, failure);
            } finally {
                ReferenceCountUtil.release(message);
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext context, Throwable failure) {
            connection.fail(connection.active, failure);
        }

        @Override
        public void channelInactive(ChannelHandlerContext context) {
            // HTTP/1.1 permits a response body delimited by connection close. It becomes a
            // successful response only here, after the physical close has occurred; a truncated
            // Content-Length/chunked response remains a transport failure.
            var closeDelimited = takeCloseDelimitedResponse();
            if (closeDelimited != null) {
                connection.completeAfterClose(connection.active, closeDelimited, null);
            } else if (response != null) {
                connection.fail(connection.active, new ClosedChannelException());
            }
            context.fireChannelInactive();
        }

        private ClientTransport.TransportResponse takeCloseDelimitedResponse() {
            if (response == null || HttpUtil.isContentLengthSet(response) || HttpUtil.isTransferEncodingChunked(response)) {
                return null;
            }
            var completedResponse = response;
            var completedBody = body.toByteArray();
            response = null;
            body = null;
            reusable = false;
            return new ClientTransport.TransportResponse(
                    completedResponse.status().code(), connection.toWaveHeaders(completedResponse.headers()), completedBody);
        }

        private void receiveHead(HttpResponse next) {
            var status = next.status().code();
            if (status >= 100 && status < 200) {
                if (status == 101) {
                    throw new IllegalStateException("HTTP protocol upgrades are not supported by the 0.4 client");
                }
                return;
            }
            if (response != null || connection.active == null) {
                throw new IllegalStateException("Received an HTTP response without one active exchange");
            }
            // Convert/check the headers before allocating any response body. receiveResponse will
            // build the final immutable copy after LastHttpContent; doing this now preserves a
            // meaningful limit failure and closes the channel before the body can grow.
            connection.toWaveHeaders(next.headers());
            response = next;
            body = new ByteArrayOutputStream(Math.min(RESPONSE_CHUNK_LIMIT, maximumResponseBodyBytes));
            reusable = next.protocolVersion().equals(HttpVersion.HTTP_1_1) && HttpUtil.isKeepAlive(next);
        }

        private void receiveContent(HttpContent content) {
            if (response == null) {
                // Informational responses can carry an empty LastHttpContent before the final
                // response. They deliberately do not consume the active HTTP/1.1 exchange.
                if (content instanceof LastHttpContent) {
                    return;
                }
                throw new IllegalStateException("Received HTTP content before a final response head");
            }
            ByteBuf bytes = content.content();
            var readable = bytes.readableBytes();
            if (readable > maximumResponseBodyBytes - body.size()) {
                throw new ClientLimitExceededException(
                        "response body bytes", maximumResponseBodyBytes, (long) body.size() + readable);
            }
            var copy = new byte[readable];
            bytes.getBytes(bytes.readerIndex(), copy);
            body.writeBytes(copy);
            if (!(content instanceof LastHttpContent)) {
                return;
            }
            var completedResponse = response;
            var completedBody = body.toByteArray();
            var keepAlive = reusable && connection.channelIsOpenForCurrentExchange();
            response = null;
            body = null;
            reusable = false;
            connection.receiveResponse(completedResponse, completedBody, keepAlive);
        }
    }

    private record PendingStart(TransportExchange exchange, ClientRequest request, RouteKey route) {
    }

    record TransportSnapshot(int liveChannels, int idleChannels, int pendingStarts) {
    }

    private record RouteKey(
            String connectHost,
            int connectPort,
            String originHost,
            int originPort,
            boolean tls,
            boolean proxy,
            boolean http11) {
        private static RouteKey from(URI target, ProxyPolicy proxyPolicy) {
            var targetTls = target.getScheme().equalsIgnoreCase("https");
            if (proxyPolicy.isDirect()) {
                return new RouteKey(
                        target.getHost(), effectivePort(target), target.getHost(), effectivePort(target), targetTls, false, true);
            }
            if (targetTls) {
                // CONNECT is intentionally deferred until the HTTP/2/client transport release.
                // Failing here is deterministic and happens before Bootstrap allocates a socket.
                throw new UnsupportedOperationException(
                        "HTTPS through an HTTP proxy requires CONNECT and is not supported by the 0.4 Netty client");
            }
            var proxy = proxyPolicy.endpoint().orElseThrow();
            return new RouteKey(
                    proxy.getHost(),
                    effectivePort(proxy),
                    // HTTP absolute-form proxy channels are reusable across origin authorities;
                    // only a direct TLS route needs the origin authority for SNI/verification.
                    proxy.getHost(),
                    effectivePort(proxy),
                    false,
                    true,
                    true);
        }

        private static int effectivePort(URI uri) {
            if (uri.getPort() >= 0) {
                return uri.getPort();
            }
            return uri.getScheme().equalsIgnoreCase("https") ? 443 : 80;
        }
    }
}


