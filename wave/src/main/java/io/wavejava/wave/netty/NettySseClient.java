package io.wavejava.wave.netty;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
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
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.util.ReferenceCountUtil;
import io.wavejava.wave.api.http.Headers;
import io.wavejava.wave.api.http.MediaType;
import io.wavejava.wave.api.sse.SseClient;
import io.wavejava.wave.api.sse.SseClientOptions;
import io.wavejava.wave.api.sse.SseConnection;
import io.wavejava.wave.api.sse.SseEvent;
import io.wavejava.wave.api.sse.SseListener;
import io.wavejava.wave.api.sse.SseProtocolException;
import io.wavejava.wave.api.sse.SseResponseInfo;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Netty-owned, incrementally parsed implementation of the bounded SSE client contract. */
public final class NettySseClient implements SseClient {
    private static final int PENDING_SIGNALS = 4;

    private final SseClientOptions options;
    private final EventLoopGroup eventLoops = new NioEventLoopGroup(1);
    private final Bootstrap bootstrap = new Bootstrap()
            .group(eventLoops)
            .channel(NioSocketChannel.class)
            .option(ChannelOption.AUTO_READ, false);
    private final Set<Connection> connections = ConcurrentHashMap.newKeySet();
    private final Semaphore connectionPermits;
    private final AtomicBoolean closed = new AtomicBoolean();

    /** Creates a client with the supplied bounded options. */
    public NettySseClient(SseClientOptions options) {
        this.options = Objects.requireNonNull(options, "options");
        connectionPermits = new Semaphore(options.maximumConnections());
    }

    @Override
    public SseConnection connect(URI uri, SseListener listener) {
        if (closed.get()) throw new IllegalStateException("SSE client is closed");
        var target = validateUri(uri);
        var targetListener = Objects.requireNonNull(listener, "listener");
        if (!connectionPermits.tryAcquire()) {
            throw new IllegalStateException("SSE client connection budget of " + options.maximumConnections() + " is exhausted");
        }
        Connection connection = null;
        try {
            if (closed.get()) throw new IllegalStateException("SSE client is closed");
            connection = new Connection(target, targetListener);
            connections.add(connection);
            if (closed.get()) throw new IllegalStateException("SSE client is closed");
            connection.start();
            return connection;
        } catch (RuntimeException failure) {
            if (connection != null) connections.remove(connection);
            connectionPermits.release();
            throw failure;
        }
    }

    @Override
    public boolean isClosed() {
        return closed.get();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        for (var connection : connections.toArray(Connection[]::new)) connection.close();
        if (connections.isEmpty()) shutdownEventLoops();
    }

    private void shutdownEventLoops() {
        eventLoops.shutdownGracefully(0, 0, TimeUnit.MILLISECONDS);
    }

    private final class Connection implements SseConnection {
        private final URI uri;
        private final SseListener listener;
        private final CompletableFuture<Void> completion = new CompletableFuture<>();
        private final AtomicBoolean connectionClosed = new AtomicBoolean();
        private final AtomicBoolean permitReleased = new AtomicBoolean();
        private final AtomicReference<Attempt> activeAttempt = new AtomicReference<>();
        private volatile Thread worker;

        private Connection(URI uri, SseListener listener) {
            this.uri = uri;
            this.listener = listener;
        }

        private void start() {
            worker = Thread.startVirtualThread(this::run);
        }

        @Override
        public URI uri() {
            return uri;
        }

        @Override
        public boolean isOpen() {
            var attempt = activeAttempt.get();
            return !connectionClosed.get() && attempt != null && attempt.isOpen();
        }

        @Override
        public CompletionStage<Void> completion() {
            return completion;
        }

        @Override
        public void close() {
            if (!connectionClosed.compareAndSet(false, true)) return;
            var attempt = activeAttempt.get();
            if (attempt != null) attempt.cancel();
            var currentWorker = worker;
            if (currentWorker != null) currentWorker.interrupt();
        }

