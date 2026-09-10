package io.wavejava.wave;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.wavejava.wave.api.sse.SseEmitter;
import io.wavejava.wave.api.sse.SseEvent;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** Real-socket lifecycle coverage for the bounded SSE response adapter. */
class SseIntegrationTest {
    private static final int TIMEOUT_MILLIS = 5_000;
    private static final int MAX_HEADER_BYTES = 16 * 1024;

    @Test
    void writesEventIdRetryAndDataOverChunkedSseResponseInfo() throws Exception {
        var emitterReference = new AtomicReference<SseEmitter>();
        var handlerEntered = new CountDownLatch(1);
        var app = Wave.app().routes(routes -> routes.get("/events", (request, response) -> {
            var emitter = SseEmitter.create();
            emitterReference.set(emitter);
            emitter.writeTo(request, response);
            handlerEntered.countDown();
        })).build();

        try (var server = Wave.server(app).listen(0).start();
                var client = TestSseClient.connect(uri(server, "/events"))) {
            var headers = client.response();
            assertEquals(200, headers.status());
            assertEquals("text/event-stream; charset=UTF-8", headers.headers().first("Content-Type").orElseThrow());
            assertEquals("no-cache", headers.headers().first("Cache-Control").orElseThrow());
            assertEquals("chunked", headers.headers().first("Transfer-Encoding").orElseThrow());
            await(handlerEntered, "SSE handler should commit its response");

            var emitter = emitterReference.get();
            assertEquals(SseEmitter.Emission.ACCEPTED, emitter.emit(SseEvent.heartbeat()));
            assertEquals(SseEmitter.Emission.ACCEPTED, emitter.emit(SseEvent.builder()
                    .event("notice")
                    .id("event-7")
                    .retry(Duration.ofSeconds(2))
                    .data("hello\nworld")
                    .build()));
            assertTrue(emitter.complete());

            var heartbeat = client.readEvent();
            assertEquals("", heartbeat.comment().orElseThrow());
            var event = client.readEvent();
            assertEquals("notice", event.event().orElseThrow());
            assertEquals("event-7", event.id().orElseThrow());
            assertEquals(Duration.ofSeconds(2), event.retry().orElseThrow());
            assertEquals("hello\nworld", event.data().orElseThrow());
            assertEquals(null, client.readEvent(), "the terminal chunk must end the SSE stream");
            assertEquals(SseEmitter.State.CLOSED, emitter.state());
        }
    }

    @Test
    void tcpFinCancelsTheLiveEmitterAndReleasesItsQueue() throws Exception {
        assertDisconnectCancelsEmitter(TestSseClient::finishOutput);
    }

    @Test
    void tcpResetCancelsTheLiveEmitterAndReleasesItsQueue() throws Exception {
        assertDisconnectCancelsEmitter(TestSseClient::abort);
    }

    private static void assertDisconnectCancelsEmitter(Disconnect disconnect) throws Exception {
        var emitterReference = new AtomicReference<SseEmitter>();
        var handlerEntered = new CountDownLatch(1);
        var app = Wave.app().routes(routes -> routes.get("/events", (request, response) -> {
            var emitter = SseEmitter.create();
            emitterReference.set(emitter);
            emitter.writeTo(request, response);
            handlerEntered.countDown();
        })).build();

        try (var server = Wave.server(app).listen(0).start();
                var client = TestSseClient.connect(uri(server, "/events"))) {
            assertEquals(200, client.response().status());
            await(handlerEntered, "SSE handler should commit its response");
            var emitter = emitterReference.get();

            disconnect.apply(client);

            awaitState(emitter, SseEmitter.State.CANCELLED, "disconnect must cancel the response publisher");
            assertEquals(0, emitter.snapshot().queuedEvents());
            assertEquals(0, emitter.snapshot().queuedBytes());
        }
    }

