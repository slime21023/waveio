package io.wavejava.wave;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** Socket-level HTTP/1.1 pipelining invariants that cannot be asserted through JDK HttpClient. */
class Http1PipeliningIntegrationTest {
    private static final int ASSERTION_TIMEOUT_MILLIS = 5_000;
    private static final int NO_PREDECESSOR_RESPONSE_TIMEOUT_MILLIS = 500;
    private static final int MAX_HEADER_BYTES = 16 * 1024;

    @Test
    void dispatchesPipelinedHandlersOutsideTheConnectionEventLoop() throws Exception {
        var slowStarted = new CountDownLatch(1);
        var fastStarted = new CountDownLatch(1);
        var slowFinished = new CountDownLatch(1);
        var releaseSlow = new CountDownLatch(1);
        var slowThread = new AtomicReference<Thread>();
        var fastThread = new AtomicReference<Thread>();

        var app = Wave.app().routes(routes -> {
            routes.get("/slow", (request, response) -> {
                slowThread.set(Thread.currentThread());
                slowStarted.countDown();
                await(releaseSlow, "test did not release the first pipelined handler");
                response.text("slow");
                slowFinished.countDown();
            });
            routes.get("/fast", (request, response) -> {
                fastThread.set(Thread.currentThread());
                fastStarted.countDown();
                response.text("fast");
            });
        }).build();

        try (var server = Wave.server(app).listen(0).start();
             var socket = new Socket("127.0.0.1", server.port())) {
            socket.setTcpNoDelay(true);
            writePipelinedRequests(socket);

            await(slowStarted, "first pipelined handler should start");
            await(fastStarted, "second pipelined handler should run while the first remains blocked");
            assertRunsOutsideNettyEventLoop(slowThread.get());
            assertRunsOutsideNettyEventLoop(fastThread.get());

            releaseSlow.countDown();
            await(slowFinished, "first pipelined handler should finish after release");
        } finally {
            releaseSlow.countDown();
        }
    }

    @Test
    void keepsPipelinedResponsesOrdered() throws Exception {
        var slowStarted = new CountDownLatch(1);
        var fastStarted = new CountDownLatch(1);
        var releaseSlow = new CountDownLatch(1);

        var app = Wave.app().routes(routes -> {
            routes.get("/slow", (request, response) -> {
                slowStarted.countDown();
                await(releaseSlow, "test did not release the first pipelined handler");
                response.text("slow");
            });
            routes.get("/fast", (request, response) -> {
                fastStarted.countDown();
                response.text("fast");
            });
        }).build();

        try (var server = Wave.server(app).listen(0).start();
             var socket = new Socket("127.0.0.1", server.port())) {
            socket.setTcpNoDelay(true);
            var input = socket.getInputStream();

            writePipelinedRequests(socket);

            await(slowStarted, "first pipelined handler should start");
            await(fastStarted, "second pipelined handler should run while the first remains blocked");

            try {
                socket.setSoTimeout(NO_PREDECESSOR_RESPONSE_TIMEOUT_MILLIS);
                assertThrows(
                        SocketTimeoutException.class,
                        input::read,
                        "the second response must not overtake the blocked first response"
                );
            } finally {
                // Keep the server teardown bounded even when this assertion exposes an ordering bug.
                releaseSlow.countDown();
            }
            socket.setSoTimeout(ASSERTION_TIMEOUT_MILLIS);
            var first = readResponse(input);
            var second = readResponse(input);

            assertEquals(200, first.status());
            assertEquals("slow", first.body());
            assertEquals(200, second.status());
            assertEquals("fast", second.body());
        } finally {
            releaseSlow.countDown();
        }
    }

    private static void writePipelinedRequests(Socket socket) throws IOException {
        var output = socket.getOutputStream();
        output.write((request("/slow") + request("/fast")).getBytes(StandardCharsets.US_ASCII));
        output.flush();
    }

    private static String request(String path) {
        return "GET " + path + " HTTP/1.1\r\n"
                + "Host: localhost\r\n"
                + "Connection: keep-alive\r\n"
                + "\r\n";
    }

    private static void assertRunsOutsideNettyEventLoop(Thread thread) {
        assertTrue(thread.isVirtual(), "application handlers must execute on virtual threads");
        assertFalse(
                thread.getName().startsWith("nioEventLoopGroup-"),
                "application handlers must not execute on a Netty event-loop thread"
        );
    }

    private static void await(CountDownLatch latch, String message) throws InterruptedException {
        assertTrue(latch.await(ASSERTION_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS), message);
    }

    private static RawResponse readResponse(InputStream input) throws IOException {
        var headerBlock = readHeaderBlock(input);
        var lines = headerBlock.split("\\r\\n");
        if (lines.length == 0) {
            throw new IOException("missing HTTP response status line");
        }

        var statusParts = lines[0].split(" ", 3);
        if (statusParts.length < 2) {
            throw new IOException("invalid HTTP response status line: " + lines[0]);
        }

        var headers = new LinkedHashMap<String, String>();
        for (int index = 1; index < lines.length; index++) {
            var separator = lines[index].indexOf(':');
            if (separator <= 0) {
                throw new IOException("invalid HTTP response header: " + lines[index]);
            }
            var name = lines[index].substring(0, separator).toLowerCase(Locale.ROOT);
            var value = lines[index].substring(separator + 1).trim();
            headers.put(name, value);
        }

        var contentLength = headers.get("content-length");
        if (contentLength == null) {
            throw new IOException("response omitted Content-Length");
        }
        var expectedLength = Integer.parseInt(contentLength);
        var body = input.readNBytes(expectedLength);
        if (body.length != expectedLength) {
            throw new EOFException("response closed before its complete body was received");
        }
        return new RawResponse(Integer.parseInt(statusParts[1]), new String(body, StandardCharsets.UTF_8));
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

    private record RawResponse(int status, String body) {
    }
}