        private void run() {
            var state = new StreamState(options.initialLastEventId(), options.initialReconnectDelay());
            var reconnectAttempt = 0;
            Throwable terminalFailure = null;
            try {
                while (!cancelled()) {
                    if (reconnectAttempt > 0) {
                        var scheduledAttempt = reconnectAttempt;
                        invoke(() -> listener.onReconnect(state.reconnectDelay, scheduledAttempt));
                        if (!waitForReconnect(state.reconnectDelay)) break;
                    }
                    var attempt = new Attempt(state.lastEventId);
                    activeAttempt.set(attempt);
                    try {
                        attempt.connect();
                        consume(attempt, state);
                    } catch (SseProtocolException protocolFailure) {
                        terminalFailure = protocolFailure;
                        break;
                    } catch (IOException networkFailure) {
                        if (cancelled()) break;
                        if (reconnectAttempt == options.maximumReconnectAttempts()) {
                            terminalFailure = new SseProtocolException(
                                    "SSE reconnect budget exhausted after " + options.maximumReconnectAttempts() + " attempts",
                                    networkFailure);
                            break;
                        }
                        reconnectAttempt++;
                        continue;
                    } finally {
                        attempt.closeChannel();
                        activeAttempt.compareAndSet(attempt, null);
                    }
                    if (cancelled()) break;
                    if (reconnectAttempt == options.maximumReconnectAttempts()) break;
                    reconnectAttempt++;
                }
            } catch (Throwable failure) {
                terminalFailure = failure;
            } finally {
                connections.remove(this);
                releasePermit();
                if (connectionClosed.get() || closed.get()) {
                    completion.complete(null);
                    invokeTerminal(listener::onClosed);
                } else if (terminalFailure != null) {
                    completion.completeExceptionally(terminalFailure);
                    var failure = terminalFailure;
                    invokeTerminal(() -> listener.onFailure(failure));
                } else {
                    completion.complete(null);
                    invokeTerminal(listener::onClosed);
                }
                if (closed.get() && connections.isEmpty()) shutdownEventLoops();
            }
        }