    @Test
    void gracefulServerShutdownCancelsTheLiveEmitterBeforeStoppingTheRuntime() throws Exception {
        var emitterReference = new AtomicReference<SseEmitter>();
        var handlerEntered = new CountDownLatch(1);
        var app = Wave.app().routes(routes -> routes.get("/events", (request, response) -> {
            var emitter = SseEmitter.create();
            emitterReference.set(emitter);
            emitter.writeTo(request, response);
            handlerEntered.countDown();
        })).build();

        var server = Wave.server(app).listen(0).start();
        try (var client = TestSseClient.connect(uri(server, "/events"))) {
            assertEquals(200, client.response().status());
            await(handlerEntered, "SSE handler should commit its response");

            server.close();

            awaitState(emitterReference.get(), SseEmitter.State.CANCELLED,
                    "shutdown must cancel an active SSE Flow subscription before the runtime stops");
        } finally {
            server.close();
        }
    }

    private static URI uri(RunningServer server, String path) {
        return URI.create("http://127.0.0.1:" + server.port() + path);
    }

    @Test
    void invalidEventConstructionIsMappedAsAnApplicationFailureBeforeAResponseIsCommitted() throws Exception {
        var app = Wave.app().routes(routes -> routes.get("/invalid", (request, response) -> {
            SseEvent.builder().event("broken\nname").build();
            response.text("unreachable");
        })).build();

        try (var server = Wave.server(app).listen(0).start();
                var socket = new Socket("127.0.0.1", server.port())) {
            socket.setSoTimeout(TIMEOUT_MILLIS);
            write(socket, request("/invalid", "close"));

            var response = readFixedResponse(socket.getInputStream());
            assertEquals(500, response.status());
            assertFalse(response.headers().containsKey("transfer-encoding"));
        }
    }

    private static String request(String path, String connection) {
        return "GET " + path + " HTTP/1.1\r\n"
                + "Host: localhost\r\n"
                + "Connection: " + connection + "\r\n"
                + "\r\n";
    }

    private static void write(Socket socket, String wire) throws IOException {
        socket.getOutputStream().write(wire.getBytes(StandardCharsets.US_ASCII));
        socket.getOutputStream().flush();
    }

    private static RawHeaders readHeaders(InputStream input) throws IOException {
        var block = readHeaderBlock(input);
        var lines = block.split("\\r\\n");
        var statusParts = lines[0].split(" ", 3);
        if (statusParts.length < 2) {
            throw new IOException("invalid HTTP status line: " + lines[0]);
        }
        var headers = new LinkedHashMap<String, String>();
        for (int index = 1; index < lines.length; index++) {
            var separator = lines[index].indexOf(':');
            if (separator <= 0) {
                throw new IOException("invalid response header: " + lines[index]);
            }
            headers.put(lines[index].substring(0, separator).toLowerCase(Locale.ROOT),
                    lines[index].substring(separator + 1).trim());
        }
        return new RawHeaders(Integer.parseInt(statusParts[1]), Map.copyOf(headers));
    }

    private static RawResponse readFixedResponse(InputStream input) throws IOException {
        var headers = readHeaders(input);
        var length = Integer.parseInt(headers.headers().getOrDefault("content-length", "0"));
        var body = input.readNBytes(length);
        if (body.length != length) {
            throw new EOFException("connection closed before complete fixed response body");
        }
        return new RawResponse(headers.status(), headers.headers(), new String(body, StandardCharsets.UTF_8));
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

    private static void await(CountDownLatch latch, String message) {
        try {
            assertTrue(latch.await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS), message);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(message, interrupted);
        }
    }

    private static void awaitState(SseEmitter emitter, SseEmitter.State expected, String message) throws Exception {
        var deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(TIMEOUT_MILLIS);
        while (System.nanoTime() < deadline) {
            if (emitter.state() == expected) {
                return;
            }
            Thread.sleep(10);
        }
        assertEquals(expected, emitter.state(), message);
    }

    private record RawHeaders(int status, Map<String, String> headers) {
    }

    private record RawResponse(int status, Map<String, String> headers, String body) {
    }

    @FunctionalInterface
    private interface Disconnect {
        void apply(TestSseClient client) throws IOException;
    }
}

