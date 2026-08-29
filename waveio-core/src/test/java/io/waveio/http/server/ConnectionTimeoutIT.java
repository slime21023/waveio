package io.waveio.http.server;

import io.waveio.http.testing.HttpServerITSupport;

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

class ConnectionTimeoutIT extends HttpServerITSupport {

    @Test
    void closesConnectionAfterConfiguredReadTimeout() throws Exception {
        try (var timeoutServer = HttpServer.builder()
                .port(0)
                .readTimeout(Duration.ofMillis(100))
                .get("/", request -> HttpResponse.text("unexpected"))
                .build()
                .start();
                var socket = new Socket("127.0.0.1", timeoutServer.localPort())) {
            socket.setSoTimeout(5_000);
            socket.getOutputStream().write(
                    "GET / HTTP/1.1\r\nHost: localhost\r\n"
                            .getBytes(StandardCharsets.US_ASCII));

            assertEquals(-1, socket.getInputStream().read());
        }
    }

    @Test
    void closesKeepAliveConnectionAfterIdleTimeout() throws Exception {
        try (var idleServer = HttpServer.builder()
                .port(0)
                .idleTimeout(Duration.ofMillis(100))
                .readTimeout(Duration.ofSeconds(1))
                .get("/", request -> HttpResponse.text("ok"))
                .build()
                .start();
                var socket = new Socket("127.0.0.1", idleServer.localPort())) {
            socket.setSoTimeout(5_000);
            socket.getOutputStream().write(
                    "GET / HTTP/1.1\r\nHost: localhost\r\n\r\n"
                            .getBytes(StandardCharsets.US_ASCII));

            assertTrue(readOneResponse(socket).endsWith("ok"));
            assertEquals(-1, socket.getInputStream().read());
        }
    }

    @Test
    void activeRequestIsExemptFromConnectionReadAndIdleTimeouts() throws Exception {
        try (var activeServer = HttpServer.builder()
                .port(0)
                .idleTimeout(Duration.ofMillis(100))
                .readTimeout(Duration.ofMillis(100))
                .handlerTimeout(Duration.ofSeconds(2))
                .blockingGet("/slow", request -> {
                    Thread.sleep(250);
                    return HttpResponse.text("done");
                })
                .build()
                .start();
                var socket = new Socket("127.0.0.1", activeServer.localPort())) {
            socket.setSoTimeout(5_000);
            socket.getOutputStream().write(
                    "GET /slow HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n"
                            .getBytes(StandardCharsets.US_ASCII));

            assertTrue(readOneResponse(socket).endsWith("done"));
        }
    }

    @Test
    void handlerTimeoutInterruptsVirtualThreadAndPreservesPipeliningOrder() throws Exception {
        var started = new CountDownLatch(1);
        var interrupted = new CountDownLatch(1);
        try (var timeoutServer = HttpServer.builder()
                .port(0)
                .handlerTimeout(Duration.ofMillis(50))
                .blockingGet("/timeout", request -> {
                    started.countDown();
                    try {
                        Thread.sleep(Duration.ofSeconds(30));
                    } catch (InterruptedException failure) {
                        interrupted.countDown();
                        throw failure;
                    }
                    return HttpResponse.text("late");
                })
                .get("/after-timeout", request -> HttpResponse.text("after"))
                .build()
                .start();
                var socket = new Socket("127.0.0.1", timeoutServer.localPort())) {
            socket.setSoTimeout(5_000);
            socket.getOutputStream().write(("GET /timeout HTTP/1.1\r\nHost: localhost\r\n\r\n"
                    + "GET /after-timeout HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));

            assertTrue(started.await(2, TimeUnit.SECONDS));
            String timedOut = readOneResponse(socket);
            String following = readOneResponse(socket);

            assertTrue(timedOut.startsWith("HTTP/1.1 504 Gateway Timeout"));
            assertTrue(timedOut.endsWith("Gateway Timeout"));
            assertTrue(following.startsWith("HTTP/1.1 200 OK"));
            assertTrue(following.endsWith("after"));
            assertTrue(interrupted.await(2, TimeUnit.SECONDS));
        }
    }

    @Test
    void handlerCompletingBeforeDeadlineReturnsNormally() throws Exception {
        try (var deadlineServer = HttpServer.builder()
                .port(0)
                .handlerTimeout(Duration.ofMillis(500))
                .blockingGet("/within-deadline", request -> {
                    Thread.sleep(10);
                    return HttpResponse.text("on-time");
                })
                .build()
                .start()) {
            var response = HttpClient.newHttpClient().send(
                    java.net.http.HttpRequest.newBuilder(URI.create(
                            "http://127.0.0.1:" + deadlineServer.localPort() + "/within-deadline"))
                            .GET().build(),
                    BodyHandlers.ofString());

            assertEquals(200, response.statusCode());
            assertEquals("on-time", response.body());
        }
    }

    @Test
    void asyncHandlerTimeoutCancelsStageAndReturns504() throws Exception {
        var stage = new CompletableFuture<HttpResponse>();
        try (var asyncServer = HttpServer.builder()
                .port(0)
                .handlerTimeout(Duration.ofMillis(50))
                .asyncGet("/async-timeout", request -> stage)
                .build()
                .start()) {
            var response = HttpClient.newHttpClient().send(
                    java.net.http.HttpRequest.newBuilder(URI.create(
                            "http://127.0.0.1:" + asyncServer.localPort() + "/async-timeout"))
                            .GET().build(),
                    BodyHandlers.ofString());

            assertEquals(504, response.statusCode());
            assertTrue(stage.isCancelled());
        }
    }
}