        private void consume(Attempt attempt, StreamState state) throws IOException {
            var parser = new EventParser(state);
            var opened = false;
            while (!cancelled()) {
                final Signal signal;
                try {
                    signal = attempt.take();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
                switch (signal.kind) {
                    case HEAD -> {
                        opened = true;
                        invoke(() -> listener.onOpen((SseResponseInfo) signal.value));
                        attempt.readNext();
                    }
                    case DATA -> {
                        if (!opened) throw new SseProtocolException("SSE body arrived before a response head");
                        parser.accept((byte[]) signal.value);
                        attempt.readNext();
                    }
                    case END -> {
                        parser.end();
                        return;
                    }
                    case FAILURE -> throwFailure((Throwable) signal.value);
                    case CANCELLED -> { return; }
                }
            }
        }

        private void throwFailure(Throwable failure) throws IOException {
            if (failure instanceof SseProtocolException protocolFailure) throw protocolFailure;
            if (failure instanceof IOException ioFailure) throw ioFailure;
            throw new IOException("SSE transport failed", failure);
        }

        private boolean waitForReconnect(Duration delay) {
            if (delay.isZero()) return !cancelled();
            try {
                Thread.sleep(delay);
                return !cancelled();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            }
        }

        private boolean cancelled() {
            return connectionClosed.get() || closed.get();
        }

        private void releasePermit() {
            if (permitReleased.compareAndSet(false, true)) connectionPermits.release();
        }

        private void invoke(Runnable callback) {
            try {
                callback.run();
            } catch (RuntimeException callbackFailure) {
                throw new IllegalStateException("SSE listener callback failed", callbackFailure);
            }
        }

        private void invokeTerminal(Runnable callback) {
            try {
                callback.run();
            } catch (RuntimeException ignored) {
                // A terminal listener callback cannot alter the completed connection state.
            }
        }

        private final class Attempt {
            private final String lastEventId;
            private final ArrayBlockingQueue<Signal> signals = new ArrayBlockingQueue<>(PENDING_SIGNALS);
            private final AtomicBoolean terminal = new AtomicBoolean();
            private final AtomicBoolean responseOpened = new AtomicBoolean();
            private final AtomicReference<Channel> channel = new AtomicReference<>();
            private volatile io.netty.util.concurrent.ScheduledFuture<?> responseDeadline;
            private volatile io.netty.util.concurrent.ScheduledFuture<?> idleDeadline;

            private Attempt(String lastEventId) {
                this.lastEventId = lastEventId;
            }

            private void connect() {
                var configured = bootstrap.clone()
                        .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, milliseconds(options.connectTimeout()))
                        .handler(new ChannelInitializer<SocketChannel>() {
                            @Override
                            protected void initChannel(SocketChannel socketChannel) {
                                socketChannel.pipeline().addLast(new HttpClientCodec(4096, options.maximumHeaderBytes(), 8192));
                                socketChannel.pipeline().addLast(new SseHandler(Attempt.this));
                            }
                        });
                final ChannelFuture connection;
                try {
                    connection = configured.connect(socketHost(uri), effectivePort(uri));
                } catch (RuntimeException failure) {
                    fail(new IOException("SSE connection could not start", failure));
                    return;
                }
                connection.addListener(future -> {
                    if (!future.isSuccess()) {
                        fail(new IOException("SSE connection failed", future.cause()));
                    } else if (cancelled()) {
                        connection.channel().close();
                    }
                });
            }

            private boolean isOpen() {
                var value = channel.get();
                return responseOpened.get() && value != null && value.isActive();
            }

            private Signal take() throws InterruptedException {
                return signals.take();
            }

            private void bind(Channel value) {
                if (!channel.compareAndSet(null, value) || cancelled()) {
                    value.close();
                }
            }

            private void startHeadDeadline(ChannelHandlerContext context) {
                responseDeadline = context.executor().schedule(
                        () -> failAndClose(context, new java.net.SocketTimeoutException("SSE response headers exceeded responseOpenTimeout")),
                        options.responseOpenTimeout().toNanos(), TimeUnit.NANOSECONDS);
            }

            private void response(ChannelHandlerContext context, SseResponseInfo response) {
                cancelResponseDeadline();
                responseOpened.set(true);
                resetIdleDeadline(context);
                offer(new Signal(Kind.HEAD, response));
            }

            private void receivedContent(ChannelHandlerContext context, byte[] bytes, boolean last) {
                resetIdleDeadline(context);
                if (bytes.length != 0) offer(new Signal(Kind.DATA, bytes));
                if (last) end();
            }

            private void resetIdleDeadline(ChannelHandlerContext context) {
                var previous = idleDeadline;
                if (previous != null) previous.cancel(false);
                idleDeadline = context.executor().schedule(
                        () -> failAndClose(context, new java.net.SocketTimeoutException("SSE event stream exceeded idleTimeout")),
                        options.idleTimeout().toNanos(), TimeUnit.NANOSECONDS);
            }

            private void readNext() {
                var value = channel.get();
                if (value != null && value.isActive() && !terminal.get()) {
                    value.eventLoop().execute(value::read);
                }
            }

            private void end() {
                if (terminal.compareAndSet(false, true)) {
                    cancelDeadlines();
                    offer(new Signal(Kind.END, null));
                }
            }

            private void fail(Throwable failure) {
                if (terminal.compareAndSet(false, true)) {
                    cancelDeadlines();
                    offer(new Signal(Kind.FAILURE, failure));
                }
            }

            private void cancel() {
                if (terminal.compareAndSet(false, true)) {
                    cancelDeadlines();
                    offer(new Signal(Kind.CANCELLED, null));
                }
                closeChannel();
            }

            private void closeChannel() {
                var value = channel.getAndSet(null);
                if (value != null) value.close();
            }

            private void inactive() {
                if (!terminal.get()) {
                    if (responseOpened.get()) end();
                    else fail(new IOException("SSE connection closed before response headers"));
                }
            }

            private void offer(Signal signal) {
                if (signals.offer(signal)) return;
                signals.clear();
                signals.offer(new Signal(Kind.FAILURE, new SseProtocolException("SSE callback queue exceeded its fixed budget")));
                closeChannel();
            }

            private void cancelResponseDeadline() {
                var value = responseDeadline;
                if (value != null) value.cancel(false);
            }

            private void cancelDeadlines() {
                cancelResponseDeadline();
                var value = idleDeadline;
                if (value != null) value.cancel(false);
            }

            private void failAndClose(ChannelHandlerContext context, Throwable failure) {
                fail(failure);
                context.close();
            }

            private final class SseHandler extends ChannelInboundHandlerAdapter {
                private final Attempt attempt;

                private SseHandler(Attempt attempt) {
                    this.attempt = attempt;
                }

                @Override
                public void channelActive(ChannelHandlerContext context) {
                    attempt.bind(context.channel());
                    if (cancelled()) {
                        context.close();
                        return;
                    }
                    attempt.startHeadDeadline(context);
                    try {
                        context.writeAndFlush(attempt.request()).addListener(future -> {
                            if (future.isSuccess()) context.read();
                            else attempt.failAndClose(context, new IOException("SSE request write failed", future.cause()));
                        });
                    } catch (SseProtocolException failure) {
                        attempt.failAndClose(context, failure);
                    }
                }

                @Override
                public void channelRead(ChannelHandlerContext context, Object message) {
                    try {
                        if (!(message instanceof HttpObject)) {
                            attempt.failAndClose(context, new SseProtocolException("SSE transport produced a non-HTTP message"));
                            return;
                        }
                        if (message instanceof HttpResponse response) handleResponse(context, response);
                        if (message instanceof HttpContent content) {
                            attempt.receivedContent(context, ByteBufUtil.getBytes(content.content()), content instanceof LastHttpContent);
                        }
                    } catch (SseProtocolException failure) {
                        attempt.failAndClose(context, failure);
                    } catch (RuntimeException failure) {
                        attempt.failAndClose(context, new SseProtocolException("SSE response could not be parsed", failure));
                    } finally {
                        ReferenceCountUtil.release(message);
                    }
                }

                @Override
                public void exceptionCaught(ChannelHandlerContext context, Throwable failure) {
                    attempt.failAndClose(context, new IOException("SSE transport failed", failure));
                }

                @Override
                public void channelInactive(ChannelHandlerContext context) {
                    attempt.inactive();
                }

                private void handleResponse(ChannelHandlerContext context, HttpResponse response) throws SseProtocolException {
                    if (attempt.responseOpened.get()) throw new SseProtocolException("SSE endpoint sent multiple response heads");
                    if (response.status().code() != 200) {
                        throw new SseProtocolException("SSE endpoint returned HTTP " + response.status().code() + " instead of 200");
                    }
                    var headers = copyHeaders(response, options.maximumHeaderCount(), options.maximumHeaderBytes());
                    validateEventStreamContentType(headers);
                    attempt.response(context, new SseResponseInfo(response.status().code(), headers, uri));
                }
            }

            private DefaultFullHttpRequest request() throws SseProtocolException {
                if (lastEventId != null && !safeHeaderValue(lastEventId)) {
                    throw new SseProtocolException("SSE last-event ID is unsafe for an HTTP header");
                }
                var target = uri.getRawPath();
                if (target == null || target.isEmpty()) target = "/";
                if (uri.getRawQuery() != null) target += '?' + uri.getRawQuery();
                var host = socketHost(uri);
                var renderedHost = host.indexOf(':') >= 0 ? '[' + host + ']' : host;
                if (uri.getPort() >= 0 && uri.getPort() != 80) renderedHost += ':' + Integer.toString(uri.getPort());
                var request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, target, Unpooled.EMPTY_BUFFER);
                request.headers().set(HttpHeaderNames.HOST, renderedHost);
                request.headers().set(HttpHeaderNames.ACCEPT, HttpHeaderValues.TEXT_EVENT_STREAM);
                request.headers().set(HttpHeaderNames.CACHE_CONTROL, HttpHeaderValues.NO_CACHE);
                request.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
                if (lastEventId != null && !lastEventId.isEmpty()) request.headers().set("Last-Event-ID", lastEventId);
                if (requestHeaderBytes(request) > options.maximumHeaderBytes()) {
                    request.release();
                    throw new SseProtocolException("SSE request exceeded header byte budget");
                }
                return request;
            }
        }

