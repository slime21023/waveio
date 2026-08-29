package io.waveio.http.server;

import io.waveio.http.testing.DefaultHttpServerITSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

class PipeliningIT extends DefaultHttpServerITSupport {

    @Test
    void preservesHttpPipeliningResponseOrder() throws Exception {
        try (var socket = new Socket("127.0.0.1", server.localPort())) {
            socket.setSoTimeout(5_000);
            var requests = "GET /slow HTTP/1.1\r\nHost: localhost\r\n\r\n"
                    + "GET /hello HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n";
            socket.getOutputStream().write(requests.getBytes(StandardCharsets.US_ASCII));
            var responses = new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

            assertTrue(responses.indexOf("slow") >= 0);
            assertTrue(responses.indexOf("hello") > responses.indexOf("slow"));
        }
    }

    @Test
    void finishesStreamingResponseBeforeNextPipelinedResponse() throws Exception {
        try (var socket = new Socket("127.0.0.1", server.localPort())) {
            socket.setSoTimeout(5_000);
            var requests = "GET /stream HTTP/1.1\r\nHost: localhost\r\n\r\n"
                    + "GET /hello HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n";
            socket.getOutputStream().write(requests.getBytes(StandardCharsets.US_ASCII));
            var responses = new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

            assertTrue(responses.indexOf("alpha") >= 0);
            assertTrue(responses.indexOf("hello") > responses.indexOf("beta"));
        }
    }

    @Test
    void boundsAlreadyBufferedPipelinedRequestsPerConnection() throws Exception {
        var invocations = new AtomicInteger();
        var release = new CountDownLatch(1);
        try (var boundedServer = HttpServer.builder()
                .port(0)
                .maxPendingRequestsPerConnection(2)
                .blockingGet("/bounded", request -> {
                    invocations.incrementAndGet();
                    release.await(5, TimeUnit.SECONDS);
                    return HttpResponse.text("ok");
                })
                .build()
                .start();
                var socket = new Socket("127.0.0.1", boundedServer.localPort())) {
            socket.setSoTimeout(5_000);
            var requests = new StringBuilder();
            for (int index = 0; index < 20; index++) {
                requests.append("GET /bounded HTTP/1.1\r\nHost: localhost\r\n\r\n");
            }
            socket.getOutputStream().write(requests.toString().getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();

            boolean remoteClosed = false;
            long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
            while (System.nanoTime() < deadline) {
                try {
                    if (socket.getInputStream().read() < 0) {
                        remoteClosed = true;
                        break;
                    }
                } catch (java.net.SocketTimeoutException ignored) {
                    break;
                }
            }
            release.countDown();
            Thread.sleep(50);

            assertTrue(remoteClosed, "overflowed pipeline was not closed");
            assertTrue(invocations.get() <= 1,
                    "queued handlers started after per-connection overflow: " + invocations.get());
        } finally {
            release.countDown();
        }
    }

    @Test
    void asyncPipelinedHandlersMayCompleteInReverseButResponsesRemainOrdered() throws Exception {
        var first = new CompletableFuture<HttpResponse>();
        var second = new CompletableFuture<HttpResponse>();
        var invoked = new CountDownLatch(2);
        try (var asyncServer = HttpServer.builder()
                .port(0)
                .asyncGet("/async-first", request -> {
                    invoked.countDown();
                    return first;
                })
                .asyncGet("/async-second", request -> {
                    invoked.countDown();
                    return second;
                })
                .build()
                .start();
                var socket = new Socket("127.0.0.1", asyncServer.localPort())) {
            socket.setSoTimeout(5_000);
            socket.getOutputStream().write(("GET /async-first HTTP/1.1\r\nHost: localhost\r\n\r\n"
                    + "GET /async-second HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));

            assertTrue(invoked.await(2, TimeUnit.SECONDS),
                    "both async handlers were not started concurrently");
            second.complete(HttpResponse.text("second"));
            first.complete(HttpResponse.text("first"));

            assertTrue(readOneResponse(socket).endsWith("first"));
            assertTrue(readOneResponse(socket).endsWith("second"));
        }
    }

    @Test
    void malformedPipelinedRequestCannotBypassPendingAsyncResponse() throws Exception {
        var first = new CompletableFuture<HttpResponse>();
        try (var orderedServer = HttpServer.builder()
                .port(0)
                .asyncGet("/first", request -> first)
                .build()
                .start();
                var socket = new Socket("127.0.0.1", orderedServer.localPort())) {
            socket.setSoTimeout(5_000);
            socket.getOutputStream().write(("GET /first HTTP/1.1\r\nHost: localhost\r\n\r\n"
                    + "GET /bad/%C3%28 HTTP/1.1\r\nHost: localhost\r\n"
                    + "Connection: close\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));

            first.complete(HttpResponse.text("first"));

            String firstResponse = readOneResponse(socket);
            String rejectedResponse = readOneResponse(socket);
            assertTrue(firstResponse.startsWith("HTTP/1.1 200 OK"));
            assertTrue(firstResponse.endsWith("first"));
            assertTrue(rejectedResponse.startsWith("HTTP/1.1 400 Bad Request"));
        }
    }
}
