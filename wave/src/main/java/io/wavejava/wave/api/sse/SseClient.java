package io.wavejava.wave.api.sse;

import io.wavejava.wave.api.http.Headers;
import io.wavejava.wave.api.http.MediaType;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PushbackInputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A direct, bounded HTTP/1.1 Server-Sent Events client.
 *
 * <p>This client intentionally has a separate lifecycle from {@code WaveClient}: its event stream
 * is long-lived and parsed incrementally rather than materialized as a bounded byte response. Its
 * private 0.5 transport is a direct {@code http} socket adapter with bounded status/header parsing
 * and chunked-body decoding. TLS, proxy tunnelling, and HTTP/2 are added with the shared client
 * transport work in 0.7. No JDK HTTP or Netty type appears in this API.</p>
 *
 * <p>Each connection owns one virtual reader thread and one direct socket. Listener callbacks run
 * serially on that virtual thread; a slow listener applies TCP backpressure rather than creating
 * an application-side event queue.</p>
 */
public final class SseClient implements AutoCloseable {
    /** Default maximum bytes in the response status line and all response headers. */
    public static final int DEFAULT_MAXIMUM_HEADER_BYTES = 16 * 1024;

    /** Default maximum response header field count. */
    public static final int DEFAULT_MAXIMUM_HEADER_COUNT = 100;

    /** Default bound for one UTF-8 event-stream line. */
    public static final int DEFAULT_MAXIMUM_LINE_BYTES = 64 * 1024;

    /** Default bound for one accumulated event block. */
    public static final int DEFAULT_MAXIMUM_EVENT_BYTES = 256 * 1024;

    /** Default finite budget for reconnect attempts after the first connection. */
    public static final int DEFAULT_MAXIMUM_RECONNECT_ATTEMPTS = 1_000;

    /** Default cap for live or reconnecting client subscriptions owned by one client. */
    public static final int DEFAULT_MAXIMUM_CONNECTIONS = 1_000;

    /** Default upper bound for a server-provided {@code retry:} reconnect delay. */
    public static final Duration DEFAULT_MAXIMUM_RECONNECT_DELAY = Duration.ofMinutes(5);

    /** Default time allowed for a peer to produce a complete HTTP response head. */
    public static final Duration DEFAULT_RESPONSE_OPEN_TIMEOUT = Duration.ofSeconds(15);

    /** Default maximum silent interval after the event stream has opened. */
    public static final Duration DEFAULT_IDLE_TIMEOUT = Duration.ofMinutes(2);

    private final Duration connectTimeout;
    private final Duration responseOpenTimeout;
    private final Duration idleTimeout;
    private final Duration initialReconnectDelay;
    private final int maximumReconnectAttempts;
    private final int maximumHeaderBytes;
    private final int maximumHeaderCount;
    private final int maximumLineBytes;
    private final int maximumEventBytes;
    private final int maximumConnections;
    private final Duration maximumReconnectDelay;
    private final String initialLastEventId;
    private final Set<Connection> connections = ConcurrentHashMap.newKeySet();
    private final Semaphore connectionPermits;
    private final AtomicBoolean closed = new AtomicBoolean();

    private SseClient(Builder builder) {
        connectTimeout = builder.connectTimeout;
        responseOpenTimeout = builder.responseOpenTimeout;
        idleTimeout = builder.idleTimeout;
        initialReconnectDelay = builder.initialReconnectDelay;
        maximumReconnectAttempts = builder.maximumReconnectAttempts;
        maximumHeaderBytes = builder.maximumHeaderBytes;
        maximumHeaderCount = builder.maximumHeaderCount;
        maximumLineBytes = builder.maximumLineBytes;
        maximumEventBytes = builder.maximumEventBytes;
        maximumConnections = builder.maximumConnections;
        maximumReconnectDelay = builder.maximumReconnectDelay;
        initialLastEventId = builder.initialLastEventId;
        connectionPermits = new Semaphore(maximumConnections);
    }

    /** Creates a client with documented finite direct-HTTP defaults. */
    public static SseClient create() {
        return builder().build();
    }

    /** Starts a client builder. */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Opens an asynchronous SSE connection. The returned handle is available immediately; response
     * validation and listener callbacks occur on a dedicated virtual thread.
     */
    public SseConnection connect(URI uri, SseListener listener) {
        if (closed.get()) {
            throw new IllegalStateException("SSE client is closed");
        }
        var target = validateUri(uri);
        var targetListener = Objects.requireNonNull(listener, "listener");
        if (!connectionPermits.tryAcquire()) {
            throw new IllegalStateException("SSE client connection budget of " + maximumConnections + " is exhausted");
        }

        Connection connection = null;
        try {
            if (closed.get()) {
                throw new IllegalStateException("SSE client is closed");
            }
            connection = new Connection(target, targetListener);
            connections.add(connection);
            if (closed.get()) {
                connections.remove(connection);
                throw new IllegalStateException("SSE client is closed");
            }
            connection.start();
            return connection;
        } catch (RuntimeException failure) {
            if (connection != null) {
                connections.remove(connection);
            }
            connectionPermits.release();
            throw failure;
        }
    }

