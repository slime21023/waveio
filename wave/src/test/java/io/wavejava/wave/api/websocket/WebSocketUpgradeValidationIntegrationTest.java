package io.wavejava.wave.api.websocket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.wavejava.wave.Wave;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;

/** Socket-level validation for ordered and unambiguous WebSocket upgrades. */
class WebSocketUpgradeValidationIntegrationTest {
    private static final int SOCKET_TIMEOUT_MILLIS = 5_000;

    @Test
    void malformedUpgradeReceivesAnHttpErrorBeforeAnySessionExists() throws Exception {
        var endpointStarted = new CountDownLatch(1);
        var app = Wave.app().routes(routes -> routes.get("/chat", io.wavejava.wave.api.websocket.WebSocket.handler( session -> endpointStarted.countDown()))).build();

        try (var server = Wave.server(app).listen(0).start();
             var socket = socket(server.port())) {
            write(socket, "GET /chat HTTP/1.1\r\n"
                    + "Host: localhost\r\n"
                    + "Connection: Upgrade\r\n"
                    + "Upgrade: websocket\r\n"
                    + "Sec-WebSocket-Version: 13\r\n"
                    + "\r\n");

            assertEquals(400, readStatus(socket.getInputStream()));
            assertEquals(1L, endpointStarted.getCount(), "malformed handshake started an endpoint");
        }
    }

    @Test
    void headFallbackCannotCommitAWebSocketUpgrade() throws Exception {
        var app = Wave.app().routes(routes -> routes.get("/chat", io.wavejava.wave.api.websocket.WebSocket.handler( session -> { }))).build();

        try (var server = Wave.server(app).listen(0).start();
             var socket = socket(server.port())) {
            write(socket, handshakeRequest("HEAD", "/chat"));
            assertEquals(400, readStatus(socket.getInputStream()));
        }
    }

    @Test
    void pipelinedHttpInputAfterAnUpgradeRequestIsRejectedBeforeCodecTransition() throws Exception {
        var endpointStarted = new CountDownLatch(1);
        var app = Wave.app().routes(routes -> {
            routes.get("/chat", io.wavejava.wave.api.websocket.WebSocket.handler( session -> endpointStarted.countDown()));
            routes.get("/after", (request, response) -> response.text("must not become a WebSocket frame"));
        }).build();

        try (var server = Wave.server(app).listen(0).start();
             var socket = socket(server.port())) {
            write(socket, handshakeRequest("GET", "/chat")
                    + "GET /after HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");

            assertEquals(400, readStatus(socket.getInputStream()));
            assertEquals(1L, endpointStarted.getCount(),
                    "ambiguous pipelined input must not create a WebSocket session");
        }
    }

    @Test
    void streamingHttpRequestModeRejectsAnUpgradeDeterministically() throws Exception {
        var endpointStarted = new CountDownLatch(1);
        var app = Wave.app().routes(routes -> routes.get("/chat", io.wavejava.wave.api.websocket.WebSocket.handler( session -> endpointStarted.countDown()))).build();

        try (var server = Wave.server(app).requestStreaming(true).listen(0).start();
             var socket = socket(server.port())) {
            write(socket, handshakeRequest("GET", "/chat"));
            assertEquals(400, readStatus(socket.getInputStream()));
            assertEquals(1L, endpointStarted.getCount(),
                    "streaming HTTP mode must not create a WebSocket session");
        }
    }

    private static Socket socket(int port) throws IOException {
        var socket = new Socket("127.0.0.1", port);
        socket.setSoTimeout(SOCKET_TIMEOUT_MILLIS);
        return socket;
    }

    private static String handshakeRequest(String method, String path) {
        return method + " " + path + " HTTP/1.1\r\n"
                + "Host: localhost\r\n"
                + "Connection: Upgrade\r\n"
                + "Upgrade: websocket\r\n"
                + "Sec-WebSocket-Version: 13\r\n"
                + "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n"
                + "\r\n";
    }

    private static void write(Socket socket, String request) throws IOException {
        socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
        socket.getOutputStream().flush();
    }

    private static int readStatus(InputStream input) throws IOException {
        var header = readHeaderBlock(input);
        var firstLine = header.split("\\r\\n", 2)[0];
        var parts = firstLine.split(" ", 3);
        if (parts.length < 2) {
            throw new IOException("invalid HTTP status line: " + firstLine);
        }
        return Integer.parseInt(parts[1]);
    }

    private static String readHeaderBlock(InputStream input) throws IOException {
        var bytes = new ByteArrayOutputStream();
        var delimiter = 0;
        for (var count = 0; count < 16 * 1024; count++) {
            var next = input.read();
            if (next < 0) {
                throw new EOFException("connection closed before HTTP headers");
            }
            bytes.write(next);
            delimiter = switch (delimiter) {
                case 0 -> next == '\r' ? 1 : 0;
                case 1 -> next == '\n' ? 2 : next == '\r' ? 1 : 0;
                case 2 -> next == '\r' ? 3 : 0;
                case 3 -> next == '\n' ? 4 : next == '\r' ? 1 : 0;
                default -> throw new AssertionError("invalid delimiter state");
            };
            if (delimiter == 4) {
                var wire = bytes.toString(StandardCharsets.US_ASCII);
                return wire.substring(0, wire.length() - 4);
            }
        }
        throw new IOException("HTTP response headers exceeded test budget");
    }
}
