package io.wavejava.wave.api.sse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** Direct HTTP/1.1 socket coverage for the standalone bounded SSE client. */
class SseClientIntegrationTest {
    private static final int TIMEOUT_MILLIS = 5_000;

    @Test
    void reconnectsWithLastEventIdAndTheServerSpecifiedRetryDelay() throws Exception {
        try (var upstream = new ServerSocket(0)) {
            var firstRequest = new AtomicReference<String>();
            var secondRequest = new AtomicReference<String>();
            var upstreamFailure = new AtomicReference<Throwable>();
            var accepted = new CountDownLatch(2);
            var upstreamDone = new CountDownLatch(1);
            Thread.startVirtualThread(() -> {
                try {
                    try (var first = upstream.accept()) {
                        firstRequest.set(readRequest(first));
                        writeResponse(first, "id: one\nretry: 999999999999999\ndata: first\n\n");
                        accepted.countDown();
                    }
                    try (var second = upstream.accept()) {
                        secondRequest.set(readRequest(second));
                        writeResponse(second, "data: second\n\n");
                        accepted.countDown();
                    }
                } catch (Throwable failure) {
                    upstreamFailure.set(failure);
                } finally {
                    upstreamDone.countDown();
                }
            });

            var events = new CopyOnWriteArrayList<SseEvent>();
            var eventThreadsAreVirtual = new AtomicBoolean();
            var eventsReceived = new CountDownLatch(2);
            try (var client = io.wavejava.wave.Wave.sseClient(SseClientOptions.builder()
                    .initialReconnectDelay(Duration.ZERO)
                    .maximumReconnectDelay(Duration.ZERO)
                    .build())) {
                var connection = client.connect(uri(upstream), new SseListener() {
                    @Override
                    public void onEvent(SseEvent event) {
                        eventThreadsAreVirtual.set(Thread.currentThread().isVirtual());
                        events.add(event);
                        eventsReceived.countDown();
                    }
                });

                assertTrue(eventsReceived.await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS),
                        "two direct SSE exchanges should dispatch two events");
                connection.close();
                connection.completion().toCompletableFuture().get(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
            }

            assertTrue(accepted.await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS));
            assertTrue(upstreamDone.await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS));
            assertEquals(null, upstreamFailure.get());
            assertFalse(firstRequest.get().toLowerCase().contains("last-event-id:"));
            assertTrue(secondRequest.get().toLowerCase().contains("last-event-id: one"));
            assertEquals(List.of("first", "second"), events.stream().map(event -> event.data().orElseThrow()).toList());
            assertEquals("one", events.getFirst().id().orElseThrow());
            assertEquals(Duration.ZERO, events.getFirst().retry().orElseThrow(),
                    "server retry must be clamped to the configured finite reconnect-delay policy");
            assertTrue(eventThreadsAreVirtual.get(), "listener callbacks must not run on a transport event loop");
        }
    }

    @Test
    void acceptsAConnectTimeoutBeyondNettyMillisecondRange() throws Exception {
        try (var upstream = new ServerSocket(0)) {
            var upstreamDone = new CountDownLatch(1);
            Thread.startVirtualThread(() -> {
                try (var socket = upstream.accept()) {
                    readRequest(socket);
                    writeResponse(socket, "data: accepted\n\n");
                } catch (IOException ignored) {
                    // A test assertion is allowed to close the local peer early.
                } finally {
                    upstreamDone.countDown();
                }
            });

            var event = new AtomicReference<SseEvent>();
            var received = new CountDownLatch(1);
            try (var client = io.wavejava.wave.Wave.sseClient(SseClientOptions.builder()
                    .connectTimeout(Duration.ofDays(30))
                    .maximumReconnectAttempts(0)
                    .build())) {
                var connection = client.connect(uri(upstream), value -> {
                    event.set(value);
                    received.countDown();
                });
                assertTrue(received.await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS),
                        "a valid timeout larger than Netty's millisecond range must still connect");
                connection.completion().toCompletableFuture().get(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
            }

            assertTrue(upstreamDone.await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS));
            assertEquals("accepted", event.get().data().orElseThrow());
        }
    }

    @Test
    void rejectsAnOverLimitWireLineWithoutAccumulatingAnEventBuffer() throws Exception {
        try (var upstream = new ServerSocket(0)) {
            var upstreamDone = new CountDownLatch(1);
            Thread.startVirtualThread(() -> {
                try (var socket = upstream.accept()) {
                    readRequest(socket);
                    writeResponse(socket, "data: this line is deliberately larger than eight bytes\n\n");
                } catch (IOException ignored) {
                    // The client is expected to tear down the over-limit response.
                } finally {
                    upstreamDone.countDown();
                }
            });

            var failure = new AtomicReference<Throwable>();
            var failed = new CountDownLatch(1);
            try (var client = io.wavejava.wave.Wave.sseClient(SseClientOptions.builder().maximumLineBytes(8).maximumReconnectAttempts(0).build())) {
                var connection = client.connect(uri(upstream), new SseListener() {
                    @Override
                    public void onEvent(SseEvent event) {
                        throw new AssertionError("over-limit event must not be dispatched");
                    }

                    @Override
                    public void onFailure(Throwable cause) {
                        failure.set(cause);
                        failed.countDown();
                    }
                });

                assertTrue(failed.await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS));
                var completion = org.junit.jupiter.api.Assertions.assertThrows(CompletionException.class,
                        () -> connection.completion().toCompletableFuture().join());
                assertInstanceOf(SseProtocolException.class, completion.getCause());
                assertInstanceOf(SseProtocolException.class, failure.get());
            }
            assertTrue(upstreamDone.await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS));
        }
    }

    @Test
    void explicitCloseInterruptsTheLiveReadAndCompletesWithoutLeakingAReconnectTask() throws Exception {
        try (var upstream = new ServerSocket(0)) {
            var opened = new CountDownLatch(1);
            var closed = new CountDownLatch(1);
            var upstreamAccepted = new CountDownLatch(1);
            Thread.startVirtualThread(() -> {
                try (var socket = upstream.accept()) {
                    upstreamAccepted.countDown();
                    readRequest(socket);
                    socket.getOutputStream().write(("HTTP/1.1 200 OK\r\n"
                            + "Content-Type: text/event-stream; charset=UTF-8\r\n"
                            + "Cache-Control: no-cache\r\n"
                            + "Connection: keep-alive\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                    socket.getOutputStream().flush();
                    while (socket.getInputStream().read() >= 0) {
                        // Hold a live, silent peer until client close reaches the socket.
                    }
                } catch (IOException ignored) {
                    // Client close is the expected terminal event.
                }
            });

            try (var client = io.wavejava.wave.Wave.sseClient()) {
                var connection = client.connect(uri(upstream), new SseListener() {
                    @Override
                    public void onEvent(SseEvent event) {
                        throw new AssertionError("silent peer must not emit an event");
                    }

                    @Override
                    public void onOpen(SseResponseInfo response) {
                        opened.countDown();
                    }

                    @Override
                    public void onClosed() {
                        closed.countDown();
                    }
                });

                assertTrue(opened.await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS));
                assertTrue(upstreamAccepted.await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS));
                assertTrue(connection.isOpen());
                connection.close();
                connection.completion().toCompletableFuture().get(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
                assertTrue(closed.await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS));
                assertFalse(connection.isOpen());
            }
        }
    }

    @Test
    void enforcesFiniteConnectionAdmissionAndReleasesThePermitAfterClose() throws Exception {
        try (var upstream = new ServerSocket(0)) {
            var accepted = new CountDownLatch(2);
            var upstreamFailure = new AtomicReference<Throwable>();
            var upstreamDone = new CountDownLatch(1);
            Thread.startVirtualThread(() -> {
                try {
                    for (var index = 0; index < 2; index++) {
                        try (var socket = upstream.accept()) {
                            readRequest(socket);
                            socket.getOutputStream().write(("HTTP/1.1 200 OK\r\n"
                                    + "Content-Type: text/event-stream\r\n"
                                    + "Connection: keep-alive\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                            socket.getOutputStream().flush();
                            accepted.countDown();
                            while (socket.getInputStream().read() >= 0) {
                                // One silent peer at a time keeps the client's permit occupied.
                            }
                        }
                    }
                } catch (Throwable failure) {
                    upstreamFailure.set(failure);
                } finally {
                    upstreamDone.countDown();
                }
            });

            var secondOpened = new CountDownLatch(1);
            try (var client = io.wavejava.wave.Wave.sseClient(SseClientOptions.builder().maximumConnections(1).build())) {
                var first = client.connect(uri(upstream), event -> {
                    throw new AssertionError("silent peer must not emit an event");
                });
                // The request has reached the server once it accepts the first socket.
                assertTrue(awaitCountAtMost(accepted, 1), "first connection should consume the only admission permit");
                assertThrows(IllegalStateException.class,
                        () -> client.connect(uri(upstream), event -> { }),
                        "a second live/reconnecting connection must be rejected at admission");

                first.close();
                first.completion().toCompletableFuture().get(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);

                var second = client.connect(uri(upstream), new SseListener() {
                    @Override
                    public void onEvent(SseEvent event) {
                        throw new AssertionError("silent peer must not emit an event");
                    }

                    @Override
                    public void onOpen(SseResponseInfo response) {
                        secondOpened.countDown();
                    }
                });
                assertTrue(secondOpened.await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS),
                        "closing the first connection must release its admission permit");
                second.close();
                second.completion().toCompletableFuture().get(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
            }
            assertTrue(accepted.await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS));
            assertTrue(upstreamDone.await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS));
            assertEquals(null, upstreamFailure.get());
        }
    }

    @Test
    void decodesChunkedSseAndIgnoresExactlyOneInitialUtf8Bom() throws Exception {
        try (var upstream = new ServerSocket(0)) {
            var upstreamDone = new CountDownLatch(1);
            Thread.startVirtualThread(() -> {
                try (var socket = upstream.accept()) {
                    readRequest(socket);
                    writeChunkedResponse(socket, List.of(
                            new byte[] {(byte) 0xef},
                            new byte[] {(byte) 0xbb, (byte) 0xbf, 'd', 'a', 't', 'a', ':', ' '},
                            "chunked\n\n".getBytes(StandardCharsets.UTF_8)));
                } catch (IOException ignored) {
                    // A client-side protocol assertion is allowed to close the test peer early.
                } finally {
                    upstreamDone.countDown();
                }
            });

            var event = new AtomicReference<SseEvent>();
            var received = new CountDownLatch(1);
            var closed = new CountDownLatch(1);
            try (var client = io.wavejava.wave.Wave.sseClient(SseClientOptions.builder().maximumReconnectAttempts(0).build())) {
                var connection = client.connect(uri(upstream), new SseListener() {
                    @Override
                    public void onEvent(SseEvent value) {
                        event.set(value);
                        received.countDown();
                    }

                    @Override
                    public void onClosed() {
                        closed.countDown();
                    }
                });

                assertTrue(received.await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS));
                connection.completion().toCompletableFuture().get(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
            }

            assertTrue(upstreamDone.await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS));
            assertTrue(closed.await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS));
            assertEquals("chunked", event.get().data().orElseThrow());
        }
    }

    @Test
    void rejectsResponseHeadersThatExceedByteOrFieldCountBudgetsBeforeExposure() throws Exception {
        try (var upstream = new ServerSocket(0)) {
            var firstDone = new CountDownLatch(1);
            var secondDone = new CountDownLatch(1);
            var requestHeadsRead = new CountDownLatch(2);
            Thread.startVirtualThread(() -> {
                try (var first = upstream.accept()) {
                    readRequest(first);
                    requestHeadsRead.countDown();
                    first.getOutputStream().write(("HTTP/1.1 200 OK\r\n"
                            + "Content-Type: text/event-stream\r\n"
                            + "X-Fill: " + "x".repeat(256) + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                    first.getOutputStream().flush();
                } catch (IOException ignored) {
                    // The header limit is expected to close this peer.
                } finally {
                    firstDone.countDown();
                }
                try (var second = upstream.accept()) {
                    readRequest(second);
                    requestHeadsRead.countDown();
                    second.getOutputStream().write(("HTTP/1.1 200 OK\r\n"
                            + "Content-Type: text/event-stream\r\n"
                            + "X-Second: retained-never\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                    second.getOutputStream().flush();
                } catch (IOException ignored) {
                    // The field-count limit is expected to close this peer.
                } finally {
                    secondDone.countDown();
                }
            });

            assertProtocolFailure(
                    io.wavejava.wave.Wave.sseClient(SseClientOptions.builder().maximumHeaderBytes(160).maximumReconnectAttempts(0).build()), uri(upstream));
            assertProtocolFailure(
                    io.wavejava.wave.Wave.sseClient(SseClientOptions.builder().maximumHeaderCount(1).maximumReconnectAttempts(0).build()), uri(upstream));

            assertTrue(firstDone.await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS));
            assertTrue(secondDone.await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS));
            assertTrue(requestHeadsRead.await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS),
                    "both response-limit failures must occur after a complete client request was written");
        }
    }

    @Test
    void enforcesAnAbsoluteResponseHeadDeadlineAgainstSlowlorisBytes() throws Exception {
        try (var upstream = new ServerSocket(0)) {
            var upstreamDone = new CountDownLatch(1);
            Thread.startVirtualThread(() -> {
                try (var socket = upstream.accept()) {
                    readRequest(socket);
                    var output = socket.getOutputStream();
                    while (true) {
                        output.write('H');
                        output.flush();
                        Thread.sleep(Duration.ofMillis(20));
                    }
                } catch (IOException | InterruptedException ignored) {
                    // The client deadline is expected to close the slowloris peer.
                } finally {
                    upstreamDone.countDown();
                }
            });

            var failure = new AtomicReference<Throwable>();
            var failed = new CountDownLatch(1);
            var started = System.nanoTime();
            try (var client = io.wavejava.wave.Wave.sseClient(SseClientOptions.builder()
                    .responseOpenTimeout(Duration.ofMillis(100))
                    .maximumReconnectAttempts(0)
                    .build())) {
                var connection = client.connect(uri(upstream), failingListener(failure, failed));
                assertTrue(failed.await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS),
                        "a byte-at-a-time response head must still hit its absolute deadline");
                assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 1_000,
                        "the total response-head deadline must not reset for each received byte");
                assertTrue(hasCause(failure.get(), java.net.SocketTimeoutException.class));
                assertThrows(CompletionException.class, () -> connection.completion().toCompletableFuture().join());
            }
            assertTrue(upstreamDone.await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS));
        }
    }

    @Test
    void reconnectsAfterAbruptResetWithImmediatelyParsedIdAndRetryState() throws Exception {
        try (var upstream = new ServerSocket(0)) {
            var firstEvent = new CountDownLatch(1);
            var upstreamDone = new CountDownLatch(1);
            var secondRequest = new AtomicReference<String>();
            Thread.startVirtualThread(() -> {
                try {
                    try (var first = upstream.accept()) {
                        readRequest(first);
                        first.getOutputStream().write(("HTTP/1.1 200 OK\r\n"
                                + "Content-Type: text/event-stream\r\n"
                                + "Connection: keep-alive\r\n\r\n"
                                + "id: reset-id\nretry: 17\ndata: before-reset\n\n")
                                .getBytes(StandardCharsets.UTF_8));
                        first.getOutputStream().flush();
                        if (!firstEvent.await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                            throw new IOException("client did not parse the first event");
                        }
                        first.setSoLinger(true, 0);
                    }
                    try (var second = upstream.accept()) {
                        secondRequest.set(readRequest(second));
                        writeResponse(second, "data: after-reset\n\n");
                    }
                } catch (Throwable ignored) {
                    // Assertions below distinguish a missing reconnect from an expected reset.
                } finally {
                    upstreamDone.countDown();
                }
            });

            var events = new CopyOnWriteArrayList<SseEvent>();
            var retryDelay = new AtomicReference<Duration>();
            var twoEvents = new CountDownLatch(2);
            try (var client = io.wavejava.wave.Wave.sseClient(SseClientOptions.builder()
                    .maximumReconnectAttempts(1)
                    .maximumReconnectDelay(Duration.ofMillis(20))
                    .build())) {
                var connection = client.connect(uri(upstream), new SseListener() {
                    @Override
                    public void onEvent(SseEvent event) {
                        events.add(event);
                        firstEvent.countDown();
                        twoEvents.countDown();
                    }

                    @Override
                    public void onReconnect(Duration delay, int attempt) {
                        retryDelay.set(delay);
                    }
                });

                assertTrue(twoEvents.await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS));
                connection.completion().toCompletableFuture().get(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
            }

            assertTrue(upstreamDone.await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS));
            assertTrue(secondRequest.get().toLowerCase().contains("last-event-id: reset-id"));
            assertEquals(Duration.ofMillis(17), retryDelay.get());
            assertEquals(List.of("before-reset", "after-reset"),
                    events.stream().map(value -> value.data().orElseThrow()).toList());
        }
    }

    @Test
    void idleTimeoutAfterAValidResponseHeadIsRetryableButFinite() throws Exception {
        try (var upstream = new ServerSocket(0)) {
            var upstreamDone = new CountDownLatch(1);
            Thread.startVirtualThread(() -> {
                try (var socket = upstream.accept()) {
                    readRequest(socket);
                    socket.getOutputStream().write(("HTTP/1.1 200 OK\r\n"
                            + "Content-Type: text/event-stream\r\n"
                            + "Connection: keep-alive\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                    socket.getOutputStream().flush();
                    while (socket.getInputStream().read() >= 0) {
                        // Keep the socket quiet until the client idle timeout closes it.
                    }
                } catch (IOException ignored) {
                    // The expected idle deadline closes the peer.
                } finally {
                    upstreamDone.countDown();
                }
            });

            var failure = new AtomicReference<Throwable>();
            var failed = new CountDownLatch(1);
            var opened = new CountDownLatch(1);
            try (var client = io.wavejava.wave.Wave.sseClient(SseClientOptions.builder()
                    .idleTimeout(Duration.ofMillis(75))
                    .maximumReconnectAttempts(0)
                    .build())) {
                var connection = client.connect(uri(upstream), new SseListener() {
                    @Override
                    public void onEvent(SseEvent event) {
                        throw new AssertionError("silent peer must not emit an event");
                    }

                    @Override
                    public void onOpen(SseResponseInfo response) {
                        opened.countDown();
                    }

                    @Override
                    public void onFailure(Throwable cause) {
                        failure.set(cause);
                        failed.countDown();
                    }
                });
                assertTrue(opened.await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS),
                        "the valid response head must open before idle monitoring starts");
                assertTrue(failed.await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS));
                assertTrue(hasCause(failure.get(), java.net.SocketTimeoutException.class));
                assertThrows(CompletionException.class, () -> connection.completion().toCompletableFuture().join());
            }
            assertTrue(upstreamDone.await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS));
        }
    }

    private static URI uri(ServerSocket upstream) {
        return URI.create("http://127.0.0.1:" + upstream.getLocalPort() + "/events");
    }

    private static String readRequest(Socket socket) throws IOException {
        var bytes = new ByteArrayOutputStream();
        var delimiter = 0;
        while (delimiter != 4) {
            var next = socket.getInputStream().read();
            if (next < 0) {
                throw new IOException("client closed before request headers completed");
            }
            bytes.write(next);
            delimiter = switch (delimiter) {
                case 0 -> next == '\r' ? 1 : 0;
                case 1 -> next == '\n' ? 2 : next == '\r' ? 1 : 0;
                case 2 -> next == '\r' ? 3 : 0;
                case 3 -> next == '\n' ? 4 : next == '\r' ? 1 : 0;
                default -> throw new AssertionError("invalid header delimiter state");
            };
        }
        return bytes.toString(StandardCharsets.US_ASCII);
    }

    private static void writeResponse(Socket socket, String eventWire) throws IOException {
        var response = "HTTP/1.1 200 OK\r\n"
                + "Content-Type: text/event-stream; charset=UTF-8\r\n"
                + "Cache-Control: no-cache\r\n"
                + "Connection: close\r\n\r\n"
                + eventWire;
        socket.getOutputStream().write(response.getBytes(StandardCharsets.UTF_8));
        socket.getOutputStream().flush();
    }

    private static void writeChunkedResponse(Socket socket, List<byte[]> chunks) throws IOException {
        var output = socket.getOutputStream();
        output.write(("HTTP/1.1 200 OK\r\n"
                + "Content-Type: text/event-stream; charset=UTF-8\r\n"
                + "Transfer-Encoding: chunked\r\n"
                + "Connection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        for (var chunk : chunks) {
            output.write(Integer.toHexString(chunk.length).getBytes(StandardCharsets.US_ASCII));
            output.write("\r\n".getBytes(StandardCharsets.US_ASCII));
            output.write(chunk);
            output.write("\r\n".getBytes(StandardCharsets.US_ASCII));
        }
        output.write("0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
        output.flush();
    }

    private static void assertProtocolFailure(SseClient client, URI uri) throws Exception {
        try (client) {
            var failure = new AtomicReference<Throwable>();
            var failed = new CountDownLatch(1);
            var connection = client.connect(uri, failingListener(failure, failed));
            assertTrue(failed.await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS));
            assertInstanceOf(SseProtocolException.class, failure.get());
            var completion = assertThrows(CompletionException.class,
                    () -> connection.completion().toCompletableFuture().join());
            assertInstanceOf(SseProtocolException.class, completion.getCause());
        }
    }

    private static SseListener failingListener(AtomicReference<Throwable> failure, CountDownLatch failed) {
        return new SseListener() {
            @Override
            public void onEvent(SseEvent event) {
                throw new AssertionError("this test only accepts a terminal failure");
            }

            @Override
            public void onFailure(Throwable cause) {
                failure.set(cause);
                failed.countDown();
            }
        };
    }

    private static boolean hasCause(Throwable failure, Class<? extends Throwable> type) {
        for (var current = failure; current != null; current = current.getCause()) {
            if (type.isInstance(current)) {
                return true;
            }
        }
        return false;
    }

    private static boolean awaitCountAtMost(CountDownLatch latch, long expectedRemaining) throws InterruptedException {
        var deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(TIMEOUT_MILLIS);
        while (System.nanoTime() < deadline) {
            if (latch.getCount() <= expectedRemaining) {
                return true;
            }
            Thread.sleep(5);
        }
        return latch.getCount() <= expectedRemaining;
    }
}

