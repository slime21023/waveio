package io.wavejava.wave;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.wavejava.wave.api.server.ServerLimits;
import java.io.IOException;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/** Socket-level coverage for server-wide and per-connection resource budgets. */
class ServerLimitsIntegrationTest {
    private static final int ASSERTION_TIMEOUT_MILLIS = 5_000;

    @Test
    void closesAConnectionThatExceedsTheGlobalConnectionBudget() throws Exception {
        var firstHandlerStarted = new CountDownLatch(1);
        var releaseFirstHandler = new CountDownLatch(1);
        var rejectedHandlerInvoked = new AtomicBoolean();
        var app = Wave.app().routes(routes -> {
            routes.get("/hold", (request, response) -> {
                firstHandlerStarted.countDown();
                await(releaseFirstHandler, "test did not release the admitted connection");
                response.text("first");
            });
            routes.get("/second", (request, response) -> {
                rejectedHandlerInvoked.set(true);
                response.text("unreachable");
            });
        }).build();
        var limits = ServerLimits.defaults().toBuilder().maximumConnections(1).build();

        try (var server = Wave.server(app).limits(limits).listen(0).start();
             var first = new Socket("127.0.0.1", server.port())) {
            first.setTcpNoDelay(true);
            writeRequest(first, "/hold");
            await(firstHandlerStarted, "the first connection should be admitted before opening the second");

            try (var second = new Socket("127.0.0.1", server.port())) {
                second.setTcpNoDelay(true);
                assertNoUsableResponse(second, "/second", "a connection over the configured cap must close");
            }

            assertFalse(rejectedHandlerInvoked.get(), "the over-cap connection must not reach the application");
        } finally {
            releaseFirstHandler.countDown();
        }
    }

    @Test
    void closesConnectionInsteadOfBufferingAnOversizedOutOfOrderResponse() throws Exception {
        var slowHandlerStarted = new CountDownLatch(1);
        var oversizedHandlerCompleted = new CountDownLatch(1);
        var releaseSlowHandler = new CountDownLatch(1);
        var oversizedPayload = "x".repeat(4 * 1024);
        var app = Wave.app().routes(routes -> {
            routes.get("/slow", (request, response) -> {
                slowHandlerStarted.countDown();
                await(releaseSlowHandler, "test did not release the first pipelined handler");
                response.text("slow");
            });
            routes.get("/oversized", (request, response) -> {
                response.text(oversizedPayload);
                oversizedHandlerCompleted.countDown();
            });
        }).build();
        var limits = ServerLimits.defaults().toBuilder()
                .maximumPendingResponseBytesPerConnection(256)
                .build();

        try (var server = Wave.server(app).limits(limits).listen(0).start();
             var socket = new Socket("127.0.0.1", server.port())) {
            socket.setTcpNoDelay(true);
            writePipelinedRequests(socket, "/slow", "/oversized");

            await(slowHandlerStarted, "the delayed predecessor should start");
            await(oversizedHandlerCompleted, "the successor should prepare its oversized response");
            assertConnectionClosed(
                    socket,
                    "an oversized response queued behind a delayed predecessor must abort the connection");
        } finally {
            releaseSlowHandler.countDown();
        }
    }

    private static void writePipelinedRequests(Socket socket, String firstPath, String secondPath) throws IOException {
        var output = socket.getOutputStream();
        output.write((request(firstPath) + request(secondPath)).getBytes(StandardCharsets.US_ASCII));
        output.flush();
    }

    private static void writeRequest(Socket socket, String path) throws IOException {
        var output = socket.getOutputStream();
        output.write(request(path).getBytes(StandardCharsets.US_ASCII));
        output.flush();
    }

    private static String request(String path) {
        return "GET " + path + " HTTP/1.1\r\n"
                + "Host: localhost\r\n"
                + "Connection: keep-alive\r\n"
                + "\r\n";
    }

    private static void assertNoUsableResponse(Socket socket, String path, String message) throws IOException {
        try {
            writeRequest(socket, path);
            assertConnectionClosed(socket, message);
        } catch (SocketException expected) {
            // An immediate TCP reset while writing is also a rejected, unusable connection.
        }
    }

    private static void assertConnectionClosed(Socket socket, String message) throws IOException {
        socket.setSoTimeout(ASSERTION_TIMEOUT_MILLIS);
        try {
            assertEquals(-1, socket.getInputStream().read(), message);
        } catch (SocketTimeoutException timeout) {
            throw new AssertionError(message, timeout);
        } catch (SocketException expected) {
            // A TCP reset is an acceptable close manifestation for a budget violation.
        }
    }

    private static void await(CountDownLatch latch, String message) throws InterruptedException {
        assertTrue(latch.await(ASSERTION_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS), message);
    }
}
