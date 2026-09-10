package io.wavejava.wave.api.websocket;

import io.wavejava.wave.api.http.Headers;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Base64;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Test-only raw direct-{@code ws} peer with bounded handshake/frame reads and no background work.
 *
 * <p>It deliberately exposes invalid masking, RSV, opcode, and fragmentation values so transport
 * tests can verify the server without exporting wire-control APIs. Use {@link #finishOutput()} to
 * produce TCP FIN and {@link #abort()} to produce TCP RST; ordinary {@link #close()} makes no such
 * protocol guarantee.</p>
 */
final class TestWebSocketClient implements AutoCloseable {
    private static final String WEBSOCKET_MAGIC = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    private static final AtomicInteger MASK_SEED = new AtomicInteger();

    private final Socket socket;
    private final Options options;
    private final Handshake handshake;

    private TestWebSocketClient(Socket socket, Options options, Handshake handshake) {
        this.socket = socket;
        this.options = options;
        this.handshake = handshake;
    }

    /** Opens a direct {@code ws} connection and requires a verified {@code 101} handshake. */
    static TestWebSocketClient connect(URI uri) throws IOException {
        return connect(uri, Options.defaults());
    }

    /** Opens a direct {@code ws} connection using explicitly finite fixture limits. */
    static TestWebSocketClient connect(URI uri, Options options) throws IOException {
        var target = validateUri(uri);
        var configured = Objects.requireNonNull(options, "options");
        var socket = new Socket();
        try {
            socket.setTcpNoDelay(true);
            socket.connect(new InetSocketAddress(target.getHost(), effectivePort(target)), toMillis(configured.connectTimeout()));
            socket.setSoTimeout(toMillis(configured.readTimeout()));
            var key = generatedKey();
            writeHandshake(socket.getOutputStream(), target, key);
            var handshake = readHandshake(socket.getInputStream(), configured.maximumHandshakeBytes());
            if (handshake.status() != 101) {
                throw new IOException("expected WebSocket 101 but received HTTP " + handshake.status());
            }
            var accept = handshake.headers().first("Sec-WebSocket-Accept").orElse("");
            if (!expectedAccept(key).equals(accept)) {
                throw new IOException("WebSocket handshake accepted an invalid Sec-WebSocket-Accept value");
            }
            return new TestWebSocketClient(socket, configured, handshake);
        } catch (Throwable failure) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // Preserve the primary fixture failure.
            }
            if (failure instanceof IOException io) {
                throw io;
            }
            throw new IOException("raw WebSocket fixture could not establish a handshake", failure);
        }
    }

    /** Returns the verified HTTP upgrade response snapshot. */
    Handshake handshake() {
        return handshake;
    }

    /** Writes exactly one client wire frame. The caller controls mask/RSV/opcode for negative tests. */
    void send(WireFrame frame) throws IOException {
        var outbound = Objects.requireNonNull(frame, "frame");
        if (outbound.payload().length > options.maximumFrameBytes()) {
            throw new IllegalArgumentException("fixture frame exceeds maximumFrameBytes");
        }
        var output = socket.getOutputStream();
        output.write((outbound.fin() ? 0x80 : 0) | ((outbound.rsv() & 0x7) << 4) | (outbound.opcode() & 0xf));
        var payload = outbound.payload();
        var masked = outbound.masked();
        writeLength(output, payload.length, masked);
        if (masked) {
            var mask = nextMask();
            output.write(mask);
            for (var index = 0; index < payload.length; index++) {
                output.write(payload[index] ^ mask[index & 3]);
            }
        } else {
            output.write(payload);
        }
        output.flush();
    }

    /** Reads exactly one bounded server wire frame. There is intentionally no background reader. */
    WireFrame readFrame() throws IOException {
        var input = socket.getInputStream();
        var first = required(input, "frame header");
        var second = required(input, "frame length");
        var fin = (first & 0x80) != 0;
        var rsv = (first >>> 4) & 0x7;
        var opcode = first & 0xf;
        var masked = (second & 0x80) != 0;
        long length = second & 0x7f;
        if (length == 126) {
            length = ((long) required(input, "extended length") << 8) | required(input, "extended length");
        } else if (length == 127) {
            length = 0;
            for (var index = 0; index < 8; index++) {
                length = (length << 8) | required(input, "extended length");
            }
        }
        if (length < 0 || length > options.maximumFrameBytes() || length > Integer.MAX_VALUE) {
            throw new IOException("WebSocket frame exceeds fixture maximumFrameBytes: " + length);
        }
        byte[] mask = null;
        if (masked) {
            mask = readFully(input, 4, "frame mask");
        }
        var payload = readFully(input, (int) length, "frame payload");
        if (mask != null) {
            for (var index = 0; index < payload.length; index++) {
                payload[index] ^= mask[index & 3];
            }
        }
        return new WireFrame(fin, masked, rsv, opcode, payload);
    }

    /** Sends a normal client masked text frame. */
    void sendText(String text) throws IOException {
        send(WireFrame.text(text));
    }

    /** Sends a normal client masked binary frame. */
    void sendBinary(byte[] bytes) throws IOException {
        send(WireFrame.binary(bytes));
    }

    /** Sends a normal client masked Ping frame. */
    void sendPing(byte[] bytes) throws IOException {
        send(WireFrame.ping(bytes));
    }

    /** Sends a normal client masked close frame. */
    void sendClose(int code, String reason) throws IOException {
        send(WireFrame.close(code, reason));
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

    /** Closes the fixture socket without claiming a WebSocket close, TCP FIN, or TCP RST semantic. */
    @Override
    public void close() throws IOException {
        socket.close();
    }

    /** Immutable bounded fixture options. */
    record Options(Duration connectTimeout, Duration readTimeout, int maximumHandshakeBytes, int maximumFrameBytes) {
        Options {
            connectTimeout = requirePositive(connectTimeout, "connectTimeout");
            readTimeout = requirePositive(readTimeout, "readTimeout");
            if (maximumHandshakeBytes <= 0 || maximumFrameBytes <= 0) {
                throw new IllegalArgumentException("fixture byte limits must be greater than zero");
            }
        }

        static Options defaults() {
            return new Options(Duration.ofSeconds(5), Duration.ofSeconds(5), 16 * 1024, 1024 * 1024);
        }
    }

    /** Raw WebSocket frame; this value intentionally permits protocol-invalid combinations for tests. */
    record WireFrame(boolean fin, boolean masked, int rsv, int opcode, byte[] payload) {
        WireFrame {
            if (rsv < 0 || rsv > 7 || opcode < 0 || opcode > 15) {
                throw new IllegalArgumentException("invalid raw WebSocket RSV or opcode");
            }
            payload = Objects.requireNonNull(payload, "payload").clone();
        }

        @Override
        public byte[] payload() {
            return payload.clone();
        }

        static WireFrame text(String text) {
            return new WireFrame(true, true, 0, 1, Objects.requireNonNull(text, "text").getBytes(StandardCharsets.UTF_8));
        }

        static WireFrame binary(byte[] bytes) {
            return new WireFrame(true, true, 0, 2, bytes);
        }

        static WireFrame ping(byte[] bytes) {
            return new WireFrame(true, true, 0, 9, bytes);
        }

        static WireFrame close(int code, String reason) {
            var close = WebSocketMessage.close(code, reason);
            var payload = new byte[close.size() + 2];
            payload[0] = (byte) (code >>> 8);
            payload[1] = (byte) code;
            System.arraycopy(close.bytes(), 0, payload, 2, close.size());
            return new WireFrame(true, true, 0, 8, payload);
        }
    }

    /** Bounded HTTP upgrade response snapshot. */
    record Handshake(int status, Headers headers) {
        Handshake {
            if (status < 100 || status > 999) {
                throw new IllegalArgumentException("invalid HTTP status");
            }
            headers = Objects.requireNonNull(headers, "headers");
        }
    }

    private static URI validateUri(URI uri) {
        Objects.requireNonNull(uri, "uri");
        if (!uri.isAbsolute() || uri.getHost() == null || !"ws".equalsIgnoreCase(uri.getScheme())
                || uri.getUserInfo() != null || uri.getRawFragment() != null || uri.getPort() > 65_535) {
            throw new IllegalArgumentException("fixture target must be an absolute direct ws URI: " + uri);
        }
        return uri;
    }

    private static int effectivePort(URI uri) {
        return uri.getPort() < 0 ? 80 : uri.getPort();
    }

    private static void writeHandshake(OutputStream output, URI uri, String key) throws IOException {
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
                + "Connection: Upgrade\r\n"
                + "Upgrade: websocket\r\n"
                + "Sec-WebSocket-Version: 13\r\n"
                + "Sec-WebSocket-Key: " + key + "\r\n\r\n";
        output.write(request.getBytes(StandardCharsets.US_ASCII));
        output.flush();
    }

    private static Handshake readHandshake(InputStream input, int maximumBytes) throws IOException {
        var bytes = new ByteArrayOutputStream();
        var delimiter = 0;
        while (bytes.size() < maximumBytes) {
            var next = input.read();
            if (next < 0) {
                throw new IOException("connection closed before WebSocket upgrade response");
            }
            bytes.write(next);
            delimiter = switch (delimiter) {
                case 0 -> next == '\r' ? 1 : 0;
                case 1 -> next == '\n' ? 2 : next == '\r' ? 1 : 0;
                case 2 -> next == '\r' ? 3 : 0;
                case 3 -> next == '\n' ? 4 : next == '\r' ? 1 : 0;
                default -> throw new AssertionError("invalid header delimiter state");
            };
            if (delimiter == 4) {
                break;
            }
        }
        if (delimiter != 4) {
            throw new IOException("WebSocket upgrade response exceeded fixture maximumHandshakeBytes");
        }
        var lines = bytes.toString(StandardCharsets.ISO_8859_1).split("\\r\\n");
        if (lines.length == 0) {
            throw new IOException("missing HTTP status line");
        }
        var statusParts = lines[0].split(" ", 3);
        if (statusParts.length < 2 || !statusParts[0].startsWith("HTTP/")) {
            throw new IOException("invalid HTTP status line: " + lines[0]);
        }
        final int status;
        try {
            status = Integer.parseInt(statusParts[1]);
        } catch (NumberFormatException invalid) {
            throw new IOException("invalid HTTP status line: " + lines[0], invalid);
        }
        var headers = Headers.builder();
        for (var index = 1; index < lines.length; index++) {
            var line = lines[index];
            if (line.isEmpty()) {
                continue;
            }
            var separator = line.indexOf(':');
            if (separator <= 0) {
                throw new IOException("invalid HTTP header line");
            }
            headers.add(line.substring(0, separator), line.substring(separator + 1).stripLeading());
        }
        return new Handshake(status, headers.build());
    }

    private static void writeLength(OutputStream output, int length, boolean masked) throws IOException {
        var maskBit = masked ? 0x80 : 0;
        if (length <= 125) {
            output.write(maskBit | length);
            return;
        }
        if (length <= 0xffff) {
            output.write(maskBit | 126);
            output.write((length >>> 8) & 0xff);
            output.write(length & 0xff);
            return;
        }
        output.write(maskBit | 127);
        for (var shift = 56; shift >= 0; shift -= 8) {
            output.write((length >>> shift) & 0xff);
        }
    }

    private static int required(InputStream input, String what) throws IOException {
        var value = input.read();
        if (value < 0) {
            throw new IOException("connection ended while reading " + what);
        }
        return value;
    }

    private static byte[] readFully(InputStream input, int length, String what) throws IOException {
        var result = new byte[length];
        var offset = 0;
        while (offset < result.length) {
            var read = input.read(result, offset, result.length - offset);
            if (read < 0) {
                throw new IOException("connection ended while reading " + what);
            }
            offset += read;
        }
        return result;
    }

    private static String generatedKey() {
        var seed = MASK_SEED.incrementAndGet();
        var bytes = new byte[16];
        for (var index = 0; index < bytes.length; index++) {
            bytes[index] = (byte) (seed + index * 17);
        }
        return Base64.getEncoder().encodeToString(bytes);
    }

    private static byte[] nextMask() {
        var seed = MASK_SEED.incrementAndGet();
        return new byte[] {(byte) seed, (byte) (seed >>> 8), (byte) 0xa5, (byte) 0x5a};
    }

    private static String expectedAccept(String key) throws IOException {
        try {
            return Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1")
                    .digest((key + WEBSOCKET_MAGIC).getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IOException("SHA-1 is unavailable for WebSocket fixture handshake verification", unavailable);
        }
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
}