        private final class EventParser {
            private final StreamState state;
            private final ByteArrayOutputStream line = new ByteArrayOutputStream(Math.min(options.maximumLineBytes(), 256));
            private String eventType;
            private final StringBuilder data = new StringBuilder();
            private int eventBytes;
            private boolean firstLine = true;
            private boolean pendingCarriageReturn;

            private EventParser(StreamState state) {
                this.state = state;
            }

            private void accept(byte[] bytes) throws IOException {
                for (byte value : bytes) accept(value & 0xff);
            }

            private void accept(int value) throws IOException {
                if (pendingCarriageReturn) {
                    pendingCarriageReturn = false;
                    if (value == '\n') {
                        finishLine(line.size() + 2);
                        return;
                    }
                    finishLine(line.size() + 1);
                }
                if (value == '\r') {
                    pendingCarriageReturn = true;
                } else if (value == '\n') {
                    finishLine(line.size() + 1);
                } else {
                    if (line.size() == options.maximumLineBytes()) {
                        throw new SseProtocolException("SSE line exceeded byte budget of " + options.maximumLineBytes());
                    }
                    line.write(value);
                }
            }

            private void end() throws IOException {
                if (pendingCarriageReturn) {
                    pendingCarriageReturn = false;
                    finishLine(line.size() + 1);
                } else if (line.size() != 0) {
                    finishLine(line.size());
                }
                dispatchEvent();
            }

