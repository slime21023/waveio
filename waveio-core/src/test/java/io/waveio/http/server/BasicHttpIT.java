package io.waveio.http.server;

import io.waveio.http.testing.DefaultHttpServerITSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.waveio.http.Cookie;
import io.waveio.http.HttpResponse;
import io.waveio.http.testing.Await;
import io.waveio.http.testing.RawHttpResponse;
import io.waveio.http.testing.TestPublishers;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BasicHttpIT extends DefaultHttpServerITSupport {

    @Test
    void servesRouteAndMiddlewareOverTcp() throws Exception {
        var response = get("/users/42?view=full");

        assertEquals(200, response.statusCode());
        assertEquals("42:full:middleware", response.body());
        assertEquals("text/plain; charset=utf-8",
                response.headers().firstValue("content-type").orElseThrow());
    }

    @Test
    void readsRequestBody() throws Exception {
        var request = java.net.http.HttpRequest.newBuilder(baseUri.resolve("/echo"))
                .POST(BodyPublishers.ofString("wave"))
                .build();

        var response = client.send(request, BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
        assertEquals("wave", response.body());
    }

    @Test
    void rejectsBodyOverConfiguredLimit() throws Exception {
        var request = java.net.http.HttpRequest.newBuilder(baseUri.resolve("/echo"))
                .POST(BodyPublishers.ofString("more-than-eight"))
                .build();

        var response = client.send(request, BodyHandlers.ofString());

        assertEquals(413, response.statusCode());
    }

    @Test
    void executesBlockingHandlerOnVirtualThread() throws Exception {
        assertEquals("true", get("/thread").body());
    }

    @Test
    void streamsPublisherWithChunkedEncoding() throws Exception {
        var response = get("/stream");

        assertEquals(200, response.statusCode());
        assertEquals("alpha-beta", response.body());
        assertEquals("chunked", response.headers().firstValue("transfer-encoding").orElseThrow());
    }

    @Test
    void streamsFileWithKnownContentLength() throws Exception {
        var response = get("/file");

        assertEquals(200, response.statusCode());
        assertEquals("file-body-" + "x".repeat(40_000), response.body());
        assertEquals(response.body().getBytes(StandardCharsets.UTF_8).length,
                response.headers().firstValueAsLong("content-length").orElseThrow());
    }

    @Test
    void readsAndWritesCookies() throws Exception {
        var request = java.net.http.HttpRequest.newBuilder(baseUri.resolve("/cookie"))
                .header("Cookie", "theme=dark; session=original")
                .GET()
                .build();

        var response = client.send(request, BodyHandlers.ofString());

        assertEquals("original", response.body());
        assertEquals("session=renewed; Path=/; Secure; HttpOnly; SameSite=Lax",
                response.headers().firstValue("set-cookie").orElseThrow());
    }

    @Test
    void mapsExpectedAndUnexpectedFailures() throws Exception {
        var expected = get("/missing-user");
        var unexpected = get("/failure");

        assertEquals(404, expected.statusCode());
        assertEquals("User not found", expected.body());
        assertEquals(500, unexpected.statusCode());
        assertEquals("Internal Server Error", unexpected.body());
        assertFalse(unexpected.body().contains("secret"));
    }

    @Test
    void returnsNotFoundForUnmatchedRoute() throws Exception {
        var response = get("/does-not-exist");

        assertEquals(404, response.statusCode());
        assertEquals("Not Found", response.body());
    }

    @Test
    void returnsMethodNotAllowedWithAllowHeader() throws Exception {
        var request = java.net.http.HttpRequest.newBuilder(baseUri.resolve("/hello"))
                .PUT(BodyPublishers.noBody())
                .build();

        var response = client.send(request, BodyHandlers.ofString());

        assertEquals(405, response.statusCode());
        assertEquals("GET, HEAD", response.headers().firstValue("allow").orElseThrow());
    }

    @Test
    void rejectsRequestThatExceedsHeaderLimit() throws Exception {
        try (var limitedServer = HttpServer.builder()
                .port(0)
                .maxHeaderSize(64)
                .get("/", request -> HttpResponse.text("unexpected"))
                .build()
                .start();
                var socket = new Socket("127.0.0.1", limitedServer.localPort())) {
            socket.setSoTimeout(5_000);
            var rawRequest = "GET / HTTP/1.1\r\nHost: localhost\r\nX-Large: "
                    + "x".repeat(128) + "\r\n\r\n";
            socket.getOutputStream().write(rawRequest.getBytes(StandardCharsets.US_ASCII));
            var response = new String(socket.getInputStream().readAllBytes(),
                    StandardCharsets.US_ASCII);

            assertTrue(response.startsWith("HTTP/1.1 400"));
            assertFalse(response.contains("unexpected"));
        }
    }
}
