package io.wavejava.wave.testing;

import io.wavejava.wave.api.http.Headers;
import io.wavejava.wave.api.http.HttpMethod;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A bounded loopback HTTP/1.1 upstream fixture for client and gateway integration tests.
 *
 * <p>The fixture accepts only finite {@code Content-Length} message bodies (up to 8 MiB) and
 * owns every listening and accepted socket. It is deliberately small: it is useful for testing
 * client transport contracts without making a production HTTP-server API part of the testing
 * surface. Use {@link Response#disconnect()} to model an upstream transport disconnect before a
 * response is written.</p>
 */
public final class MockUpstream implements AutoCloseable {
    private static final int MAXIMUM_HEADER_BYTES = 64 * 1024;
    private static final int MAXIMUM_BODY_BYTES = 8 * 1024 * 1024;
    private static final String LOOPBACK_HOST = "127.0.0.1";

    private final ServerSocket listener;
    private final Handler handler;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final Set<Socket> connections = ConcurrentHashMap.newKeySet();
    private final CopyOnWriteArrayList<Request> requests = new CopyOnWriteArrayList<>();
    private final AtomicReference<Throwable> failure = new AtomicReference<>();
    private final Thread acceptor;

    private MockUpstream(Handler handler) throws IOException {
        this.handler = Objects.requireNonNull(handler, "handler");
        listener = new ServerSocket();
        listener.setReuseAddress(true);
        listener.bind(new InetSocketAddress(LOOPBACK_HOST, 0));
        acceptor = Thread.ofVirtual().name("wave-mock-upstream-accept").start(this::acceptLoop);
    }

    /** Starts a loopback upstream using {@code handler} for each received request. */
    public static MockUpstream start(Handler handler) {
        try {
            return new MockUpstream(handler);
        } catch (IOException failure) {
            throw new IllegalStateException("Could not start mock upstream", failure);
        }
    }

    /**
     * Starts a loopback HTTP proxy that forwards absolute-form {@code http} requests.
     *
     * <p>This fixture intentionally does not implement {@code CONNECT}; it exists to exercise
     * ordinary HTTP proxy forwarding and leaves TLS tunnelling to a dedicated transport fixture.
     * The proxy forwards request method, end-to-end headers, and a bounded fixed-length body to
     * the requested origin, then relays the bounded response.</p>
     */
    public static MockUpstream forwardingProxy() {
        return start(MockUpstream::forward);
    }

    /** Returns the loopback authority URI ending in a slash. */
    public URI baseUri() {
        return URI.create("http://" + LOOPBACK_HOST + ':' + port() + '/');
    }

    /** Returns the OS-selected loopback TCP port. */
    public int port() {
        return listener.getLocalPort();
    }

    /** Returns whether this fixture is still accepting connections. */
    public boolean isRunning() {
        return running.get();
    }

    /** Returns the number of fully parsed requests observed by this fixture. */
    public int requestCount() {
        return requests.size();
    }

    /** Returns immutable snapshots of all requests observed in arrival order. */
    public List<Request> requests() {
        return List.copyOf(requests);
    }

    /** Returns a fixture-side failure, if a handler or accept loop encountered one. */
    public Optional<Throwable> failure() {
        return Optional.ofNullable(failure.get());
    }

    /** Fails the calling test if this fixture observed an unexpected internal failure. */
    public void assertHealthy() {
        var observed = failure.get();
        if (observed != null) {
            throw new AssertionError("Mock upstream failed", observed);
        }
    }

    /**
     * Stops accepting connections and closes every active accepted socket. This operation is
     * idempotent and does not wait for application-owned handler latches.
     */
    @Override
    public void close() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        closeQuietly(listener);
        for (var connection : connections) {
            closeQuietly(connection);
        }
    }

    private void acceptLoop() {
        while (running.get()) {
            final Socket connection;
            try {
                connection = listener.accept();
            } catch (SocketException closed) {
                if (running.get()) {
                    recordFailure(closed);
                }
                return;
            } catch (IOException failure) {
                if (running.get()) {
                    recordFailure(failure);
                }
                return;
            }
            if (!running.get()) {
                closeQuietly(connection);
                return;
            }
            connections.add(connection);
            Thread.ofVirtual().name("wave-mock-upstream-connection").start(() -> serve(connection));
        }
    }

    private void serve(Socket connection) {
        try (connection;
                var input = new BufferedInputStream(connection.getInputStream());
                var output = new BufferedOutputStream(connection.getOutputStream())) {
            connection.setTcpNoDelay(true);
            while (running.get() && !connection.isClosed()) {
                var request = readRequest(input);
                if (request == null) {
                    return;
                }
                requests.add(request);
                final Response response;
                try {
                    response = Objects.requireNonNull(handler.handle(request), "Mock upstream handler returned null");
                } catch (Throwable handlerFailure) {
                    recordFailure(handlerFailure);
                    return;
                }
                if (response.disconnects()) {
                    return;
                }
                var closeAfterResponse = response.closesConnection() || requestsConnectionClose(request.headers());
                writeResponse(output, response, closeAfterResponse, request.method().equals(HttpMethod.HEAD));
                if (closeAfterResponse) {
                    return;
                }
            }
        } catch (IOException ignored) {
            // A client can cancel or close a request at any point; that is expected fixture input.
        } finally {
            connections.remove(connection);
        }
    }

    private void recordFailure(Throwable observed) {
        failure.compareAndSet(null, observed);
    }

    private static Response forward(Request request) throws IOException {
        final URI destination;
        try {
            destination = URI.create(request.target());
        } catch (IllegalArgumentException malformed) {
            return Response.text(400, "Proxy request target must be an absolute URI").closeConnection();
        }
        if (!destination.isAbsolute() || !"http".equalsIgnoreCase(destination.getScheme())
                || destination.getHost() == null || destination.getUserInfo() != null) {
            return Response.text(400, "Mock proxy supports only absolute http request targets").closeConnection();
        }

        try (var origin = new Socket()) {
            origin.connect(new InetSocketAddress(destination.getHost(), effectivePort(destination)), 5_000);
            origin.setSoTimeout(5_000);
            try (var output = new BufferedOutputStream(origin.getOutputStream());
                    var input = new BufferedInputStream(origin.getInputStream())) {
                writeForwardedRequest(output, request, destination);
                var response = readResponse(input);
                return Response.of(response.status(), response.headers(), response.body());
            }
        }
    }

    private static int effectivePort(URI uri) {
        return uri.getPort() < 0 ? 80 : uri.getPort();
    }

    private static void writeForwardedRequest(OutputStream output, Request request, URI destination) throws IOException {
        var body = request.body;
        var target = destination.getRawPath();
        if (target == null || target.isEmpty()) {
            target = "/";
        }
        if (destination.getRawQuery() != null) {
            target += '?' + destination.getRawQuery();
        }
        writeAscii(output, request.method().name() + ' ' + target + " HTTP/1.1\r\n");
        writeAscii(output, "Host: " + hostHeader(destination) + "\r\n");
        for (var entry : request.headers().asMap().entrySet()) {
            if (isHopByHopOrFramingHeader(entry.getKey())) {
                continue;
            }
            for (var value : entry.getValue()) {
                writeAscii(output, entry.getKey() + ": " + value + "\r\n");
            }
        }
        writeAscii(output, "Content-Length: " + body.length + "\r\n");
        writeAscii(output, "Connection: close\r\n\r\n");
        output.write(body);
        output.flush();
    }

    private static String hostHeader(URI destination) {
        return destination.getPort() < 0 ? destination.getHost() : destination.getHost() + ':' + destination.getPort();
    }

    private static boolean isHopByHopOrFramingHeader(String name) {
        return switch (name.toLowerCase(Locale.ROOT)) {
            case "connection", "proxy-connection", "proxy-authorization", "keep-alive", "te", "trailer",
                    "transfer-encoding", "upgrade", "host", "content-length" -> true;
            default -> false;
        };
    }

    private static Request readRequest(InputStream input) throws IOException {
        var startLine = readLine(input, MAXIMUM_HEADER_BYTES);
        if (startLine == null) {
            return null;
        }
        var pieces = startLine.split(" ", 3);
        if (pieces.length != 3 || !pieces[2].startsWith("HTTP/")) {
            throw new IOException("Malformed HTTP request line: " + startLine);
        }
        final HttpMethod method;
        try {
            method = HttpMethod.of(pieces[0]);
        } catch (IllegalArgumentException malformed) {
            throw new IOException("Malformed HTTP request method", malformed);
        }
        var headers = readHeaders(input);
        return new Request(method, pieces[1], pieces[2], headers, readBody(input, headers));
    }

    private static UpstreamResponse readResponse(InputStream input) throws IOException {
        var startLine = readLine(input, MAXIMUM_HEADER_BYTES);
        if (startLine == null) {
            throw new EOFException("Origin disconnected before a response");
        }
        var pieces = startLine.split(" ", 3);
        if (pieces.length < 2 || !pieces[0].startsWith("HTTP/")) {
            throw new IOException("Malformed HTTP response line: " + startLine);
        }
        final int status;
        try {
            status = Integer.parseInt(pieces[1]);
        } catch (NumberFormatException malformed) {
            throw new IOException("Malformed HTTP response status", malformed);
        }
        var headers = readHeaders(input);
        return new UpstreamResponse(status, headers, readBody(input, headers));
    }

    private static Headers readHeaders(InputStream input) throws IOException {
        var headers = Headers.builder();
        var totalBytes = 0;
        while (true) {
            var line = readLine(input, MAXIMUM_HEADER_BYTES);
            if (line == null) {
                throw new EOFException("Message ended before the header block");
            }
            totalBytes += line.getBytes(StandardCharsets.ISO_8859_1).length + 2;
            if (totalBytes > MAXIMUM_HEADER_BYTES) {
                throw new IOException("HTTP header block exceeds " + MAXIMUM_HEADER_BYTES + " bytes");
            }
            if (line.isEmpty()) {
                return headers.build();
            }
            var delimiter = line.indexOf(':');
            if (delimiter <= 0) {
                throw new IOException("Malformed HTTP header: " + line);
            }
            try {
                headers.add(line.substring(0, delimiter), trimOws(line.substring(delimiter + 1)));
            } catch (IllegalArgumentException malformed) {
                throw new IOException("Malformed HTTP header", malformed);
            }
        }
    }

    private static String trimOws(String value) {
        var first = 0;
        var last = value.length();
        while (first < last && (value.charAt(first) == ' ' || value.charAt(first) == '\t')) {
            first++;
        }
        while (last > first && (value.charAt(last - 1) == ' ' || value.charAt(last - 1) == '\t')) {
            last--;
        }
        return value.substring(first, last);
    }

    private static byte[] readBody(InputStream input, Headers headers) throws IOException {
        if (headers.contains("Transfer-Encoding")) {
            throw new IOException("Mock upstream supports only Content-Length framed bodies");
        }
        var length = contentLength(headers);
        if (length == 0) {
            return new byte[0];
        }
        var body = input.readNBytes(length);
        if (body.length != length) {
            throw new EOFException("Message ended before its Content-Length body completed");
        }
        return body;
    }

    private static int contentLength(Headers headers) throws IOException {
        var values = headers.all("Content-Length");
        if (values.isEmpty()) {
            return 0;
        }
        long expected = -1;
        for (var value : values) {
            final long parsed;
            try {
                parsed = Long.parseLong(value);
            } catch (NumberFormatException malformed) {
                throw new IOException("Invalid Content-Length", malformed);
            }
            if (parsed < 0 || parsed > MAXIMUM_BODY_BYTES) {
                throw new IOException("HTTP body exceeds " + MAXIMUM_BODY_BYTES + " bytes");
            }
            if (expected >= 0 && expected != parsed) {
                throw new IOException("Conflicting Content-Length headers");
            }
            expected = parsed;
        }
        return (int) expected;
    }

    private static String readLine(InputStream input, int maximumBytes) throws IOException {
        var line = new ByteArrayOutputStream(Math.min(256, maximumBytes));
        while (true) {
            var next = input.read();
            if (next < 0) {
                if (line.size() == 0) {
                    return null;
                }
                throw new EOFException("HTTP line ended before CRLF");
            }
            if (line.size() >= maximumBytes) {
                throw new IOException("HTTP line exceeds " + maximumBytes + " bytes");
            }
            if (next == '\n') {
                var bytes = line.toByteArray();
                if (bytes.length == 0 || bytes[bytes.length - 1] != '\r') {
                    throw new IOException("HTTP line must end with CRLF");
                }
                return new String(bytes, 0, bytes.length - 1, StandardCharsets.ISO_8859_1);
            }
            line.write(next);
        }
    }

    private static void writeResponse(OutputStream output, Response response, boolean close, boolean headRequest)
            throws IOException {
        var body = response.body;
        writeAscii(output, "HTTP/1.1 " + response.status() + " " + reasonPhrase(response.status()) + "\r\n");
        for (var entry : response.headers().asMap().entrySet()) {
            if (isHopByHopOrFramingHeader(entry.getKey())) {
                continue;
            }
            for (var value : entry.getValue()) {
                writeAscii(output, entry.getKey() + ": " + value + "\r\n");
            }
        }
        writeAscii(output, "Content-Length: " + body.length + "\r\n");
        writeAscii(output, "Connection: " + (close ? "close" : "keep-alive") + "\r\n\r\n");
        if (!headRequest) {
            output.write(body);
        }
        output.flush();
    }

    private static String reasonPhrase(int status) {
        return switch (status) {
            case 200 -> "OK";
            case 201 -> "Created";
            case 202 -> "Accepted";
            case 204 -> "No Content";
            case 301 -> "Moved Permanently";
            case 302 -> "Found";
            case 303 -> "See Other";
            case 307 -> "Temporary Redirect";
            case 308 -> "Permanent Redirect";
            case 400 -> "Bad Request";
            case 404 -> "Not Found";
            case 500 -> "Internal Server Error";
            case 502 -> "Bad Gateway";
            default -> "Mock Upstream";
        };
    }

    private static boolean requestsConnectionClose(Headers headers) {
        return headers.all("Connection").stream()
                .flatMap(value -> List.of(value.split(",")).stream())
                .map(String::trim)
                .anyMatch(value -> value.equalsIgnoreCase("close"));
    }

    private static void writeAscii(OutputStream output, String text) throws IOException {
        output.write(text.getBytes(StandardCharsets.ISO_8859_1));
    }

    private static void closeQuietly(AutoCloseable closeable) {
        try {
            closeable.close();
        } catch (Exception ignored) {
            // Fixture shutdown should release all other sockets even if one close fails.
        }
    }

    /** Handles one bounded HTTP request received by a {@link MockUpstream}. */
    @FunctionalInterface
    public interface Handler {
        /** Returns the response action for the supplied immutable request snapshot. */
        Response handle(Request request) throws Exception;
    }

    /** Immutable bounded request snapshot observed by this fixture. */
    public record Request(HttpMethod method, String target, String version, Headers headers, byte[] body) {
        public Request {
            method = Objects.requireNonNull(method, "method");
            target = Objects.requireNonNull(target, "target");
            version = Objects.requireNonNull(version, "version");
            headers = Objects.requireNonNull(headers, "headers");
            body = Objects.requireNonNull(body, "body").clone();
            if (body.length > MAXIMUM_BODY_BYTES) {
                throw new IllegalArgumentException("HTTP body exceeds " + MAXIMUM_BODY_BYTES + " bytes");
            }
        }

        /** Returns a defensive copy of the fixed-length request body. */
        @Override
        public byte[] body() {
            return body.clone();
        }

        /** Decodes the request body as UTF-8 for concise test assertions. */
        public String text() {
            return new String(body, StandardCharsets.UTF_8);
        }
    }

    /** One deterministic response action for a {@link MockUpstream} handler. */
    public static final class Response {
        private final int status;
        private final Headers headers;
        private final byte[] body;
        private final boolean disconnect;
        private final boolean closeConnection;

        private Response(int status, Headers headers, byte[] body, boolean disconnect, boolean closeConnection) {
            if (!disconnect && (status < 100 || status > 999)) {
                throw new IllegalArgumentException("Invalid HTTP response status: " + status);
            }
            this.status = status;
            this.headers = Objects.requireNonNull(headers, "headers");
            this.body = Objects.requireNonNull(body, "body").clone();
            if (this.body.length > MAXIMUM_BODY_BYTES) {
                throw new IllegalArgumentException("HTTP body exceeds " + MAXIMUM_BODY_BYTES + " bytes");
            }
            this.disconnect = disconnect;
            this.closeConnection = closeConnection;
        }

        /** Returns a successful or error response with no body. */
        public static Response empty(int status) {
            return of(status, Headers.empty(), new byte[0]);
        }

        /** Returns a UTF-8 text response. */
        public static Response text(int status, String body) {
            return of(status, Headers.of("Content-Type", "text/plain; charset=UTF-8"),
                    Objects.requireNonNull(body, "body").getBytes(StandardCharsets.UTF_8));
        }

        /** Returns a byte response with no additional headers. */
        public static Response bytes(int status, byte[] body) {
            return of(status, Headers.empty(), body);
        }

        /** Returns a byte response with immutable supplied headers. Content-Length is generated by the fixture. */
        public static Response of(int status, Headers headers, byte[] body) {
            return new Response(status, headers, body, false, false);
        }

        /** Closes the TCP connection before writing any HTTP response bytes. */
        public static Response disconnect() {
            return new Response(0, Headers.empty(), new byte[0], true, true);
        }

        /** Returns an equivalent response that closes the connection after its body is written. */
        public Response closeConnection() {
            if (disconnect) {
                return this;
            }
            return new Response(status, headers, body, false, true);
        }

        /** Returns the response status. Calling this for {@link #disconnect()} is invalid. */
        public int status() {
            if (disconnect) {
                throw new IllegalStateException("A disconnect action has no HTTP response status");
            }
            return status;
        }

        /** Returns immutable response headers. */
        public Headers headers() {
            return headers;
        }

        /** Returns a defensive copy of the fixed-length response body. */
        public byte[] body() {
            return body.clone();
        }

        /** Returns whether this action closes the connection without a response. */
        public boolean disconnects() {
            return disconnect;
        }

        /** Returns whether the connection closes after this response. */
        public boolean closesConnection() {
            return closeConnection;
        }
    }

    private record UpstreamResponse(int status, Headers headers, byte[] body) {
        private UpstreamResponse {
            if (status < 100 || status > 999) {
                throw new IllegalArgumentException("Invalid HTTP response status: " + status);
            }
            headers = Objects.requireNonNull(headers, "headers");
            body = Objects.requireNonNull(body, "body").clone();
            if (body.length > MAXIMUM_BODY_BYTES) {
                throw new IllegalArgumentException("HTTP body exceeds " + MAXIMUM_BODY_BYTES + " bytes");
            }
        }

        @Override
        public byte[] body() {
            return body.clone();
        }
    }
}