    /** Closes all owned SSE subscriptions and prevents new ones. */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        for (var connection : connections.toArray(Connection[]::new)) {
            connection.close();
        }
    }

    private final class Connection implements SseConnection {
        private final URI uri;
        private final SseListener listener;
        private final CompletableFuture<Void> completion = new CompletableFuture<>();
        private final AtomicBoolean connectionClosed = new AtomicBoolean();
        private final AtomicBoolean permitReleased = new AtomicBoolean();
        private final AtomicReference<Socket> activeSocket = new AtomicReference<>();
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
            var socket = activeSocket.get();
            return !connectionClosed.get() && socket != null && socket.isConnected() && !socket.isClosed();
        }

        @Override
        public CompletionStage<Void> completion() {
            return completion;
        }

        @Override
        public void close() {
            if (!connectionClosed.compareAndSet(false, true)) {
                return;
            }
            closeActiveSocket();
            var currentWorker = worker;
            if (currentWorker != null) {
                currentWorker.interrupt();
            }
        }

        private void run() {
            var streamState = new StreamState(initialLastEventId, initialReconnectDelay);
            var reconnectAttempt = 0;
            Throwable terminalFailure = null;
            try {
                while (!isCancelled()) {
                    var cleanEndOfStream = false;
                    IOException retryableFailure = null;
                    if (reconnectAttempt > 0) {
                        var scheduledDelay = streamState.reconnectDelay;
                        var scheduledAttempt = reconnectAttempt;
                        invokeListener(() -> listener.onReconnect(scheduledDelay, scheduledAttempt));
                        if (!waitForReconnect(scheduledDelay)) {
                            break;
                        }
                    }

                    try {
                        var exchange = openExchange(streamState.lastEventId);
                        invokeListener(() -> listener.onOpen(exchange.response()));
                        parseEventStream(exchange.body(), streamState);
                        cleanEndOfStream = true;
                    } catch (SseProtocolException protocolFailure) {
                        terminalFailure = protocolFailure;
                        break;
                    } catch (IOException networkFailure) {
                        if (isCancelled()) {
                            break;
                        }
                        retryableFailure = networkFailure;
                        // EOF, reset, timeout, and direct connection failures are retryable until
                        // the explicit reconnect count budget is exhausted.
                    } finally {
                        closeActiveSocket();
                    }

                    if (isCancelled()) {
                        break;
                    }
                    if (reconnectAttempt == maximumReconnectAttempts) {
                        // EOF after a valid event stream is a normal terminal condition when
                        // the caller's finite reconnect budget is spent. A connection or idle
                        // failure remains observable as a failure rather than being disguised
                        // as a clean stream close.
                        if (!cleanEndOfStream) {
                            terminalFailure = new SseProtocolException(
                                    "SSE reconnect budget exhausted after "
                                            + maximumReconnectAttempts
                                            + " attempts",
                                    retryableFailure);
                        }
                        break;
                    }
                    reconnectAttempt++;
                }
            } catch (Throwable failure) {
                terminalFailure = failure;
            } finally {
                connections.remove(this);
                closeActiveSocket();
                releasePermit();
                if (connectionClosed.get() || SseClient.this.closed.get()) {
                    completion.complete(null);
                    invokeClosed();
                } else if (terminalFailure != null) {
                    completion.completeExceptionally(terminalFailure);
                    invokeFailure(terminalFailure);
                } else {
                    completion.complete(null);
                    invokeClosed();
                }
            }
        }

        private Exchange openExchange(String lastEventId) throws IOException {
            var socket = new Socket();
            activeSocket.set(socket);
            if (isCancelled()) {
                socket.close();
                throw new IOException("SSE connection was cancelled before HTTP exchange");
            }
            socket.connect(new InetSocketAddress(socketHost(uri), effectivePort(uri)), durationToMillis(connectTimeout, "connectTimeout"));
            writeRequest(socket.getOutputStream(), uri, lastEventId, maximumHeaderBytes);
            // Validate once before constructing the nanosecond deadline so a duration that cannot
            // be represented by Socket#setSoTimeout is rejected deterministically.
            durationToMillis(responseOpenTimeout, "responseOpenTimeout");

            // Keep buffering below the deadline wrapper. This lets every header-parser read
            // check the absolute deadline even when BufferedInputStream has already prefetched
            // bytes, while retaining any prefetched body bytes for the body decoder below.
            var input = new BufferedInputStream(socket.getInputStream());
            var deadlineInput = new ResponseHeadDeadlineInputStream(input, socket, responseOpenTimeout);
            var head = readResponseHead(deadlineInput, maximumHeaderCount, maximumHeaderBytes);
            deadlineInput.completeResponseHead();
            if (head.status() != 200) {
                throw new SseProtocolException("SSE endpoint returned HTTP " + head.status() + " instead of 200");
            }
            validateEventStreamContentType(head.headers());
            socket.setSoTimeout(durationToMillis(idleTimeout, "idleTimeout"));
            return new Exchange(new SseResponseInfo(head.status(), head.headers(), uri), bodyStream(input, head.headers()));
        }

        private void parseEventStream(InputStream source, StreamState streamState) throws IOException {
            new EventStreamParser(source, streamState).parse();
        }

        private boolean waitForReconnect(Duration delay) {
            if (delay.isZero()) {
                return !isCancelled();
            }
            try {
                Thread.sleep(delay);
                return !isCancelled();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            }
        }

        private boolean isCancelled() {
            return connectionClosed.get() || SseClient.this.closed.get();
        }

        private void closeActiveSocket() {
            var socket = activeSocket.getAndSet(null);
            if (socket != null) {
                try {
                    socket.close();
                } catch (IOException ignored) {
                    // Teardown owns the socket regardless of its prior I/O state.
                }
            }
        }

        private void releasePermit() {
            if (permitReleased.compareAndSet(false, true)) {
                connectionPermits.release();
            }
        }

        private void invokeListener(Runnable callback) {
            try {
                callback.run();
            } catch (RuntimeException callbackFailure) {
                throw new IllegalStateException("SSE listener callback failed", callbackFailure);
            }
        }

        private void invokeClosed() {
            try {
                listener.onClosed();
            } catch (RuntimeException ignored) {
                // The connection is already terminal.
            }
        }

        private void invokeFailure(Throwable failure) {
            try {
                listener.onFailure(failure);
            } catch (RuntimeException ignored) {
                // A terminal reporting callback cannot revive a failed connection.
            }
        }

        private final class EventStreamParser {
            private final PushbackInputStream input;
            private final StreamState streamState;
            private String eventType;
            private final StringBuilder data = new StringBuilder();
            private int eventBytes;
            private boolean firstLine = true;

            private EventStreamParser(InputStream source, StreamState streamState) {
                input = new PushbackInputStream(Objects.requireNonNull(source, "source"), 1);
                this.streamState = Objects.requireNonNull(streamState, "streamState");
            }

            private void parse() throws IOException {
                while (!isCancelled()) {
                    var line = readSseLine(input, maximumLineBytes);
                    if (line == null) {
                        dispatchEvent();
                        return;
                    }
                    var value = line.value();
                    if (firstLine) {
                        firstLine = false;
                        // The SSE parser algorithm ignores exactly one initial UTF-8 BOM, never a
                        // later U+FEFF that belongs to event data.
                        if (value.startsWith("\uFEFF")) {
                            value = value.substring(1);
                        }
                    }
                    if (value.isEmpty()) {
                        dispatchEvent();
                        resetEventFields();
                        continue;
                    }
                    processLine(value, line.wireBytes());
                }
            }

            private void processLine(String value, int wireBytes) throws IOException {
                if (value.charAt(0) == ':') {
                    invokeListener(() -> listener.onComment(stripOptionalSpace(value.substring(1))));
                    return;
                }
                eventBytes = addEventBytes(eventBytes, wireBytes, maximumEventBytes);
                var separator = value.indexOf(':');
                var field = separator < 0 ? value : value.substring(0, separator);
                var fieldValue = separator < 0 ? "" : stripOptionalSpace(value.substring(separator + 1));
                switch (field) {
                    case "data" -> data.append(fieldValue).append('\n');
                    case "event" -> eventType = fieldValue.isEmpty() ? null : fieldValue;
                    case "id" -> {
                        if (fieldValue.indexOf('\0') < 0) {
                            // Persist immediately so an abrupt reset after this line reconnects
                            // with the protocol's latest known ID rather than the prior event's.
                            streamState.lastEventId = fieldValue;
                        }
                    }
                    case "retry" -> parseRetry(fieldValue);
                    default -> {
                        // Unknown SSE fields are ignored by the wire specification.
                    }
                }
            }

            private void parseRetry(String value) {
                if (value.isEmpty()) {
                    return;
                }
                for (var index = 0; index < value.length(); index++) {
                    if (value.charAt(index) < '0' || value.charAt(index) > '9') {
                        return;
                    }
                }
                try {
                    var candidate = Duration.ofMillis(Long.parseLong(value));
                    // Preserve the SSE hint but do not permit a peer to park a virtual connection
                    // beyond the client's explicitly configured time budget.
                    streamState.reconnectDelay = candidate.compareTo(maximumReconnectDelay) > 0
                            ? maximumReconnectDelay
                            : candidate;
                } catch (NumberFormatException ignored) {
                    // A syntactically too-large retry value is ignored just like any invalid SSE retry field.
                }
            }

            private void dispatchEvent() throws IOException {
                if (data.isEmpty()) {
                    return;
                }
                data.setLength(data.length() - 1); // trailing newline added for the final data field
                try {
                    var event = SseEvent.builder().data(data.toString());
                    if (eventType != null) {
                        event.event(eventType);
                    }
                    if (streamState.lastEventId != null) {
                        event.id(streamState.lastEventId);
                    }
                    event.retry(streamState.reconnectDelay);
                    invokeListener(() -> listener.onEvent(event.build()));
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

    private static ResponseHead readResponseHead(InputStream input, int maximumHeaderCount, int maximumHeaderBytes)
            throws IOException {
        var statusLine = readHttpLine(input, maximumHeaderBytes);
        if (statusLine == null) {
            throw new EOFException("connection closed before HTTP status line");
        }
        var consumedBytes = statusLine.wireBytes();
        if (consumedBytes > maximumHeaderBytes) {
            throw new SseProtocolException("SSE response exceeded header byte budget");
        }
        var status = parseStatus(statusLine.value());
        var headers = Headers.builder();
        var headerCount = 0;
        while (true) {
            var line = readHttpLine(input, maximumHeaderBytes - consumedBytes);
            if (line == null) {
                throw new EOFException("connection closed before HTTP headers completed");
            }
            consumedBytes = addHeaderBytes(consumedBytes, line.wireBytes(), maximumHeaderBytes);
            if (line.value().isEmpty()) {
                return new ResponseHead(status, headers.build());
            }
            if (++headerCount > maximumHeaderCount) {
                throw new SseProtocolException("SSE response exceeded header count budget");
            }
            if (line.value().charAt(0) == ' ' || line.value().charAt(0) == '\t') {
                throw new SseProtocolException("SSE response contains obsolete folded header syntax");
            }
            var separator = line.value().indexOf(':');
            if (separator <= 0) {
                throw new SseProtocolException("SSE response contains a malformed header line");
            }
            var name = line.value().substring(0, separator);
            var value = trimHttpWhitespace(line.value().substring(separator + 1));
            try {
                headers.add(name, value);
            } catch (IllegalArgumentException invalidHeader) {
                throw new SseProtocolException("SSE response contains an invalid header", invalidHeader);
            }
        }
    }

    private static InputStream bodyStream(InputStream input, Headers headers) throws IOException {
        var transferEncoding = transferCodings(headers);
        var contentLengths = headers.all("Content-Length");
        if (!transferEncoding.isEmpty() && !contentLengths.isEmpty()) {
            throw new SseProtocolException("SSE response must not send both Transfer-Encoding and Content-Length");
        }
        if (!transferEncoding.isEmpty()) {
            if (transferEncoding.size() != 1 || !transferEncoding.getFirst().equals("chunked")) {
                throw new SseProtocolException("SSE response uses an unsupported Transfer-Encoding");
            }
            return new ChunkedInputStream(input);
        }
        if (contentLengths.isEmpty()) {
            return input; // HTTP/1.0/close-delimited SSE response
        }
        return new LimitedInputStream(input, parseContentLength(contentLengths));
    }

    private static List<String> transferCodings(Headers headers) throws SseProtocolException {
        var codings = new ArrayList<String>();
        for (var value : headers.all("Transfer-Encoding")) {
            for (var item : value.split(",", -1)) {
                var coding = item.trim().toLowerCase(Locale.ROOT);
                if (coding.isEmpty()) {
                    throw new SseProtocolException("SSE response contains an empty Transfer-Encoding token");
                }
                codings.add(coding);
            }
        }
        return List.copyOf(codings);
    }

    private static long parseContentLength(List<String> values) throws SseProtocolException {
        Long parsed = null;
        for (var value : values) {
            final long candidate;
            try {
                if (value.isEmpty() || value.charAt(0) == '+') {
                    throw new NumberFormatException("invalid content length");
                }
                candidate = Long.parseLong(value);
            } catch (NumberFormatException invalidLength) {
                throw new SseProtocolException("SSE response contains an invalid Content-Length", invalidLength);
            }
            if (candidate < 0 || parsed != null && parsed != candidate) {
                throw new SseProtocolException("SSE response contains conflicting Content-Length values");
            }
            parsed = candidate;
        }
        return Objects.requireNonNull(parsed, "content length");
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
            throw new IllegalArgumentException("SSE client 0.5 supports only absolute http URIs without user-info or fragments");
        }
        if (value.getPort() == 0 || value.getPort() > 65_535) {
            throw new IllegalArgumentException("SSE client URI port must be in the range 1 through 65535");
        }
        return value;
    }

    private static int effectivePort(URI uri) {
        return uri.getPort() < 0 ? 80 : uri.getPort();
    }

    private static String socketHost(URI uri) {
        var host = uri.getHost();
        // URI#getHost retains brackets for IPv6 literals while Socket and Host rendering need
        // opposite forms: Socket gets the bare literal and the HTTP field adds one bracket pair.
        if (host.length() >= 2 && host.charAt(0) == '[' && host.charAt(host.length() - 1) == ']') {
            return host.substring(1, host.length() - 1);
        }
        return host;
    }

    private static void writeRequest(OutputStream output, URI uri, String lastEventId, int maximumHeaderBytes)
            throws IOException {
        if (lastEventId != null && !isSafeHttpHeaderValue(lastEventId)) {
            throw new SseProtocolException("SSE last-event ID is unsafe for an HTTP header");
        }
        var target = uri.getRawPath();
        if (target == null || target.isEmpty()) {
            target = "/";
        }
        if (uri.getRawQuery() != null) {
            target += '?' + uri.getRawQuery();
        }
        var host = socketHost(uri);
        var renderedHost = host.indexOf(':') >= 0 ? '[' + host + ']' : host;
        if (uri.getPort() >= 0 && uri.getPort() != 80) {
            renderedHost += ':' + Integer.toString(uri.getPort());
        }
        var request = new StringBuilder()
                .append("GET ").append(target).append(" HTTP/1.1\r\n")
                .append("Host: ").append(renderedHost).append("\r\n")
                .append("Accept: text/event-stream\r\n")
                .append("Cache-Control: no-cache\r\n")
                .append("Connection: keep-alive\r\n");
        if (lastEventId != null && !lastEventId.isEmpty()) {
            request.append("Last-Event-ID: ").append(lastEventId).append("\r\n");
        }
        request.append("\r\n");
        var bytes = request.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > maximumHeaderBytes) {
            throw new SseProtocolException("SSE request exceeded header byte budget");
        }
        output.write(bytes);
        output.flush();
    }

    private static boolean isSafeHttpHeaderValue(String value) {
        for (var index = 0; index < value.length(); index++) {
            var character = value.charAt(index);
            if (character == '\r' || character == '\n' || character == 0 || character == 0x7f
                    || (character < 0x20 && character != '\t')) {
                return false;
            }
        }
        return true;
    }

    private static int durationToMillis(Duration duration, String name) {
        try {
            var millis = duration.toMillis();
            if (millis <= 0 || millis > Integer.MAX_VALUE) {
                throw new IllegalArgumentException(name + " must fit a positive socket timeout in milliseconds");
            }
            return Math.toIntExact(millis);
        } catch (ArithmeticException failure) {
            throw new IllegalArgumentException(name + " is too large", failure);
        }
    }

    private static int addHeaderBytes(int current, int addition, int maximum) throws SseProtocolException {
        try {
            var total = Math.addExact(current, addition);
            if (total > maximum) {
                throw new SseProtocolException("SSE response exceeded header byte budget");
            }
            return total;
        } catch (ArithmeticException overflow) {
            throw new SseProtocolException("SSE response header byte count overflow", overflow);
        }
    }

    private static int addEventBytes(int current, int addition, int maximumEventBytes) throws SseProtocolException {
        try {
            var total = Math.addExact(current, addition);
            if (total > maximumEventBytes) {
                throw new SseProtocolException("SSE event exceeded byte budget");
            }
            return total;
        } catch (ArithmeticException overflow) {
            throw new SseProtocolException("SSE event byte count overflow", overflow);
        }
    }

    private static HttpLine readHttpLine(InputStream input, int maximumBytes) throws IOException {
        // The wire budget includes the required CRLF. Keep the allocated payload strictly below
        // that budget so an over-limit peer cannot make us retain even a line's delimiter beyond
        // the advertised response-header limit.
        if (maximumBytes < 2) {
            throw new SseProtocolException("SSE response exceeded header byte budget");
        }
        var payloadBudget = maximumBytes - 2;
        var bytes = new ByteArrayOutputStream(Math.min(payloadBudget, 256));
        while (true) {
            var next = input.read();
            if (next < 0) {
                if (bytes.size() == 0) {
                    return null;
                }
                throw new SseProtocolException("HTTP line ended without CRLF");
            }
            if (next == '\r') {
                if (input.read() != '\n') {
                    throw new SseProtocolException("HTTP line was not terminated with CRLF");
                }
                return new HttpLine(new String(bytes.toByteArray(), StandardCharsets.ISO_8859_1), bytes.size() + 2);
            }
            if (next == '\n') {
                throw new SseProtocolException("HTTP line was not terminated with CRLF");
            }
            if (bytes.size() == payloadBudget) {
                throw new SseProtocolException("HTTP line exceeded byte budget of " + maximumBytes);
            }
            bytes.write(next);
        }
    }

    private static SseLine readSseLine(PushbackInputStream input, int maximumBytes) throws IOException {
        var bytes = new ByteArrayOutputStream(Math.min(maximumBytes, 256));
        var wireBytes = 0;
        while (true) {
            var next = input.read();
            if (next < 0) {
                if (bytes.size() == 0) {
                    return null;
                }
                return new SseLine(decodeUtf8(bytes.toByteArray()), wireBytes);
            }
            if (next == '\n') {
                return new SseLine(decodeUtf8(bytes.toByteArray()), wireBytes + 1);
            }
            if (next == '\r') {
                var following = input.read();
                if (following >= 0 && following != '\n') {
                    input.unread(following);
                }
                return new SseLine(decodeUtf8(bytes.toByteArray()), wireBytes + (following == '\n' ? 2 : 1));
            }
            if (bytes.size() == maximumBytes) {
                throw new SseProtocolException("SSE line exceeded byte budget of " + maximumBytes);
            }
            bytes.write(next);
            wireBytes++;
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

    private static String trimHttpWhitespace(String value) {
        var start = 0;
        var end = value.length();
        while (start < end && (value.charAt(start) == ' ' || value.charAt(start) == '\t')) {
            start++;
        }
        while (end > start && (value.charAt(end - 1) == ' ' || value.charAt(end - 1) == '\t')) {
            end--;
        }
        return value.substring(start, end);
    }

    private static int parseStatus(String statusLine) throws SseProtocolException {
        var firstSpace = statusLine.indexOf(' ');
        if (firstSpace <= 0 || !(statusLine.substring(0, firstSpace).equals("HTTP/1.1")
                || statusLine.substring(0, firstSpace).equals("HTTP/1.0"))) {
            throw new SseProtocolException("SSE response has an unsupported HTTP status line");
        }
        var index = firstSpace;
        while (index < statusLine.length() && statusLine.charAt(index) == ' ') {
            index++;
        }
        if (index + 3 > statusLine.length()) {
            throw new SseProtocolException("SSE response status is missing");
        }
        var code = statusLine.substring(index, index + 3);
        if (code.charAt(0) < '0' || code.charAt(0) > '9'
                || code.charAt(1) < '0' || code.charAt(1) > '9'
                || code.charAt(2) < '0' || code.charAt(2) > '9') {
            throw new SseProtocolException("SSE response status is invalid");
        }
        if (index + 3 < statusLine.length() && statusLine.charAt(index + 3) != ' ') {
            throw new SseProtocolException("SSE response status is malformed");
        }
        return Integer.parseInt(code);
    }

    private record Exchange(SseResponseInfo response, InputStream body) {
    }

    private record ResponseHead(int status, Headers headers) {
    }

    private record HttpLine(String value, int wireBytes) {
    }

    private record SseLine(String value, int wireBytes) {
    }

    /** Mutable stream protocol state, confined to the single client reader virtual thread. */
    private static final class StreamState {
        private String lastEventId;
        private Duration reconnectDelay;

        private StreamState(String lastEventId, Duration reconnectDelay) {
            this.lastEventId = lastEventId;
            this.reconnectDelay = reconnectDelay;
        }
    }

    /**
     * Applies one absolute deadline while the status line and response headers are read.
     * It wraps the parser-facing side of the buffered input, so even already-prefetched header
     * bytes cannot move response-head acceptance past the original deadline. Each potentially
     * blocking socket read receives the remaining timeout against that same deadline.
     */
    private static final class ResponseHeadDeadlineInputStream extends InputStream {
        private final InputStream source;
        private final Socket socket;
        private final long deadlineNanos;
        private boolean responseHeadComplete;

        private ResponseHeadDeadlineInputStream(InputStream source, Socket socket, Duration timeout) {
            this.source = Objects.requireNonNull(source, "source");
            this.socket = Objects.requireNonNull(socket, "socket");
            // responseOpenTimeout is constrained to a positive socket-timeout range by
            // openExchange, so this addition remains well below the wraparound interval used by
            // System.nanoTime deadline arithmetic.
            deadlineNanos = System.nanoTime() + timeout.toNanos();
        }

        @Override
        public int read() throws IOException {
            applyRemainingTimeout();
            return source.read();
        }

        @Override
        public int read(byte[] target, int offset, int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, target.length);
            if (length == 0) {
                return 0;
            }
            applyRemainingTimeout();
            return source.read(target, offset, length);
        }

        private void applyRemainingTimeout() throws SocketTimeoutException, IOException {
            if (responseHeadComplete) {
                return;
            }
            var remainingNanos = deadlineNanos - System.nanoTime();
            if (remainingNanos <= 0) {
                throw new SocketTimeoutException("SSE response headers exceeded responseOpenTimeout");
            }
            // Socket timeouts use whole milliseconds. Round upward so a positive sub-millisecond
            // remainder remains a real bounded wait rather than becoming an accidental infinite
            // timeout through a zero value.
            var remainingMillis = Math.max(1L, (remainingNanos + 999_999L) / 1_000_000L);
            socket.setSoTimeout((int) Math.min(Integer.MAX_VALUE, remainingMillis));
        }

        private void completeResponseHead() {
            responseHeadComplete = true;
        }
    }

    /** Decodes an HTTP/1.1 chunked body without buffering chunks beyond the caller's read request. */
    private static final class ChunkedInputStream extends InputStream {
        private static final int MAXIMUM_CHUNK_LINE_BYTES = 8 * 1024;
        private static final int MAXIMUM_TRAILER_BYTES = 16 * 1024;
        private static final int MAXIMUM_TRAILER_COUNT = 100;

        private final InputStream source;
        private long remaining;
        private boolean terminal;

        private ChunkedInputStream(InputStream source) {
            this.source = Objects.requireNonNull(source, "source");
        }

        @Override
        public int read() throws IOException {
            if (!ensureChunk()) {
                return -1;
            }
            var value = source.read();
            if (value < 0) {
                throw new EOFException("chunked SSE response ended inside a chunk");
            }
            if (--remaining == 0) {
                requireChunkTerminator();
            }
            return value;
        }

        @Override
        public int read(byte[] target, int offset, int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, target.length);
            if (length == 0) {
                return 0;
            }
            if (!ensureChunk()) {
                return -1;
            }
            var maximum = (int) Math.min(length, remaining);
            var read = source.read(target, offset, maximum);
            if (read < 0) {
                throw new EOFException("chunked SSE response ended inside a chunk");
            }
            remaining -= read;
            if (remaining == 0) {
                requireChunkTerminator();
            }
            return read;
        }

        private boolean ensureChunk() throws IOException {
            if (terminal) {
                return false;
            }
            if (remaining > 0) {
                return true;
            }
            var line = readHttpLine(source, MAXIMUM_CHUNK_LINE_BYTES);
            if (line == null || line.value().isEmpty()) {
                throw new SseProtocolException("chunked SSE response has an invalid chunk size line");
            }
            var separator = line.value().indexOf(';');
            var encodedSize = (separator < 0 ? line.value() : line.value().substring(0, separator)).trim();
            final long size;
            try {
                if (encodedSize.isEmpty() || encodedSize.charAt(0) == '+') {
                    throw new NumberFormatException("invalid chunk size");
                }
                size = Long.parseLong(encodedSize, 16);
            } catch (NumberFormatException invalidSize) {
                throw new SseProtocolException("chunked SSE response has an invalid chunk size", invalidSize);
            }
            if (size < 0) {
                throw new SseProtocolException("chunked SSE response has a negative chunk size");
            }
            if (size == 0) {
                consumeTrailers();
                terminal = true;
                return false;
            }
            remaining = size;
            return true;
        }

        private void requireChunkTerminator() throws IOException {
            if (source.read() != '\r' || source.read() != '\n') {
                throw new SseProtocolException("chunked SSE response chunk is missing CRLF terminator");
            }
        }

        private void consumeTrailers() throws IOException {
            var bytes = 0;
            var count = 0;
            while (true) {
                var line = readHttpLine(source, MAXIMUM_TRAILER_BYTES - bytes);
                if (line == null) {
                    throw new EOFException("chunked SSE response ended before terminal trailers");
                }
                bytes = addHeaderBytes(bytes, line.wireBytes(), MAXIMUM_TRAILER_BYTES);
                if (line.value().isEmpty()) {
                    return;
                }
                if (++count > MAXIMUM_TRAILER_COUNT || line.value().indexOf(':') <= 0) {
                    throw new SseProtocolException("chunked SSE response has invalid trailers");
                }
            }
        }
    }

    /** Limits a fixed-length response body without buffering it. */
    private static final class LimitedInputStream extends InputStream {
        private final InputStream source;
        private long remaining;

        private LimitedInputStream(InputStream source, long remaining) {
            this.source = Objects.requireNonNull(source, "source");
            this.remaining = remaining;
        }

        @Override
        public int read() throws IOException {
            if (remaining == 0) {
                return -1;
            }
            var value = source.read();
            if (value < 0) {
                throw new EOFException("fixed-length SSE response ended early");
            }
            remaining--;
            return value;
        }

        @Override
        public int read(byte[] target, int offset, int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, target.length);
            if (length == 0) {
                return 0;
            }
            if (remaining == 0) {
                return -1;
            }
            var read = source.read(target, offset, (int) Math.min(length, remaining));
            if (read < 0) {
                throw new EOFException("fixed-length SSE response ended early");
            }
            remaining -= read;
            return read;
        }
    }

    /** Builder for a bounded direct-HTTP {@link SseClient}. */
    public static final class Builder {
        private Duration connectTimeout = Duration.ofSeconds(10);
        private Duration responseOpenTimeout = DEFAULT_RESPONSE_OPEN_TIMEOUT;
        private Duration idleTimeout = DEFAULT_IDLE_TIMEOUT;
        private Duration initialReconnectDelay = Duration.ofSeconds(1);
        private int maximumReconnectAttempts = DEFAULT_MAXIMUM_RECONNECT_ATTEMPTS;
        private int maximumHeaderBytes = DEFAULT_MAXIMUM_HEADER_BYTES;
        private int maximumHeaderCount = DEFAULT_MAXIMUM_HEADER_COUNT;
        private int maximumLineBytes = DEFAULT_MAXIMUM_LINE_BYTES;
        private int maximumEventBytes = DEFAULT_MAXIMUM_EVENT_BYTES;
        private int maximumConnections = DEFAULT_MAXIMUM_CONNECTIONS;
        private Duration maximumReconnectDelay = DEFAULT_MAXIMUM_RECONNECT_DELAY;
        private String initialLastEventId;

        private Builder() {
        }

        /** Sets a strictly positive direct HTTP connect timeout. */
        public Builder connectTimeout(Duration connectTimeout) {
            this.connectTimeout = requirePositiveDuration(connectTimeout, "connectTimeout");
            return this;
        }

        /** Sets the finite deadline for receiving a complete status line and response headers. */
        public Builder responseOpenTimeout(Duration responseOpenTimeout) {
            this.responseOpenTimeout = requirePositiveDuration(responseOpenTimeout, "responseOpenTimeout");
            return this;
        }

        /** Sets the finite maximum silent interval after an SSE response has opened. */
        public Builder idleTimeout(Duration idleTimeout) {
            this.idleTimeout = requirePositiveDuration(idleTimeout, "idleTimeout");
            return this;
        }

        /** Sets the delay used before a server has supplied a valid {@code retry:} field. */
        public Builder initialReconnectDelay(Duration initialReconnectDelay) {
            this.initialReconnectDelay = requireNonNegativeDuration(initialReconnectDelay, "initialReconnectDelay");
            return this;
        }

        /** Sets the finite number of reconnection exchanges permitted after the initial request. */
        public Builder maximumReconnectAttempts(int maximumReconnectAttempts) {
            if (maximumReconnectAttempts < 0) {
                throw new IllegalArgumentException("maximumReconnectAttempts must not be negative");
            }
            this.maximumReconnectAttempts = maximumReconnectAttempts;
            return this;
        }

        /** Sets the hard byte limit enforced before retaining a response status/header line. */
        public Builder maximumHeaderBytes(int maximumHeaderBytes) {
            this.maximumHeaderBytes = requirePositive(maximumHeaderBytes, "maximumHeaderBytes");
            return this;
        }

        /** Sets the hard field-count limit enforced while parsing response headers. */
        public Builder maximumHeaderCount(int maximumHeaderCount) {
            this.maximumHeaderCount = requirePositive(maximumHeaderCount, "maximumHeaderCount");
            return this;
        }

        /** Sets the maximum UTF-8 bytes in a single SSE wire line. */
        public Builder maximumLineBytes(int maximumLineBytes) {
            this.maximumLineBytes = requirePositive(maximumLineBytes, "maximumLineBytes");
            return this;
        }

        /** Sets the maximum bytes accumulated for one event before dispatch. */
        public Builder maximumEventBytes(int maximumEventBytes) {
            this.maximumEventBytes = requirePositive(maximumEventBytes, "maximumEventBytes");
            return this;
        }

        /** Sets the finite number of concurrently live or reconnecting subscriptions. */
        public Builder maximumConnections(int maximumConnections) {
            this.maximumConnections = requirePositive(maximumConnections, "maximumConnections");
            return this;
        }

        /**
         * Sets the upper bound applied to a server-provided {@code retry:} delay. Zero disables
         * waiting while retaining reconnect semantics.
         */
        public Builder maximumReconnectDelay(Duration maximumReconnectDelay) {
            this.maximumReconnectDelay = requireNonNegativeDuration(maximumReconnectDelay, "maximumReconnectDelay");
            return this;
        }

        /** Sets an initial last-event ID to send on the first request, if non-empty. */
        public Builder initialLastEventId(String initialLastEventId) {
            var value = Objects.requireNonNull(initialLastEventId, "initialLastEventId");
            if (!isSafeHttpHeaderValue(value)) {
                throw new IllegalArgumentException("initialLastEventId must be safe for an HTTP header");
            }
            this.initialLastEventId = value;
            return this;
        }

        /** Builds an independent client. */
        public SseClient build() {
            return new SseClient(this);
        }

        private static int requirePositive(int value, String name) {
            if (value <= 0) {
                throw new IllegalArgumentException(name + " must be greater than zero: " + value);
            }
            return value;
        }

        private static Duration requirePositiveDuration(Duration value, String name) {
            var duration = Objects.requireNonNull(value, name);
            if (duration.isZero() || duration.isNegative()) {
                throw new IllegalArgumentException(name + " must be greater than zero");
            }
            return duration;
        }

        private static Duration requireNonNegativeDuration(Duration value, String name) {
            var duration = Objects.requireNonNull(value, name);
            if (duration.isNegative()) {
                throw new IllegalArgumentException(name + " must not be negative");
            }
            return duration;
        }
    }
}

