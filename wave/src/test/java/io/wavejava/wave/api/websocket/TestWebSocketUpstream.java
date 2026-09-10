package io.wavejava.wave.api.websocket;

import io.wavejava.wave.api.http.Headers;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Test-only raw direct-{@code ws} upstream for verifying the owned client transport.
 *
 * <p>The acceptor performs only the bounded HTTP handshake. Frame I/O is explicitly driven by
 * the test thread, so it can model slow consumers and protocol-invalid server frames without a
 * hidden reader or unbounded queue.</p>
 */
final class TestWebSocketUpstream implements AutoCloseable {
    private static final int TIMEOUT_MILLIS = 5_000;
    private static final int MAXIMUM_HEAD_BYTES = 16 * 1024;
    private static final int MAXIMUM_FRAME_BYTES = 1024 * 1024;
    private static final String WEBSOCKET_MAGIC = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    private static final AtomicInteger MASK_SEED = new AtomicInteger();

    private final ServerSocket listener;
    private final CountDownLatch handshakeComplete = new CountDownLatch(1);
    private final AtomicReference<Socket> connection = new AtomicReference<>();
    private final AtomicReference<Throwable> failure = new AtomicReference<>();

    private TestWebSocketUpstream() throws IOException {
        listener = new ServerSocket(0);
        Thread.ofVirtual().name("wave-ws-test-upstream").start(this::acceptOnce);
    }

    /** Starts one bounded loopback WebSocket upstream. */
    static TestWebSocketUpstream start() throws IOException {
        return new TestWebSocketUpstream();
    }

    /** Returns the direct loopback URI accepted by this fixture. */
    URI uri() {
        return URI.create("ws://127.0.0.1:" + listener.getLocalPort() + "/chat");
    }

    /** Waits for the client request and verified 101 response write. */
    void awaitHandshake() throws InterruptedException {
        if (!handshakeComplete.await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
            throw new AssertionError("client did not complete the raw WebSocket handshake");
        }
        assertHealthy();
    }

    /** Writes one server wire frame. A server frame is normally unmasked; invalid mask values are allowed for tests. */
    void send(TestWebSocketClient.WireFrame frame) throws IOException {
        writeFrame(output(), Objects.requireNonNull(frame, "frame"));
    }

    /** Reads exactly one bounded client frame. Client frames should be masked by RFC 6455. */
    TestWebSocketClient.WireFrame readFrame() throws IOException {
        return readFrame(input());
    }

    /** Writes a normal server text frame. */
    void sendText(String text) throws IOException {
        send(new TestWebSocketClient.WireFrame(
                true, false, 0, 1, Objects.requireNonNull(text, "text").getBytes(StandardCharsets.UTF_8)));
    }

    /** Writes a normal server Ping frame. */
    void sendPing(byte[] payload) throws IOException {
        send(new TestWebSocketClient.WireFrame(true, false, 0, 9, payload));
    }

    /** Writes a normal server close frame. */
    void sendClose(int code, String reason) throws IOException {
        var close = WebSocketMessage.close(code, reason);
        var payload = new byte[close.size() + 2];
        payload[0] = (byte) (code >>> 8);
        payload[1] = (byte) code;
        System.arraycopy(close.bytes(), 0, payload, 2, close.size());
        send(new TestWebSocketClient.WireFrame(true, false, 0, 8, payload));
    }

    /** Fails the test with the acceptor failure, if any. */
    void assertHealthy() {
        var observed = failure.get();
        if (observed != null) {
            throw new AssertionError("raw WebSocket upstream failed", observed);
        }
    }

    @Override
    public void close() {
        try {
            listener.close();
        } catch (IOException ignored) {
            // Fixture close is idempotent.
        }
        var accepted = connection.get();
        if (accepted != null) {
            try {
                accepted.close();
            } catch (IOException ignored) {
                // Fixture close is idempotent.
            }
        }
    }

    private void acceptOnce() {
        try {
            var accepted = listener.accept();
            accepted.setSoTimeout(TIMEOUT_MILLIS);
            accepted.setTcpNoDelay(true);
            var headers = readHeaders(accepted.getInputStream());
            var key = headers.first("Sec-WebSocket-Key").orElseThrow(
                    () -> new IOException("client upgrade request did not contain Sec-WebSocket-Key"));
            writeHandshake(accepted.getOutputStream(), expectedAccept(key));
            connection.set(accepted);
            handshakeComplete.countDown();
        } catch (Throwable unexpected) {
            if (!listener.isClosed()) {
                failure.compareAndSet(null, unexpected);
            }
            handshakeComplete.countDown();
        }
    }

    private InputStream input() throws IOException {
        return requireConnection().getInputStream();
    }

    private OutputStream output() throws IOException {
        return requireConnection().getOutputStream();
    }

