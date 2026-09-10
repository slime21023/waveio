package io.wavejava.wave;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** Socket-level regression coverage for fail-closed HTTP/1.1 framing validation. */
class Http1RequestFramingIntegrationTest {
    private static final int SOCKET_TIMEOUT_MILLIS = 5_000;

    @Test
    void rejectsContentLengthTransferEncodingAmbiguityBeforeApplicationResult() throws Exception {
        assertUnsafeRequestIsNotDispatched("""
                POST /target HTTP/1.1\r
                Host: localhost\r
                Content-Length: 4\r
                Transfer-Encoding: chunked\r
                \r
                0\r
                \r
                """);
    }

    @Test
    void rejectsDuplicateContentLengthBeforeApplicationResult() throws Exception {
        assertUnsafeRequestIsNotDispatched("""
                POST /target HTTP/1.1\r
                Host: localhost\r
                Content-Length: 1\r
                Content-Length: 1\r
                \r
                x
                """);
    }

    @Test
    void rejectsHttp11RequestWithoutExactlyOneHostBeforeApplicationResult() throws Exception {
        assertUnsafeRequestIsNotDispatched("""
                GET /target HTTP/1.1\r
                Connection: close\r
                \r
                """);
    }

    private static void assertUnsafeRequestIsNotDispatched(String request) throws Exception {
        var invoked = new CountDownLatch(1);
        io.wavejava.wave.api.routing.Handler handler = (inbound, response) -> {
            invoked.countDown();
            response.text("unsafe request reached application");
        };
        var app = Wave.app().routes(routes -> {
            routes.get("/target", handler);
            routes.post("/target", handler);
        }).build();

        try (var server = Wave.server(app).listen(0).start();
                var socket = new Socket("127.0.0.1", server.port())) {
            socket.setSoTimeout(SOCKET_TIMEOUT_MILLIS);
            socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();

            var response = readUntilCloseOrHeaders(socket);
            assertFalse(response.contains("unsafe request reached application"));
            assertTrue(response.isEmpty() || response.startsWith("HTTP/1.1 400"),
                    () -> "unsafe framing must receive 400 or fail closed, response was: " + response);
            assertFalse(invoked.await(250, TimeUnit.MILLISECONDS), "unsafe framing must not invoke the application");
        }
    }

    private static String readUntilCloseOrHeaders(Socket socket) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try {
            while (bytes.size() < 16 * 1024) {
                var next = socket.getInputStream().read();
                if (next < 0) {
                    break;
                }
                bytes.write(next);
                var current = bytes.toString(StandardCharsets.US_ASCII);
                if (current.contains("\r\n\r\n")) {
                    return current;
                }
            }
        } catch (SocketTimeoutException timeout) {
            throw new AssertionError("unsafe framing peer neither responded nor closed", timeout);
        }
        return bytes.toString(StandardCharsets.US_ASCII);
    }
}

