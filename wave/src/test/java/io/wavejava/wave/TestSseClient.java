package io.wavejava.wave;

import io.wavejava.wave.api.http.Headers;
import io.wavejava.wave.api.http.MediaType;
import io.wavejava.wave.api.sse.SseEvent;
import io.wavejava.wave.api.sse.SseResponseInfo;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PushbackInputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Test-only direct HTTP/1.1 SSE peer with bounded synchronous reads and no background worker.
 *
 * <p>The fixture intentionally owns only a raw socket. It can verify the server's wire-level
 * event encoding without coupling server tests to the production {@code SseClient}. Use
 * {@link #finishOutput()} for TCP FIN and {@link #abort()} for TCP RST; ordinary {@link #close()}
 * does not make a transport-semantic claim.</p>
 */
final class TestSseClient implements AutoCloseable {
    private static final int DEFAULT_MAXIMUM_RESPONSE_HEADER_BYTES = 16 * 1024;
    private static final int DEFAULT_MAXIMUM_RESPONSE_HEADER_COUNT = 100;
    private static final int DEFAULT_MAXIMUM_LINE_BYTES = 64 * 1024;
    private static final int DEFAULT_MAXIMUM_EVENT_BYTES = 256 * 1024;

    private final Socket socket;
    private final PushbackInputStream body;
    private final Options options;
    private final SseResponseInfo response;
    private boolean firstLine = true;

    private TestSseClient(Socket socket, InputStream body, Options options, SseResponseInfo response) {
        this.socket = socket;
        this.body = new PushbackInputStream(body, 1);
        this.options = options;
        this.response = response;
    }

    /** Opens a direct HTTP SSE connection and requires a valid {@code 200 text/event-stream} response. */
    static TestSseClient connect(URI uri) throws IOException {
        return connect(uri, Options.defaults());
    }

    /** Opens a direct HTTP SSE connection with explicit finite fixture limits. */
    static TestSseClient connect(URI uri, Options options) throws IOException {
        var target = validateUri(uri);
        var configured = Objects.requireNonNull(options, "options");
        var socket = new Socket();
        try {
            socket.setTcpNoDelay(true);
            socket.connect(new InetSocketAddress(target.getHost(), effectivePort(target)), toMillis(configured.connectTimeout()));
            socket.setSoTimeout(toMillis(configured.readTimeout()));
            writeRequest(socket.getOutputStream(), target);

            var input = new BufferedInputStream(socket.getInputStream());
            var response = readResponse(input, target, configured);
            validateSseResponseInfo(response);
            return new TestSseClient(socket, bodyStream(input, response.headers(), configured), configured, response);
        } catch (Throwable failure) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // Preserve the primary fixture failure.
            }
            if (failure instanceof IOException io) {
                throw io;
            }
            throw new IOException("raw SSE fixture could not establish an event stream", failure);
        }
    }

    /** Returns the validated HTTP response that opened this event stream. */
    SseResponseInfo response() {
        return response;
    }

    /**
     * Reads one complete SSE event block, or {@code null} after a clean end of the HTTP body.
     * Comment-only blocks, including heartbeats, are returned as {@link SseEvent} values.
     */
    SseEvent readEvent() throws IOException {
        var event = new EventBuilder(options.maximumEventBytes());
        while (true) {
            var line = readSseLine(body, options.maximumLineBytes());
            if (line == null) {
                return event.build();
            }
            var value = line.value();
            if (firstLine) {
                firstLine = false;
                if (value.startsWith("\uFEFF")) {
                    value = value.substring(1);
                }
            }
            if (value.isEmpty()) {
                var completed = event.build();
                if (completed != null) {
                    return completed;
                }
                event = new EventBuilder(options.maximumEventBytes());
                continue;
            }
            event.accept(value, line.wireBytes());
        }
    }

    /** Sends TCP FIN while retaining the input side for deterministic peer-close assertions. */
    void finishOutput() throws IOException {
        socket.shutdownOutput();
    }

    /** Sends TCP RST by enabling zero linger before closing the socket. */
    void abort() throws IOException {
        socket.setSoLinger(true, 0);
        socket.close();
    }

    /** Returns whether the fixture socket remains open. */
    boolean isOpen() {
        return !socket.isClosed();
    }

    /** Closes the fixture without claiming an SSE close, TCP FIN, or TCP RST semantic. */
    @Override
    public void close() throws IOException {
        socket.close();
    }

    /** Immutable finite limits for the test-only peer. */
    record Options(
            Duration connectTimeout,
            Duration readTimeout,
            int maximumResponseHeaderBytes,
            int maximumResponseHeaderCount,
            int maximumLineBytes,
            int maximumEventBytes) {
        Options {
            connectTimeout = requirePositive(connectTimeout, "connectTimeout");
            readTimeout = requirePositive(readTimeout, "readTimeout");
            if (maximumResponseHeaderBytes <= 0 || maximumResponseHeaderCount <= 0
                    || maximumLineBytes <= 0 || maximumEventBytes <= 0) {
                throw new IllegalArgumentException("fixture limits must be greater than zero");
            }
        }

        static Options defaults() {
            return new Options(
                    Duration.ofSeconds(5),
                    Duration.ofSeconds(5),
                    DEFAULT_MAXIMUM_RESPONSE_HEADER_BYTES,
                    DEFAULT_MAXIMUM_RESPONSE_HEADER_COUNT,
                    DEFAULT_MAXIMUM_LINE_BYTES,
                    DEFAULT_MAXIMUM_EVENT_BYTES);
        }
    }

    private static SseResponseInfo readResponse(InputStream input, URI uri, Options options) throws IOException {
        var remainingHeaderBytes = options.maximumResponseHeaderBytes();
        var status = requiredLine(input, remainingHeaderBytes, "HTTP response status");
        remainingHeaderBytes -= status.wireBytes();
        var statusCode = parseStatus(status.value());
        var headers = Headers.builder();
        var count = 0;
        while (true) {
            var line = requiredLine(input, remainingHeaderBytes, "HTTP response header");
            remainingHeaderBytes -= line.wireBytes();
            if (line.value().isEmpty()) {
                return new SseResponseInfo(statusCode, headers.build(), uri);
            }
            if (++count > options.maximumResponseHeaderCount()) {
                throw new IOException("SSE response exceeded fixture maximumResponseHeaderCount");
            }
            var separator = line.value().indexOf(':');
            if (separator <= 0) {
                throw new IOException("SSE response has an invalid header line");
            }
            try {
                headers.add(line.value().substring(0, separator), line.value().substring(separator + 1).strip());
            } catch (IllegalArgumentException invalid) {
                throw new IOException("SSE response has an invalid header", invalid);
            }
        }
    }

    private static void validateSseResponseInfo(SseResponseInfo response) throws IOException {
        if (response.status() != 200) {
            throw new IOException("expected SSE HTTP 200 but received HTTP " + response.status());
        }
        var contentType = response.headers().first("Content-Type")
                .orElseThrow(() -> new IOException("SSE response is missing Content-Type"));
        final MediaType mediaType;
        try {
            mediaType = MediaType.parse(contentType);
        } catch (IllegalArgumentException invalid) {
            throw new IOException("SSE response has an invalid Content-Type", invalid);
        }
        if (!mediaType.withoutParameter("charset").equals(MediaType.TEXT_EVENT_STREAM)) {
            throw new IOException("SSE response Content-Type must be text/event-stream");
        }
        try {
            if (mediaType.charset().isPresent() && !StandardCharsets.UTF_8.equals(mediaType.charset().orElseThrow())) {
                throw new IOException("SSE response Content-Type charset must be UTF-8");
            }
        } catch (IllegalArgumentException unsupportedCharset) {
            throw new IOException("SSE response Content-Type has an unsupported charset", unsupportedCharset);
        }
    }

    private static InputStream bodyStream(InputStream input, Headers headers, Options options) throws IOException {
        var transferCodings = headers.all("Transfer-Encoding").stream()
                .flatMap(value -> List.of(value.split(",")).stream())
                .map(String::strip)
                .filter(value -> !value.isEmpty())
                .map(value -> value.toLowerCase(Locale.ROOT))
                .toList();
        var contentLengths = headers.all("Content-Length");
        if (!transferCodings.isEmpty() && !contentLengths.isEmpty()) {
            throw new IOException("SSE response cannot contain both Transfer-Encoding and Content-Length");
        }
        if (!transferCodings.isEmpty()) {
            if (transferCodings.size() != 1 || !"chunked".equals(transferCodings.getFirst())) {
                throw new IOException("fixture only supports chunked SSE Transfer-Encoding");
            }
            return new ChunkedInputStream(input, options.maximumResponseHeaderBytes());
        }
        if (contentLengths.isEmpty()) {
            return input;
        }
        if (contentLengths.size() != 1) {
            throw new IOException("SSE response has ambiguous Content-Length");
        }
        final long length;
        try {
            var value = contentLengths.getFirst().strip();
            if (value.isEmpty() || value.startsWith("+")) {
                throw new NumberFormatException("invalid Content-Length");
            }
            length = Long.parseLong(value);
        } catch (NumberFormatException invalid) {
            throw new IOException("SSE response has an invalid Content-Length", invalid);
        }
        if (length < 0) {
            throw new IOException("SSE response has a negative Content-Length");
        }
        return new LimitedInputStream(input, length);
    }

    private static SseLine readSseLine(PushbackInputStream input, int maximumBytes) throws IOException {
        var bytes = new ByteArrayOutputStream(Math.min(maximumBytes, 256));
        var wireBytes = 0;
        while (true) {
            var next = input.read();
            if (next < 0) {
                return bytes.size() == 0 ? null : new SseLine(decodeUtf8(bytes.toByteArray()), wireBytes);
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
                throw new IOException("SSE line exceeded fixture maximumLineBytes");
            }
            bytes.write(next);
            wireBytes++;
        }
    }

    private static HttpLine requiredLine(InputStream input, int remainingBytes, String what) throws IOException {
        if (remainingBytes < 2) {
            throw new IOException("SSE response exceeded fixture maximumResponseHeaderBytes");
        }
        var bytes = new ByteArrayOutputStream(Math.min(remainingBytes, 256));
        while (true) {
            var next = input.read();
            if (next < 0) {
                throw new EOFException("connection ended before " + what + " completed");
            }
            if (next == '\r') {
                if (bytes.size() + 2 > remainingBytes) {
                    throw new IOException("SSE response exceeded fixture maximumResponseHeaderBytes");
                }
                if (input.read() != '\n') {
                    throw new IOException("SSE response header line was not CRLF terminated");
                }
                return new HttpLine(bytes.toString(StandardCharsets.ISO_8859_1), bytes.size() + 2);
            }
            if (next == '\n') {
                throw new IOException("SSE response header line was not CRLF terminated");
            }
            if (bytes.size() + 2 >= remainingBytes) {
                throw new IOException("SSE response exceeded fixture maximumResponseHeaderBytes");
            }
            bytes.write(next);
        }
    }

    private static int parseStatus(String line) throws IOException {
        var parts = line.split(" ", 3);
        if (parts.length < 2 || !("HTTP/1.1".equals(parts[0]) || "HTTP/1.0".equals(parts[0]))) {
            throw new IOException("SSE response has an invalid HTTP status line");
        }
        try {
            var status = Integer.parseInt(parts[1]);
            if (status < 100 || status > 999) {
                throw new NumberFormatException("status outside HTTP range");
            }
            return status;
        } catch (NumberFormatException invalid) {
            throw new IOException("SSE response has an invalid HTTP status", invalid);
        }
    }

    private static URI validateUri(URI uri) {
        Objects.requireNonNull(uri, "uri");
        if (!uri.isAbsolute() || uri.getHost() == null || !"http".equalsIgnoreCase(uri.getScheme())
                || uri.getUserInfo() != null || uri.getRawFragment() != null || uri.getPort() > 65_535) {
            throw new IllegalArgumentException("fixture target must be an absolute direct http URI: " + uri);
        }
        return uri;
    }

    private static int effectivePort(URI uri) {
        return uri.getPort() < 0 ? 80 : uri.getPort();
    }

    private static void writeRequest(OutputStream output, URI uri) throws IOException {
        var path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
        if (uri.getRawQuery() != null) {
            path += '?' + uri.getRawQuery();
        }
        var host = uri.getHost().contains(":") ? "[" + uri.getHost() + "]" : uri.getHost();
        if (uri.getPort() >= 0 && uri.getPort() != 80) {
            host += ':' + uri.getPort();
        }
        var request = "GET " + path + " HTTP/1.1\r\n"
                + "Host: " + host + "\r\n"
                + "Accept: text/event-stream\r\n"
                + "Cache-Control: no-cache\r\n"
                + "Connection: keep-alive\r\n\r\n";
        output.write(request.getBytes(StandardCharsets.US_ASCII));
        output.flush();
    }

    private static String decodeUtf8(byte[] bytes) throws IOException {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException invalid) {
            throw new IOException("SSE fixture received invalid UTF-8", invalid);
        }
    }

    private static String stripOptionalSpace(String value) {
        return value.startsWith(" ") ? value.substring(1) : value;
    }

    private static Duration requirePositive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be greater than zero");
        }
        return value;
    }

    private static int toMillis(Duration timeout) {
        try {
            return (int) Math.max(1L, Math.min(Integer.MAX_VALUE, timeout.toMillis()));
        } catch (ArithmeticException ignored) {
            return Integer.MAX_VALUE;
        }
    }

    private record HttpLine(String value, int wireBytes) {
    }

    private record SseLine(String value, int wireBytes) {
    }

    private static final class EventBuilder {
        private final int maximumBytes;
        private final StringBuilder data = new StringBuilder();
        private final StringBuilder comment = new StringBuilder();
        private String event;
        private String id;
        private Duration retry;
        private int eventBytes;
        private boolean hasData;
        private boolean hasComment;

        private EventBuilder(int maximumBytes) {
            this.maximumBytes = maximumBytes;
        }

        private void accept(String line, int wireBytes) throws IOException {
            if (eventBytes > maximumBytes - wireBytes) {
                throw new IOException("SSE event exceeded fixture maximumEventBytes");
            }
            eventBytes += wireBytes;
            if (line.charAt(0) == ':') {
                if (hasComment) {
                    comment.append('\n');
                }
                comment.append(stripOptionalSpace(line.substring(1)));
                hasComment = true;
                return;
            }
            var separator = line.indexOf(':');
            var field = separator < 0 ? line : line.substring(0, separator);
            var value = separator < 0 ? "" : stripOptionalSpace(line.substring(separator + 1));
            switch (field) {
                case "data" -> {
                    data.append(value).append('\n');
                    hasData = true;
                }
                case "event" -> event = value.isEmpty() ? null : value;
                case "id" -> {
                    if (value.indexOf('\0') < 0) {
                        id = value;
                    }
                }
                case "retry" -> retry = parseRetry(value);
                default -> {
                    // Unknown fields have no fixture-level semantic effect.
                }
            }
        }

        private SseEvent build() {
            if (!hasData && !hasComment && event == null && id == null && retry == null) {
                return null;
            }
            var result = SseEvent.builder();
            if (hasComment) {
                result.comment(comment.toString());
            }
            if (event != null) {
                result.event(event);
            }
            if (id != null) {
                result.id(id);
            }
            if (retry != null) {
                result.retry(retry);
            }
            if (hasData) {
                result.data(data.substring(0, data.length() - 1));
            }
            return result.build();
        }

        private static Duration parseRetry(String value) {
            if (value.isEmpty()) {
                return null;
            }
            for (var index = 0; index < value.length(); index++) {
                if (value.charAt(index) < '0' || value.charAt(index) > '9') {
                    return null;
                }
            }
            try {
                return Duration.ofMillis(Long.parseLong(value));
            } catch (NumberFormatException | ArithmeticException ignored) {
                return null;
            }
        }
    }

    private static final class ChunkedInputStream extends InputStream {
        private final InputStream source;
        private final int maximumTrailerBytes;
        private long remaining;
        private boolean terminal;

        private ChunkedInputStream(InputStream source, int maximumTrailerBytes) {
            this.source = Objects.requireNonNull(source, "source");
            this.maximumTrailerBytes = maximumTrailerBytes;
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
            var read = source.read(target, offset, (int) Math.min(length, remaining));
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
            var line = requiredLine(source, maximumTrailerBytes, "SSE chunk size");
            var separator = line.value().indexOf(';');
            var encodedSize = (separator < 0 ? line.value() : line.value().substring(0, separator)).strip();
            final long size;
            try {
                if (encodedSize.isEmpty() || encodedSize.startsWith("+")) {
                    throw new NumberFormatException("invalid chunk size");
                }
                size = Long.parseLong(encodedSize, 16);
            } catch (NumberFormatException invalid) {
                throw new IOException("chunked SSE response has an invalid chunk size", invalid);
            }
            if (size < 0) {
                throw new IOException("chunked SSE response has a negative chunk size");
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
                throw new IOException("chunked SSE response chunk is missing its CRLF terminator");
            }
        }

        private void consumeTrailers() throws IOException {
            var remainingBytes = maximumTrailerBytes;
            while (true) {
                var trailer = requiredLine(source, remainingBytes, "SSE terminal trailer");
                remainingBytes -= trailer.wireBytes();
                if (trailer.value().isEmpty()) {
                    return;
                }
                if (trailer.value().indexOf(':') <= 0) {
                    throw new IOException("chunked SSE response has an invalid trailer");
                }
            }
        }
    }

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
}