            private void finishLine(int wireBytes) throws IOException {
                var value = decodeUtf8(line.toByteArray());
                line.reset();
                if (firstLine) {
                    firstLine = false;
                    if (value.startsWith("\uFEFF")) value = value.substring(1);
                }
                if (value.isEmpty()) {
                    dispatchEvent();
                    resetEventFields();
                    return;
                }
                processLine(value, wireBytes);
            }

            private void processLine(String value, int wireBytes) throws IOException {
                if (value.charAt(0) == ':') {
                    invoke(() -> listener.onComment(stripOptionalSpace(value.substring(1))));
                    return;
                }
                eventBytes = addEventBytes(eventBytes, wireBytes, options.maximumEventBytes());
                var separator = value.indexOf(':');
                var field = separator < 0 ? value : value.substring(0, separator);
                var fieldValue = separator < 0 ? "" : stripOptionalSpace(value.substring(separator + 1));
                switch (field) {
                    case "data" -> data.append(fieldValue).append('\n');
                    case "event" -> eventType = fieldValue.isEmpty() ? null : fieldValue;
                    case "id" -> { if (fieldValue.indexOf('\0') < 0) state.lastEventId = fieldValue; }
                    case "retry" -> parseRetry(fieldValue);
                    default -> { }
                }
            }

            private void parseRetry(String value) {
                if (value.isEmpty() || !value.chars().allMatch(character -> character >= '0' && character <= '9')) return;
                try {
                    var delay = Duration.ofMillis(Long.parseLong(value));
                    state.reconnectDelay = delay.compareTo(options.maximumReconnectDelay()) > 0
                            ? options.maximumReconnectDelay() : delay;
                } catch (NumberFormatException ignored) {
                    // Invalid or unrepresentable retry fields are ignored by the SSE specification.
                }
            }

            private void dispatchEvent() throws IOException {
                if (data.isEmpty()) return;
                data.setLength(data.length() - 1);
                try {
                    var event = SseEvent.builder().data(data.toString());
                    if (eventType != null) event.event(eventType);
                    if (state.lastEventId != null) event.id(state.lastEventId);
                    event.retry(state.reconnectDelay);
                    invoke(() -> listener.onEvent(event.build()));
                } catch (IllegalArgumentException invalidEvent) {
                    throw new SseProtocolException("SSE event contains an unsafe field", invalidEvent);
                }
            }

