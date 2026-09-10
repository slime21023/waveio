package io.wavejava.wave.api.websocket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.wavejava.wave.Wave;
import io.wavejava.wave.api.http.CancellationToken;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** Real-socket direct-{@code ws} client coverage against the owned Wave server transport. */
class WebSocketClientIntegrationTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    @Test
    void directClientMasksMessagesNegotiatesSubprotocolAndRunsInboundCallbacksOffEventLoop() throws Exception {
        var echoed = new CountDownLatch(1);
        var callbackThread = new AtomicReference<Thread>();
        var received = new AtomicReference<String>();
        var endpoint = WebSocket.withSubprotocols(session -> session.inbound().subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(WebSocketMessage item) {
                item.text().ifPresent(text -> session.sendText("echo:" + text));
            }

            @Override
            public void onError(Throwable failure) {
                // The client owns the normal close in this test.
            }

            @Override
            public void onComplete() {
                // The client owns the normal close in this test.
            }
        }), List.of("chat.v1"));
        var app = Wave.app().routes(routes -> routes.websocket("/echo", endpoint)).build();

        try (var server = Wave.server(app).listen(0).start();
                var client = WebSocketClient.builder().idleTimeout(Duration.ofSeconds(30)).build()) {
            var connection = client.connect(WebSocketClientRequest.builder()
                            .uri(URI.create("ws://127.0.0.1:" + server.port() + "/echo"))
                            .subprotocol("chat.v1")
                            .build())
                    .toCompletableFuture()
                    .get(TIMEOUT.toNanos(), TimeUnit.NANOSECONDS);

            assertEquals("chat.v1", connection.subprotocol().orElseThrow());
            connection.inbound().subscribe(new Flow.Subscriber<>() {
                @Override
                public void onSubscribe(Flow.Subscription subscription) {
                    subscription.request(Long.MAX_VALUE);
                }

                @Override
                public void onNext(WebSocketMessage item) {
                    callbackThread.set(Thread.currentThread());
                    received.set(item.text().orElseThrow());
                    echoed.countDown();
                }

                @Override
                public void onError(Throwable failure) {
                    throw new AssertionError(failure);
                }

                @Override
                public void onComplete() {
                    // Close completion is asserted through the connection stage below.
                }
            });
            connection.sendText("hello").toCompletableFuture().get(TIMEOUT.toNanos(), TimeUnit.NANOSECONDS);
            assertTrue(echoed.await(TIMEOUT.toNanos(), TimeUnit.NANOSECONDS), "client did not receive server echo");
            assertEquals("echo:hello", received.get());
            assertTrue(callbackThread.get().isVirtual(), "client inbound callback must not run on Netty EventLoop");
            connection.close(1000, "done").toCompletableFuture().get(TIMEOUT.toNanos(), TimeUnit.NANOSECONDS);
        }
    }

    @Test
    void nonUpgradeHttpResponseFailsWithBoundedHandshakeDetails() throws Exception {
        var app = Wave.app().routes(routes -> routes.get("/plain", (request, response) -> response.text("not a socket"))).build();

        try (var server = Wave.server(app).listen(0).start();
                var client = WebSocketClient.builder().build()) {
            var future = client.connect(WebSocketClientRequest.of(
                    URI.create("ws://127.0.0.1:" + server.port() + "/plain"))).toCompletableFuture();
            var failure = java.util.concurrent.ExecutionException.class.cast(
                    org.junit.jupiter.api.Assertions.assertThrows(java.util.concurrent.ExecutionException.class,
                            () -> future.get(TIMEOUT.toNanos(), TimeUnit.NANOSECONDS))).getCause();
            var handshake = assertInstanceOf(WebSocketHandshakeException.class, failure);
            assertEquals(200, handshake.status());
        }
    }

    @Test
    void physicalConnectionAdmissionIsReleasedOnlyAfterTheConnectionCloses() throws Exception {
        var connected = new CountDownLatch(1);
        var app = Wave.app().routes(routes -> routes.websocket("/hold", session -> connected.countDown())).build();

        try (var server = Wave.server(app).listen(0).start();
                var client = WebSocketClient.builder().maximumConnections(1).build()) {
            var target = URI.create("ws://127.0.0.1:" + server.port() + "/hold");
            var first = client.connect(WebSocketClientRequest.of(target)).toCompletableFuture()
                    .get(TIMEOUT.toNanos(), TimeUnit.NANOSECONDS);
            assertTrue(connected.await(TIMEOUT.toNanos(), TimeUnit.NANOSECONDS));

            var rejected = client.connect(WebSocketClientRequest.of(target)).toCompletableFuture();
            var rejection = java.util.concurrent.ExecutionException.class.cast(
                    org.junit.jupiter.api.Assertions.assertThrows(java.util.concurrent.ExecutionException.class,
                            () -> rejected.get(TIMEOUT.toNanos(), TimeUnit.NANOSECONDS))).getCause();
            assertInstanceOf(WebSocketClientRejectedException.class, rejection);

            first.abort();
            org.junit.jupiter.api.Assertions.assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> first.closed().toCompletableFuture().get(TIMEOUT.toNanos(), TimeUnit.NANOSECONDS));
            var second = client.connect(WebSocketClientRequest.of(target)).toCompletableFuture()
                    .get(TIMEOUT.toNanos(), TimeUnit.NANOSECONDS);
            second.abort();
        }
    }

    @Test
    void clientShutdownAbortsActiveConnectionsAndRejectsNewAdmission() throws Exception {
        try (var upstream = TestWebSocketUpstream.start();
                var client = WebSocketClient.builder().build()) {
            var connection = client.connect(WebSocketClientRequest.of(upstream.uri())).toCompletableFuture()
                    .get(TIMEOUT.toNanos(), TimeUnit.NANOSECONDS);
            upstream.awaitHandshake();

            client.close();
            assertTrue(client.isClosed());
            assertInstanceOf(WebSocketClosedException.class,
                    awaitFailure(connection.closed().toCompletableFuture()));
            assertInstanceOf(IllegalStateException.class,
                    awaitFailure(client.connect(WebSocketClientRequest.of(upstream.uri())).toCompletableFuture()));
        }
    }

    @Test
    void establishmentDeadlineClosesThePhysicalSocketBeforeItsAdmissionCanBeReused() throws Exception {
        try (var peer = RawHandshakePeer.stalling();
                var client = WebSocketClient.builder()
                        .maximumConnections(1)
                        .handshakeTimeout(Duration.ofMillis(250))
                        .build()) {
            var future = client.connect(WebSocketClientRequest.of(peer.uri())).toCompletableFuture();
            peer.assertRequestObserved();

            var failure = awaitFailure(future);
            assertInstanceOf(WebSocketClientTimeoutException.class, failure);
            peer.assertConnectionClosed();
            peer.assertHealthy();
        }
    }

    @Test
    void requestCancellationAbortsAnInProgressHandshakeAndClosesThePhysicalSocket() throws Exception {
        var cancellation = CancellationToken.create();
        try (var peer = RawHandshakePeer.stalling();
                var client = WebSocketClient.builder().build()) {
            var future = client.connect(WebSocketClientRequest.builder()
                            .uri(peer.uri())
                            .cancellationToken(cancellation)
                            .build())
                    .toCompletableFuture();
            peer.assertRequestObserved();
            cancellation.cancel("test cancellation");

            assertInstanceOf(CancellationException.class, awaitFailure(future));
            peer.assertConnectionClosed();
            peer.assertHealthy();
        }
    }

    @Test
    void handshakeResponseHeaderCountIsBoundedBeforeAConnectionIsPublished() throws Exception {
        var response = "HTTP/1.1 101 Switching Protocols\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Content-Length: 0\r\n"
                + "X-First: one\r\n"
                + "X-Second: two\r\n\r\n";
        try (var peer = RawHandshakePeer.responding(response);
                var client = WebSocketClient.builder().maximumResponseHeaders(2).build()) {
            var future = client.connect(WebSocketClientRequest.of(peer.uri())).toCompletableFuture();
            peer.assertRequestObserved();

            var limit = assertInstanceOf(WebSocketClientLimitExceededException.class, awaitFailure(future));
            assertEquals("response header count", limit.limitName());
            peer.assertConnectionClosed();
            peer.assertHealthy();
        }
    }

    @Test
    void handshakeResponseHeaderBytesAreRejectedByTheDecoderBeforeRetention() throws Exception {
        var response = "HTTP/1.1 101 Switching Protocols\r\n"
                + "X-Oversized: " + "x".repeat(512) + "\r\n\r\n";
        try (var peer = RawHandshakePeer.responding(response);
                var client = WebSocketClient.builder().maximumResponseHeaderBytes(64).build()) {
            var future = client.connect(WebSocketClientRequest.of(peer.uri())).toCompletableFuture();
            peer.assertRequestObserved();

            var limit = assertInstanceOf(WebSocketClientLimitExceededException.class, awaitFailure(future));
            assertEquals("response header bytes", limit.limitName());
            peer.assertConnectionClosed();
            peer.assertHealthy();
        }
    }

    @Test
    void clientHandlesServerTextPingAndCloseWithMaskedOutboundControlFrames() throws Exception {
        var received = new AtomicReference<String>();
        var delivered = new CountDownLatch(1);
        try (var upstream = TestWebSocketUpstream.start();
                var client = WebSocketClient.builder().idleTimeout(Duration.ofSeconds(30)).build()) {
            var connection = client.connect(WebSocketClientRequest.of(upstream.uri())).toCompletableFuture()
                    .get(TIMEOUT.toNanos(), TimeUnit.NANOSECONDS);
            upstream.awaitHandshake();
            connection.inbound().subscribe(new Flow.Subscriber<>() {
                @Override
                public void onSubscribe(Flow.Subscription subscription) {
                    subscription.request(Long.MAX_VALUE);
                }

                @Override
                public void onNext(WebSocketMessage item) {
                    received.set(item.text().orElseThrow());
                    delivered.countDown();
                }

                @Override
                public void onError(Throwable failure) {
                    throw new AssertionError(failure);
                }

                @Override
                public void onComplete() {
                    // Normal close is awaited below.
                }
            });
            upstream.sendText("from-upstream");
            assertTrue(delivered.await(TIMEOUT.toNanos(), TimeUnit.NANOSECONDS));
            assertEquals("from-upstream", received.get());

            connection.sendText("from-client").toCompletableFuture().get(TIMEOUT.toNanos(), TimeUnit.NANOSECONDS);
            var outboundText = upstream.readFrame();
            assertTrue(outboundText.masked(), "client application data must be masked");
            assertEquals(1, outboundText.opcode());
            assertArrayEquals("from-client".getBytes(StandardCharsets.UTF_8), outboundText.payload());

            upstream.sendPing(new byte[] {7, 8});
            var pong = upstream.readFrame();
            assertTrue(pong.masked(), "client Pongs must be masked");
            assertEquals(10, pong.opcode());
            assertArrayEquals(new byte[] {7, 8}, pong.payload());

            var closing = connection.close(1000, "done");
            var close = upstream.readFrame();
            assertTrue(close.masked(), "client close must be masked");
            assertEquals(8, close.opcode());
            upstream.sendClose(1000, "done");
            closing.toCompletableFuture().get(TIMEOUT.toNanos(), TimeUnit.NANOSECONDS);
            upstream.assertHealthy();
        }
    }

    @Test
    void slowClientInboundSubscriberRetainsOneMessageThenClosesWithBoundedOverflow() throws Exception {
        var subscribed = new CountDownLatch(1);
        var delivered = new CountDownLatch(1);
        try (var upstream = TestWebSocketUpstream.start();
                var client = WebSocketClient.builder().idleTimeout(Duration.ofSeconds(30)).build()) {
            var connection = client.connect(WebSocketClientRequest.of(upstream.uri())).toCompletableFuture()
                    .get(TIMEOUT.toNanos(), TimeUnit.NANOSECONDS);
            upstream.awaitHandshake();
            connection.inbound().subscribe(new Flow.Subscriber<>() {
                @Override
                public void onSubscribe(Flow.Subscription subscription) {
                    // Deliberately retain zero demand: one message is the complete bounded
                    // application buffer, and the next must cause protocol progress rather than
                    // grow a queue or stall control-frame processing.
                    subscribed.countDown();
                }

                @Override
                public void onNext(WebSocketMessage item) {
                    delivered.countDown();
                }

                @Override
                public void onError(Throwable failure) {
                    // The overflow close is the expected terminal path.
                }

                @Override
                public void onComplete() {
                    // The overflow close is the expected terminal path.
                }
            });
            assertTrue(subscribed.await(TIMEOUT.toNanos(), TimeUnit.NANOSECONDS));

            upstream.sendText("retained");
            upstream.sendText("overflow");
            var close = upstream.readFrame();
            assertTrue(close.masked());
            assertEquals(8, close.opcode());
            assertEquals(1009, closeCode(close));
            upstream.sendClose(1009, "inbound consumer is too slow");
            connection.closed().toCompletableFuture().get(TIMEOUT.toNanos(), TimeUnit.NANOSECONDS);
            assertEquals(1L, delivered.getCount(), "zero demand must not receive retained application data");
            upstream.assertHealthy();
        }
    }

    @Test
    void maskedServerDataFrameTriggersAProtocolCloseInsteadOfReachingTheClientSubscriber() throws Exception {
        var delivered = new CountDownLatch(1);
        try (var upstream = TestWebSocketUpstream.start();
                var client = WebSocketClient.builder().build()) {
            var connection = client.connect(WebSocketClientRequest.of(upstream.uri())).toCompletableFuture()
                    .get(TIMEOUT.toNanos(), TimeUnit.NANOSECONDS);
            upstream.awaitHandshake();
            connection.inbound().subscribe(new Flow.Subscriber<>() {
                @Override
                public void onSubscribe(Flow.Subscription subscription) {
                    subscription.request(Long.MAX_VALUE);
                }

                @Override
                public void onNext(WebSocketMessage item) {
                    delivered.countDown();
                }

                @Override
                public void onError(Throwable failure) {
                    // The malformed transport is the expected terminal path.
                }

                @Override
                public void onComplete() {
                    // The malformed transport is the expected terminal path.
                }
            });
            upstream.send(new TestWebSocketClient.WireFrame(
                    true, true, 0, 1, "invalid-mask".getBytes(StandardCharsets.UTF_8)));
            var close = upstream.readFrame();
            assertTrue(close.masked());
            assertEquals(8, close.opcode());
            assertEquals(1002, closeCode(close));
            // Netty's strict frame decoder emits the RFC 6455 protocol close and immediately
            // tears down its transport for a mask-direction violation. A successful peer-close
            // acknowledgement is therefore neither observable nor required for malformed wire
            // input; the normal close-handshake test covers that separate contract.
            assertInstanceOf(WebSocketClosedException.class,
                    awaitFailure(connection.closed().toCompletableFuture()));
            assertEquals(1L, delivered.getCount(), "invalid server data must not reach application Flow");
        }
    }

    private static int closeCode(TestWebSocketClient.WireFrame frame) {
        var payload = frame.payload();
        assertTrue(payload.length >= 2);
        return (Byte.toUnsignedInt(payload[0]) << 8) | Byte.toUnsignedInt(payload[1]);
    }

    private static Throwable awaitFailure(CompletableFuture<?> future) throws Exception {
        try {
            future.get(TIMEOUT.toNanos(), TimeUnit.NANOSECONDS);
        } catch (java.util.concurrent.ExecutionException failure) {
            return failure.getCause();
        } catch (CancellationException failure) {
            return failure;
        }
        throw new AssertionError("future completed successfully when failure was required");
    }

    /** Single-connection raw server peer for client deadline, cancellation, and handshake-limit tests. */
    private static final class RawHandshakePeer implements AutoCloseable {
        private static final int MAXIMUM_REQUEST_HEAD_BYTES = 16 * 1024;

        private final ServerSocket listener;
        private final String response;
        private final CountDownLatch requestObserved = new CountDownLatch(1);
        private final CountDownLatch connectionClosed = new CountDownLatch(1);
        private final AtomicReference<Socket> connection = new AtomicReference<>();
        private final AtomicReference<Throwable> failure = new AtomicReference<>();

        private RawHandshakePeer(String response) throws IOException {
            this.response = response;
            listener = new ServerSocket(0);
            Thread.ofVirtual().name("wave-ws-client-raw-peer").start(this::serve);
        }

        static RawHandshakePeer stalling() throws IOException {
            return new RawHandshakePeer(null);
        }

        static RawHandshakePeer responding(String response) throws IOException {
            return new RawHandshakePeer(response);
        }

        URI uri() {
            return URI.create("ws://127.0.0.1:" + listener.getLocalPort() + "/chat");
        }

        void assertRequestObserved() throws InterruptedException {
            assertTrue(requestObserved.await(TIMEOUT.toNanos(), TimeUnit.NANOSECONDS),
                    "client did not write a WebSocket handshake request");
        }

        void assertConnectionClosed() throws InterruptedException {
            assertTrue(connectionClosed.await(TIMEOUT.toNanos(), TimeUnit.NANOSECONDS),
                    "client did not physically close the raw handshake socket");
        }

        void assertHealthy() {
            var observed = failure.get();
            if (observed != null) {
                throw new AssertionError("raw handshake peer failed", observed);
            }
        }

        @Override
        public void close() {
            try {
                listener.close();
            } catch (IOException ignored) {
                // Fixture close is idempotent.
            }
            var active = connection.get();
            if (active != null) {
                try {
                    active.close();
                } catch (IOException ignored) {
                    // Fixture close is idempotent.
                }
            }
        }

        private void serve() {
            try (var accepted = listener.accept()) {
                connection.set(accepted);
                accepted.setSoTimeout(Math.toIntExact(TIMEOUT.toMillis()));
                readRequestHead(accepted);
                requestObserved.countDown();
                if (response != null) {
                    accepted.getOutputStream().write(response.getBytes(StandardCharsets.ISO_8859_1));
                    accepted.getOutputStream().flush();
                }
                while (accepted.getInputStream().read() >= 0) {
                    // The test peer retains no application data; EOF is the asserted physical close.
                }
                connectionClosed.countDown();
            } catch (IOException expectedFromClientClose) {
                if (requestObserved.getCount() == 0) {
                    connectionClosed.countDown();
                } else if (!listener.isClosed()) {
                    failure.compareAndSet(null, expectedFromClientClose);
                }
            } catch (Throwable unexpected) {
                failure.compareAndSet(null, unexpected);
            }
        }

        private static void readRequestHead(Socket socket) throws IOException {
            var bytes = new ByteArrayOutputStream();
            var delimiter = 0;
            while (bytes.size() < MAXIMUM_REQUEST_HEAD_BYTES) {
                var next = socket.getInputStream().read();
                if (next < 0) {
                    throw new IOException("client closed before sending an upgrade request");
                }
                bytes.write(next);
                delimiter = switch (delimiter) {
                    case 0 -> next == '\r' ? 1 : 0;
                    case 1 -> next == '\n' ? 2 : next == '\r' ? 1 : 0;
                    case 2 -> next == '\r' ? 3 : 0;
                    case 3 -> next == '\n' ? 4 : next == '\r' ? 1 : 0;
                    default -> throw new AssertionError("invalid request delimiter state");
                };
                if (delimiter == 4) {
                    return;
                }
            }
            throw new IOException("client WebSocket handshake request exceeded fixture bound");
        }
    }
}
