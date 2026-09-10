package io.wavejava.wave;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.wavejava.wave.api.server.ServerTimeouts;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** Socket-level coverage for transport-triggered request cancellation. */
class RequestCancellationIntegrationTest {
    private static final Duration REQUEST_TIMEOUT = Duration.ofMillis(250);
    private static final Duration TRANSPORT_TIMEOUT = Duration.ofSeconds(5);
    private static final int ASSERTION_TIMEOUT_MILLIS = 5_000;
    private static final int MAX_HEADER_BYTES = 16 * 1024;

    @Test
    void requestDeadlineReturns504AndInterruptsTheActiveHandler() throws Exception {
        var handlerStarted = new CountDownLatch(1);
        var handlerCancelled = new CountDownLatch(1);
        var cancellationObserved = new AtomicBoolean();
        var cancellationReason = new AtomicReference<String>();

        var app = Wave.app().routes(routes -> routes.get("/deadline", (request, response) -> {
            handlerStarted.countDown();
            try {
                new CountDownLatch(1).await();
            } catch (InterruptedException interrupted) {
                var token = request.cancellationToken().orElseThrow();
                cancellationObserved.set(token.isCancelled());
                cancellationReason.set(token.reason().orElseThrow());
                handlerCancelled.countDown();
            }
            response.text("too late");
        })).build();

        try (var server = Wave.server(app).timeouts(deadlineTimeouts()).listen(0).start();
             var socket = new Socket("127.0.0.1", server.port())) {
            socket.setTcpNoDelay(true);
            socket.setSoTimeout(ASSERTION_TIMEOUT_MILLIS);
            writeRequest(socket, "/deadline");

            await(handlerStarted, "deadline handler should start before its deadline");
            await(handlerCancelled, "request deadline should interrupt the active handler");
            assertTrue(cancellationObserved.get(), "handler should observe its cancellation token");
            assertEquals("deadline exceeded", cancellationReason.get());
            assertEquals(504, readResponseStatus(socket.getInputStream()));
        }
    }

    @Test
    void clientDisconnectCancelsTheActiveHandlerWithoutRequiringAResponse() throws Exception {
        var handlerStarted = new CountDownLatch(1);
        var handlerCancelled = new CountDownLatch(1);
        var cancellationObserved = new AtomicBoolean();
        var cancellationReason = new AtomicReference<String>();
        var releaseHandler = new CountDownLatch(1);

        var app = Wave.app().routes(routes -> routes.get("/disconnect", (request, response) -> {
            handlerStarted.countDown();
            try {
                releaseHandler.await();
            } catch (InterruptedException interrupted) {
                var token = request.cancellationToken().orElseThrow();
                cancellationObserved.set(token.isCancelled());
                cancellationReason.set(token.reason().orElseThrow());
                handlerCancelled.countDown();
            }
            response.text("unreachable");
        })).build();

        try (var server = Wave.server(app).timeouts(disconnectTimeouts()).listen(0).start();
             var socket = new Socket("127.0.0.1", server.port())) {
            socket.setTcpNoDelay(true);
            writeRequest(socket, "/disconnect");
            await(handlerStarted, "disconnect handler should start before the client closes");

            socket.close();

            await(handlerCancelled, "client disconnect should interrupt the active handler");
            assertTrue(cancellationObserved.get(), "handler should observe its cancellation token");
            assertEquals("client disconnected", cancellationReason.get());
        } finally {
            releaseHandler.countDown();
        }
    }

    private static ServerTimeouts deadlineTimeouts() {
        return timeouts(REQUEST_TIMEOUT);
    }

    private static ServerTimeouts disconnectTimeouts() {
        // Keep the runtime deadline outside the assertion window so only socket closure can win.
        return timeouts(Duration.ofSeconds(30));
    }

    private static ServerTimeouts timeouts(Duration requestTimeout) {
        return ServerTimeouts.builder()
                .requestTimeout(requestTimeout)
                .readTimeout(TRANSPORT_TIMEOUT)
                .writeTimeout(TRANSPORT_TIMEOUT)
                .idleTimeout(TRANSPORT_TIMEOUT)
                .shutdownTimeout(TRANSPORT_TIMEOUT)
                .build();
    }

    private static void writeRequest(Socket socket, String path) throws IOException {
        var request = "GET " + path + " HTTP/1.1\r\n"
                + "Host: localhost\r\n"
                + "Connection: close\r\n"
                + "\r\n";
        var output = socket.getOutputStream();
        output.write(request.getBytes(StandardCharsets.US_ASCII));
        output.flush();
    }

    private static void await(CountDownLatch latch, String message) throws InterruptedException {
        assertTrue(latch.await(ASSERTION_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS), message);
    }

    private static int readResponseStatus(InputStream input) throws IOException {
        var headerBlock = readHeaderBlock(input);
        var firstLineEnd = headerBlock.indexOf("\r\n");
        var statusLine = firstLineEnd < 0 ? headerBlock : headerBlock.substring(0, firstLineEnd);
        var parts = statusLine.split(" ", 3);
        if (parts.length < 2) {
            throw new IOException("invalid HTTP response status line: " + statusLine);
        }
        return Integer.parseInt(parts[1]);
    }

    private static String readHeaderBlock(InputStream input) throws IOException {
        var bytes = new ByteArrayOutputStream();
        var delimiterState = 0;
        for (int count = 0; count < MAX_HEADER_BYTES; count++) {
            var next = input.read();
            if (next < 0) {
                throw new EOFException("connection closed before response headers completed");
            }
            bytes.write(next);
            delimiterState = switch (delimiterState) {
                case 0 -> next == '\r' ? 1 : 0;
                case 1 -> next == '\n' ? 2 : next == '\r' ? 1 : 0;
                case 2 -> next == '\r' ? 3 : 0;
                case 3 -> next == '\n' ? 4 : next == '\r' ? 1 : 0;
                default -> throw new AssertionError("header delimiter state out of range");
            };
            if (delimiterState == 4) {
                var wire = bytes.toString(StandardCharsets.US_ASCII);
                return wire.substring(0, wire.length() - 4);
            }
        }
        throw new IOException("response headers exceeded " + MAX_HEADER_BYTES + " bytes");
    }
}
