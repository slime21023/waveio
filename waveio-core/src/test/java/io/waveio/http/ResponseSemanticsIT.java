package io.waveio.http;

import io.waveio.http.testing.DefaultHttpServerITSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

class ResponseSemanticsIT extends DefaultHttpServerITSupport {

    @Test
    void headFallsBackToGetAndSuppressesBodyOverTcp() throws Exception {
        var request = java.net.http.HttpRequest.newBuilder(baseUri.resolve("/head-fallback"))
                .method("HEAD", BodyPublishers.noBody())
                .build();

        var response = client.send(request, BodyHandlers.ofByteArray());

        assertEquals(200, response.statusCode());
        assertEquals("14", response.headers().firstValue("content-length").orElseThrow());
        assertEquals(0, response.body().length);
    }

    @Test
    void explicitHeadRouteTakesPrecedenceOverGetOverTcp() throws Exception {
        var request = java.net.http.HttpRequest.newBuilder(baseUri.resolve("/head-explicit"))
                .method("HEAD", BodyPublishers.noBody())
                .build();

        var response = client.send(request, BodyHandlers.ofByteArray());

        assertEquals("head", response.headers().firstValue("x-handler").orElseThrow());
        assertEquals("8", response.headers().firstValue("content-length").orElseThrow());
        assertEquals(0, response.body().length);
    }

    @Test
    void bodyForbiddenFinalStatusesHaveNoBodyOrFramingOverTcp() throws Exception {
        for (String path : List.of("/no-content-body", "/not-modified-body")) {
            var response = get(path);
            assertEquals("", response.body());
            assertTrue(response.headers().firstValue("content-length").isEmpty());
            assertTrue(response.headers().firstValue("transfer-encoding").isEmpty());
        }
    }

    @Test
    void informationalStatusHasNoBodyOrFramingOverRawTcp() throws Exception {
        try (var socket = new Socket("127.0.0.1", server.localPort())) {
            socket.setSoTimeout(5_000);
            socket.getOutputStream().write(("GET /early-hints-body HTTP/1.1\r\n"
                    + "Host: localhost\r\nConnection: close\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));

            String headers = readResponseHeaders(socket);
            assertTrue(headers.startsWith("HTTP/1.1 103 Early Hints"));
            assertFalse(headers.toLowerCase(java.util.Locale.ROOT).contains("content-length:"));
            assertFalse(headers.toLowerCase(java.util.Locale.ROOT).contains("transfer-encoding:"));
            assertEquals(-1, socket.getInputStream().read());
        }
    }
}