            private void resetEventFields() {
                data.setLength(0);
                eventType = null;
                eventBytes = 0;
            }
        }
    }

    private static Headers copyHeaders(HttpResponse response, int maximumCount, int maximumBytes) throws SseProtocolException {
        var source = response.headers();
        var headers = Headers.builder();
        var bytes = response.protocolVersion().text().getBytes(StandardCharsets.ISO_8859_1).length + 6;
        var count = 0;
        for (var entry : source) {
            if (++count > maximumCount) throw new SseProtocolException("SSE response exceeded header count budget");
            try {
                headers.add(entry.getKey(), entry.getValue());
            } catch (IllegalArgumentException failure) {
                throw new SseProtocolException("SSE response contains an invalid header", failure);
            }
            try {
                bytes = Math.addExact(bytes, entry.getKey().getBytes(StandardCharsets.ISO_8859_1).length
                        + entry.getValue().getBytes(StandardCharsets.ISO_8859_1).length + 4);
            } catch (ArithmeticException overflow) {
                throw new SseProtocolException("SSE response header byte count overflow", overflow);
            }
            if (bytes > maximumBytes) throw new SseProtocolException("SSE response exceeded header byte budget");
        }
        return headers.build();
    }

    private static void validateEventStreamContentType(Headers headers) throws SseProtocolException {
        var value = headers.first("Content-Type")
                .orElseThrow(() -> new SseProtocolException("SSE response is missing Content-Type"));
        final MediaType mediaType;
        try {
            mediaType = MediaType.parse(value);
        } catch (IllegalArgumentException invalidContentType) {
            throw new SseProtocolException("SSE response has an invalid Content-Type", invalidContentType);
        }
        if (!MediaType.TEXT_EVENT_STREAM.equals(mediaType.withoutParameter("charset"))) {
            throw new SseProtocolException("SSE response Content-Type must be text/event-stream");
        }
        try {
            if (mediaType.charset().isPresent() && !StandardCharsets.UTF_8.equals(mediaType.charset().orElseThrow())) {
                throw new SseProtocolException("SSE response charset must be UTF-8");
            }
        } catch (IllegalArgumentException invalidCharset) {
            throw new SseProtocolException("SSE response declares an invalid charset", invalidCharset);
        }
    }

    private static URI validateUri(URI uri) {
        var value = Objects.requireNonNull(uri, "uri");
        if (!value.isAbsolute() || value.getHost() == null || value.getRawFragment() != null || value.getRawUserInfo() != null
                || !"http".equalsIgnoreCase(value.getScheme())) {
            throw new IllegalArgumentException("SSE client supports only absolute http URIs without user-info or fragments");
        }
        if (value.getPort() == 0 || value.getPort() > 65_535) {
            throw new IllegalArgumentException("SSE client URI port must be in the range 1 through 65535");
        }
        return value;
    }

    private static String socketHost(URI uri) {
        var host = uri.getHost();
        return host.length() >= 2 && host.charAt(0) == '[' && host.charAt(host.length() - 1) == ']'
                ? host.substring(1, host.length() - 1) : host;
    }

    private static int effectivePort(URI uri) {
        return uri.getPort() < 0 ? 80 : uri.getPort();
    }

    private static int requestHeaderBytes(DefaultFullHttpRequest request) throws SseProtocolException {
        try {
            var bytes = request.method().name().getBytes(StandardCharsets.UTF_8).length
                    + 1 + request.uri().getBytes(StandardCharsets.UTF_8).length
                    + 1 + request.protocolVersion().text().getBytes(StandardCharsets.UTF_8).length + 2;
            for (var header : request.headers()) {
                bytes = Math.addExact(bytes, header.getKey().getBytes(StandardCharsets.UTF_8).length
                        + 2 + header.getValue().getBytes(StandardCharsets.UTF_8).length + 2);
            }
            return Math.addExact(bytes, 2);
        } catch (ArithmeticException overflow) {
            throw new SseProtocolException("SSE request header byte count overflow", overflow);
        }
    }

    private static int milliseconds(Duration duration) {
        try {
            return Math.toIntExact(Math.max(1L, duration.toMillis()));
        } catch (ArithmeticException ignored) {
            return Integer.MAX_VALUE;
        }
    }

    private static int addEventBytes(int current, int addition, int maximum) throws SseProtocolException {
        try {
            var total = Math.addExact(current, addition);
            if (total > maximum) throw new SseProtocolException("SSE event exceeded byte budget");
            return total;
        } catch (ArithmeticException overflow) {
            throw new SseProtocolException("SSE event byte count overflow", overflow);
        }
    }

    private static String decodeUtf8(byte[] bytes) throws SseProtocolException {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException invalidUtf8) {
            throw new SseProtocolException("SSE stream is not valid UTF-8", invalidUtf8);
        }
    }

    private static String stripOptionalSpace(String value) {
        return value.startsWith(" ") ? value.substring(1) : value;
    }

    private static boolean safeHeaderValue(String value) {
        for (var index = 0; index < value.length(); index++) {
            var character = value.charAt(index);
            if (character == '\r' || character == '\n' || character == 0 || character == 0x7f
                    || (character < 0x20 && character != '\t')) return false;
        }
        return true;
    }

    private enum Kind { HEAD, DATA, END, FAILURE, CANCELLED }

    private record Signal(Kind kind, Object value) { }

    private static final class StreamState {
        private String lastEventId;
        private Duration reconnectDelay;

        private StreamState(String lastEventId, Duration reconnectDelay) {
            this.lastEventId = lastEventId;
            this.reconnectDelay = reconnectDelay;
        }
    }
}
