package io.wavejava.wave.netty;

import io.wavejava.wave.api.websocket.*;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoop;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.TooLongFrameException;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.websocketx.WebSocketClientHandshaker;
import io.netty.handler.codec.http.websocketx.WebSocketClientHandshakerFactory;
import io.netty.handler.codec.http.websocketx.WebSocket13FrameEncoder;
import io.netty.handler.codec.http.websocketx.WebSocketFrameAggregator;
import io.netty.handler.codec.http.websocketx.WebSocketVersion;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.util.concurrent.DefaultThreadFactory;
import io.wavejava.wave.api.http.Headers;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Owned Netty implementation for bounded direct-{@code ws} client connections. */
public final class NettyWebSocketClient implements WebSocketClient {
    private static final int MAXIMUM_INITIAL_LINE_BYTES = 8 * 1024;
    private static final int MAXIMUM_HANDSHAKE_CONTENT_BYTES = 8 * 1024;

    private final WebSocketClientOptions config;
    private final NioEventLoopGroup group;
    private final EventLoop eventLoop;
    private final Bootstrap bootstrap;
    private final Semaphore admissions;
    private final ExecutorService callbackExecutor;
    private final Set<Attempt> attempts = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closing = new AtomicBoolean();

    public NettyWebSocketClient(WebSocketClientOptions config) {
        this.config = Objects.requireNonNull(config, "config");
        group = new NioEventLoopGroup(1, new DefaultThreadFactory("wave-websocket-client-io", true));
        eventLoop = group.next();
        bootstrap = new Bootstrap()
                .group(group)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.TCP_NODELAY, true)
                .option(ChannelOption.AUTO_READ, true);
        admissions = new Semaphore(config.maximumConnections(), true);
        callbackExecutor = Executors.newVirtualThreadPerTaskExecutor();
    }

    @Override
    public WebSocketClientRequest.Builder request(java.net.URI uri) {
        return WebSocketClientRequest.builder().uri(uri);
    }

    @Override
    public CompletionStage<WebSocketConnection> connect(WebSocketClientRequest request) {
        Objects.requireNonNull(request, "request");
        if (closing.get()) {
            return CompletableFuture.failedFuture(new IllegalStateException("WebSocketClient is closed"));
        }
        if (!admissions.tryAcquire()) {
            return CompletableFuture.failedFuture(new WebSocketClientRejectedException(config.maximumConnections()));
        }
        var attempt = new Attempt(request);
        attempts.add(attempt);
        if (attempt.isPhysicallyReleased()) {
            // A pre-cancelled request token can synchronously win while Attempt is being built.
            // Its permit is already returned, so do not retain a dead attempt in the close set.
            attempts.remove(attempt);
        }
        attempt.result.whenComplete((connection, failure) -> {
            if (attempt.result.isCancelled()) {
                attempt.cancel("WebSocket connect future cancelled");
            }
        });
        attempt.start();
        return attempt.result;
    }

    @Override
    public boolean isClosed() {
        return closing.get();
    }

    @Override
    public void close() {
        if (!closing.compareAndSet(false, true)) {
            return;
        }
        for (var attempt : attempts) {
            attempt.cancel("WebSocketClient closed");
        }
        // The group shutdown provides a final physical-close path for a connect that races the
        // snapshot above. Each admission remains held until its channel close future or failed
        // connect callback executes; logical cancellation alone never returns a permit.
        group.shutdownGracefully().addListener(ignored -> callbackExecutor.shutdown());
    }

    private final class Attempt {
        private final WebSocketClientRequest request;
        private final CompletableFuture<WebSocketConnection> result = new CompletableFuture<>();
        private final AtomicReference<Channel> channel = new AtomicReference<>();
        private final AtomicBoolean physicalReleased = new AtomicBoolean();
        private final AtomicBoolean cancellationRequested = new AtomicBoolean();
        private final Object startLock = new Object();
        private final Duration establishmentTimeout;
        private final io.wavejava.wave.api.http.CancellationToken.Registration cancellationRegistration;

        private volatile io.netty.util.concurrent.ScheduledFuture<?> establishmentDeadline;
        private volatile boolean connected;
        private boolean connectionStarted;

        private Attempt(WebSocketClientRequest request) {
            this.request = request;
            establishmentTimeout = effectiveEstablishmentTimeout(request);
            cancellationRegistration = request.cancellationToken()
                    .map(token -> token.onCancellation(reason -> cancel("WebSocket request cancelled: " + reason)))
                    .orElse(null);
        }

        private void start() {
            if (establishmentTimeout.isZero()) {
                fail(new WebSocketClientTimeoutException(Duration.ZERO, null));
                connectFailed();
                return;
            }
            try {
                eventLoop.execute(this::startOnEventLoop);
            } catch (RejectedExecutionException rejected) {
                fail(new IllegalStateException("WebSocketClient is closed", rejected));
                connectFailed();
            }
        }

        private void startOnEventLoop() {
            synchronized (startLock) {
                if (cancellationRequested.get() || closing.get() || result.isDone()) {
                    connectFailed();
                    return;
                }
                connectionStarted = true;
            }
            if (cancellationRequested.get() || closing.get()) {
                connectFailed();
                return;
            }
            armEstablishmentDeadline();
            final WebSocketClientHandshaker handshaker;
            final ChannelFuture connectFuture;
            try {
                handshaker = WebSocketClientHandshakerFactory.newHandshaker(
                        request.uri(),
                        WebSocketVersion.V13,
                        request.subprotocols().isEmpty() ? null : String.join(",", request.subprotocols()),
                        false,
                        toNettyHeaders(request.headers()),
                        config.limits().maximumFrameBytes());
                var timeoutMillis = toTimeoutMillis(min(config.connectTimeout(), establishmentTimeout));
                connectFuture = bootstrap.clone()
                        .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, timeoutMillis)
                        .handler(new ChannelInitializer<SocketChannel>() {
                            @Override
                            protected void initChannel(SocketChannel socket) {
                                var pipeline = socket.pipeline();
                                pipeline.addLast("waveWsHttpClientCodec", new HttpClientCodec(
                                        MAXIMUM_INITIAL_LINE_BYTES,
                                        config.maximumResponseHeaderBytes(),
                                        MAXIMUM_HANDSHAKE_CONTENT_BYTES));
                                pipeline.addLast("waveWsHandshakeAggregator",
                                        new HttpObjectAggregator(MAXIMUM_HANDSHAKE_CONTENT_BYTES));
                                pipeline.addLast("waveWsHandshake", new HandshakeHandler(Attempt.this, handshaker));
                            }
                        })
                        .connect(request.uri().getHost(), WebSocketRequestAdapter.effectivePort(request.uri()));
            } catch (RuntimeException failure) {
                fail(new WebSocketHandshakeException("WebSocket client could not start the upgrade", 0, Headers.empty(), failure));
                connectFailed();
                return;
            }
            connectFuture.addListener(future -> {
                if (!future.isSuccess()) {
                    fail(new WebSocketHandshakeException(
                            "WebSocket TCP connection failed", 0, Headers.empty(), future.cause()));
                    connectFailed();
                }
            });
        }

        private void attachChannel(Channel connectedChannel) {
            if (!channel.compareAndSet(null, connectedChannel)) {
                return;
            }
            connected = true;
            connectedChannel.closeFuture().addListener(ignored -> transportClosed());
            if (cancellationRequested.get() || closing.get()) {
                connectedChannel.close();
            }
        }

        private void handshakeSucceeded(WebSocketClientSession session) {
            cancelEstablishmentDeadline();
            if (result.complete(session)) {
                return;
            }
            // A deadline, cancellation, or caller cancellation won while the peer was replying.
            // No established connection may survive a failed logical connect stage.
            session.abort();
        }

        private void handshakeFailed(Throwable failure) {
            fail(failure);
        }

        private void cancel(String reason) {
            final boolean releaseWithoutChannel;
            synchronized (startLock) {
                cancellationRequested.set(true);
                releaseWithoutChannel = !connectionStarted;
            }
            if (!result.isDone()) {
                fail(new CancellationException(reason));
            }
            var activeChannel = channel.get();
            if (activeChannel != null) {
                activeChannel.close();
            }
            if (releaseWithoutChannel) {
                releasePhysical();
            }
        }

        private void fail(Throwable failure) {
            cancellationRequested.set(true);
            cancelEstablishmentDeadline();
            result.completeExceptionally(failure);
            var activeChannel = channel.get();
            if (activeChannel != null) {
                activeChannel.close();
            }
        }

        private void connectFailed() {
            if (!connected) {
                releasePhysical();
            }
        }

        private void transportClosed() {
            cancelEstablishmentDeadline();
            if (!result.isDone()) {
                result.completeExceptionally(new WebSocketClosedException(
                        "WebSocket connection closed before the HTTP upgrade completed"));
            }
            releasePhysical();
        }

        private void armEstablishmentDeadline() {
            final long delayNanos = toNanosSaturated(establishmentTimeout);
            establishmentDeadline = eventLoop.schedule(
                    (Runnable) () -> fail(new WebSocketClientTimeoutException(establishmentTimeout, null)),
                    delayNanos,
                    TimeUnit.NANOSECONDS);
        }

        private void cancelEstablishmentDeadline() {
            var deadline = establishmentDeadline;
            if (deadline != null) {
                deadline.cancel(false);
                establishmentDeadline = null;
            }
        }

        private void releasePhysical() {
            if (!physicalReleased.compareAndSet(false, true)) {
                return;
            }
            cancelEstablishmentDeadline();
            if (cancellationRegistration != null) {
                cancellationRegistration.close();
            }
            attempts.remove(this);
            admissions.release();
        }

        private boolean isPhysicallyReleased() {
            return physicalReleased.get();
        }
    }

    /** Performs the HTTP handshake and atomically switches the pipeline to a session owner. */
    private final class HandshakeHandler extends SimpleChannelInboundHandler<FullHttpResponse> {
        private final Attempt attempt;
        private final WebSocketClientHandshaker handshaker;

        private HandshakeHandler(Attempt attempt, WebSocketClientHandshaker handshaker) {
            this.attempt = attempt;
            this.handshaker = handshaker;
        }

        @Override
        public void channelActive(ChannelHandlerContext context) {
            attempt.attachChannel(context.channel());
            if (attempt.cancellationRequested.get()) {
                context.close();
                return;
            }
            handshaker.handshake(context.channel()).addListener(future -> {
                if (!future.isSuccess()) {
                    attempt.handshakeFailed(new WebSocketHandshakeException(
                            "WebSocket upgrade request write failed", 0, Headers.empty(), future.cause()));
                }
            });
            context.fireChannelActive();
        }

        @Override
        protected void channelRead0(ChannelHandlerContext context, FullHttpResponse response) {
            if (!response.decoderResult().isSuccess()) {
                var cause = response.decoderResult().cause();
                if (isHeaderLimitFailure(cause)) {
                    attempt.handshakeFailed(new WebSocketClientLimitExceededException(
                            "response header bytes", config.maximumResponseHeaderBytes(),
                            config.maximumResponseHeaderBytes() + 1L));
                } else {
                    attempt.handshakeFailed(new WebSocketHandshakeException(
                            "WebSocket upgrade response could not be decoded", 0, Headers.empty(), cause));
                }
                return;
            }
            var headers = toWaveHeaders(response.headers());
            if (response.headers().size() > config.maximumResponseHeaders()) {
                attempt.handshakeFailed(new WebSocketClientLimitExceededException(
                        "response header count", config.maximumResponseHeaders(), response.headers().size()));
                return;
            }
            var headerBytes = headerBytes(response.headers());
            if (headerBytes > config.maximumResponseHeaderBytes()) {
                attempt.handshakeFailed(new WebSocketClientLimitExceededException(
                        "response header bytes", config.maximumResponseHeaderBytes(), headerBytes));
                return;
            }
            try {
                handshaker.finishHandshake(context.channel(), response);
            } catch (Throwable failure) {
                attempt.handshakeFailed(new WebSocketHandshakeException(
                        "WebSocket upgrade was rejected or malformed", response.status().code(), headers, failure));
                return;
            }

            var pipeline = context.pipeline();
            if (pipeline.context("ws-decoder") == null) {
                attempt.handshakeFailed(new WebSocketHandshakeException(
                        "WebSocket handshaker did not install a frame decoder", response.status().code(), headers, null));
                return;
            }
            var selectedSubprotocol = handshaker.actualSubprotocol();
            var session = new WebSocketClientSession(
                    attempt.request,
                    attempt.request.uri(),
                    selectedSubprotocol == null || selectedSubprotocol.isBlank() ? null : selectedSubprotocol,
                    config.limits(),
                    callbackExecutor);
            // Netty's low-level handshaker installs the decoder but deliberately expects its
            // caller to install the matching outbound encoder. Both must be present before the
            // connection becomes observable to application code. Keep the session directly after
            // the decoder so an immediately queued first peer frame cannot fall through the old
            // HTTP pipeline during the asynchronous codec removal.
            pipeline.addBefore("ws-decoder", "waveWsClientEncoder", new WebSocket13FrameEncoder(true));
            pipeline.addAfter("ws-decoder", "waveWsClientFrameAggregator",
                    new WebSocketFrameAggregator(config.limits().maximumMessageBytes()));
            pipeline.addAfter("waveWsClientFrameAggregator", "waveWsClientSession", session);
            pipeline.addBefore("waveWsClientSession", "waveWsClientIdle",
                    new IdleStateHandler(0, 0, config.idleTimeout().toMillis(), TimeUnit.MILLISECONDS));
            session.activate();
            pipeline.remove(this);
            // finishHandshake queued removal of the legacy HttpClientCodec on this EventLoop.
            // Publish the connection on the next turn, after that structural transition, so a
            // synchronous user continuation cannot write a frame into a half-upgraded pipeline.
            context.executor().execute(() -> attempt.handshakeSucceeded(session));
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext context, Throwable cause) {
            if (isHeaderLimitFailure(cause)) {
                attempt.handshakeFailed(new WebSocketClientLimitExceededException(
                        "response header bytes", config.maximumResponseHeaderBytes(), config.maximumResponseHeaderBytes() + 1L));
            } else {
                attempt.handshakeFailed(new WebSocketHandshakeException(
                        "WebSocket upgrade transport failed", 0, Headers.empty(), cause));
            }
        }

        @Override
        public void channelInactive(ChannelHandlerContext context) {
            attempt.transportClosed();
            context.fireChannelInactive();
        }
    }

    private static io.netty.handler.codec.http.HttpHeaders toNettyHeaders(Headers headers) {
        HttpHeaders result = new DefaultHttpHeaders(false);
        headers.asMap().forEach((name, values) -> values.forEach(value -> result.add(name, value)));
        return result;
    }

    private static Headers toWaveHeaders(HttpHeaders headers) {
        var result = Headers.builder();
        for (var entry : headers) {
            result.add(entry.getKey(), entry.getValue());
        }
        return result.build();
    }

    private static long headerBytes(HttpHeaders headers) {
        long total = 0;
        for (var entry : headers) {
            total = Math.addExact(total, entry.getKey().getBytes(StandardCharsets.ISO_8859_1).length);
            total = Math.addExact(total, entry.getValue().getBytes(StandardCharsets.ISO_8859_1).length);
            total = Math.addExact(total, 4L); // ': ', CRLF
        }
        return total;
    }

    private static boolean isHeaderLimitFailure(Throwable failure) {
        for (var current = failure; current != null && current.getCause() != current; current = current.getCause()) {
            if (current instanceof TooLongFrameException
                    || current.getClass().getSimpleName().contains("TooLongHttpHeader")) {
                return true;
            }
        }
        return false;
    }

    private Duration effectiveEstablishmentTimeout(WebSocketClientRequest request) {
        var timeout = config.handshakeTimeout();
        if (request.timeout().isPresent() && request.timeout().orElseThrow().compareTo(timeout) < 0) {
            timeout = request.timeout().orElseThrow();
        }
        if (request.deadline().isPresent()) {
            var deadlineRemaining = request.deadline().orElseThrow().remaining();
            if (deadlineRemaining.compareTo(timeout) < 0) {
                timeout = deadlineRemaining;
            }
        }
        return timeout.isNegative() ? Duration.ZERO : timeout;
    }

    private static Duration min(Duration left, Duration right) {
        return left.compareTo(right) <= 0 ? left : right;
    }

    private static int toTimeoutMillis(Duration timeout) {
        long millis;
        try {
            millis = timeout.toMillis();
        } catch (ArithmeticException ignored) {
            return Integer.MAX_VALUE;
        }
        return (int) Math.max(1L, Math.min(Integer.MAX_VALUE, millis));
    }

    private static long toNanosSaturated(Duration timeout) {
        try {
            return Math.max(0L, timeout.toNanos());
        } catch (ArithmeticException ignored) {
            return Long.MAX_VALUE;
        }
    }
}