    private Socket requireConnection() throws IOException {
        var accepted = connection.get();
        if (accepted == null || accepted.isClosed()) {
            throw new IOException("raw WebSocket upstream has no open client connection");
        }
        return accepted;
    }

    private static Headers readHeaders(InputStream input) throws IOException {
        var bytes = new ByteArrayOutputStream();
        var delimiter = 0;
        while (bytes.size() < MAXIMUM_HEAD_BYTES) {
            var next = input.read();
            if (next < 0) {
                throw new IOException("client closed before the WebSocket handshake");
            }
            bytes.write(next);
            delimiter = switch (delimiter) {
                case 0 -> next == '\r' ? 1 : 0;
                case 1 -> next == '\n' ? 2 : next == '\r' ? 1 : 0;
                case 2 -> next == '\r' ? 3 : 0;
                case 3 -> next == '\n' ? 4 : next == '\r' ? 1 : 0;
                default -> throw new AssertionError("invalid HTTP delimiter state");
            };
            if (delimiter == 4) {
                var lines = bytes.toString(StandardCharsets.ISO_8859_1).split("\\r\\n");
                if (lines.length == 0 || !lines[0].startsWith("GET ")) {
                    throw new IOException("invalid WebSocket client request line");
                }
                var headers = Headers.builder();
                for (var index = 1; index < lines.length; index++) {
                    var line = lines[index];
                    if (line.isEmpty()) {
                        continue;
                    }
                    var colon = line.indexOf(':');
                    if (colon <= 0) {
                        throw new IOException("invalid WebSocket client header");
                    }
                    headers.add(line.substring(0, colon), line.substring(colon + 1).stripLeading());
                }
                return headers.build();
            }
        }
        throw new IOException("client handshake exceeded fixture head bound");
    }

    private static void writeHandshake(OutputStream output, String accept) throws IOException {
        var response = "HTTP/1.1 101 Switching Protocols\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Accept: " + accept + "\r\n\r\n";
        output.write(response.getBytes(StandardCharsets.ISO_8859_1));
        output.flush();
    }

    private static void writeFrame(OutputStream output, TestWebSocketClient.WireFrame frame) throws IOException {
        var payload = frame.payload();
        if (payload.length > MAXIMUM_FRAME_BYTES) {
            throw new IllegalArgumentException("fixture frame exceeds maximumFrameBytes");
        }
        output.write((frame.fin() ? 0x80 : 0) | ((frame.rsv() & 0x7) << 4) | (frame.opcode() & 0xf));
        var masked = frame.masked();
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

    private static TestWebSocketClient.WireFrame readFrame(InputStream input) throws IOException {
        var first = required(input, "frame header");
        var second = required(input, "frame length");
        var fin = (first & 0x80) != 0;
        var rsv = (first >>> 4) & 0x7;
        var opcode = first & 0xf;
        var masked = (second & 0x80) != 0;
        long length = second & 0x7f;
        if (length == 126) {
            length = ((long) required(input, "extended frame length") << 8) | required(input, "extended frame length");
        } else if (length == 127) {
            length = 0;
            for (var index = 0; index < 8; index++) {
                length = (length << 8) | required(input, "extended frame length");
            }
        }
        if (length < 0 || length > MAXIMUM_FRAME_BYTES || length > Integer.MAX_VALUE) {
            throw new IOException("client frame exceeds fixture maximumFrameBytes: " + length);
        }
        var mask = masked ? readFully(input, 4, "frame mask") : null;
        var payload = readFully(input, (int) length, "frame payload");
        if (mask != null) {
            for (var index = 0; index < payload.length; index++) {
                payload[index] ^= mask[index & 3];
            }
        }
        return new TestWebSocketClient.WireFrame(fin, masked, rsv, opcode, payload);
    }

    private static void writeLength(OutputStream output, int length, boolean masked) throws IOException {
        var maskBit = masked ? 0x80 : 0;
        if (length <= 125) {
            output.write(maskBit | length);
        } else if (length <= 0xffff) {
            output.write(maskBit | 126);
            output.write((length >>> 8) & 0xff);
            output.write(length & 0xff);
        } else {
            output.write(maskBit | 127);
            for (var shift = 56; shift >= 0; shift -= 8) {
                output.write((length >>> shift) & 0xff);
            }
        }
    }

    private static byte[] readFully(InputStream input, int length, String label) throws IOException {
        var result = new byte[length];
        var offset = 0;
        while (offset < result.length) {
            var read = input.read(result, offset, result.length - offset);
            if (read < 0) {
                throw new IOException("connection ended while reading " + label);
            }
            offset += read;
        }
        return result;
    }

    private static int required(InputStream input, String label) throws IOException {
        var value = input.read();
        if (value < 0) {
            throw new IOException("connection ended while reading " + label);
        }
        return value;
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
            throw new IOException("SHA-1 is unavailable", unavailable);
        }
    }
}
