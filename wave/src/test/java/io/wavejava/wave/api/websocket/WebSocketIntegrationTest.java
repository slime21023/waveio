package io.wavejava.wave.api.websocket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.wavejava.wave.Wave;
import io.wavejava.wave.WaveApp;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** Real-socket WebSocket upgrade and callback-boundary coverage. */
class WebSocketIntegrationTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    @Test
    void upgradesARoutedEndpointAndDeliversTextOutsideTheEventLoop() throws Exception {
        var endpointStarted = new CountDownLatch(1);
        var textReceived = new CountDownLatch(1);
        var endpointThread = new AtomicReference<Thread>();
        var echoed = new AtomicReference<String>();
        var app = Wave.app().routes(routes -> routes.websocket("/chat", session -> {
            endpointThread.set(Thread.currentThread());
            endpointStarted.countDown();
            session.inbound().subscribe(new Flow.Subscriber<>() {
                @Override
                public void onSubscribe(Flow.Subscription subscription) {
                    subscription.request(Long.MAX_VALUE);
                }

                @Override
                public void onNext(WebSocketMessage message) {
                    message.text().ifPresent(text -> session.sendText("echo:" + text));
                }

                @Override
                public void onError(Throwable failure) {
                    // The test drives a normal close after receiving the echo.
                }

                @Override
                public void onComplete() {
                    // The test drives a normal close after receiving the echo.
                }
            });
        })).build();

        try (var server = Wave.server(app).listen(0).start()) {
            var listener = new java.net.http.WebSocket.Listener() {
                @Override
                public void onOpen(java.net.http.WebSocket socket) {
                    socket.request(1);
                }

                @Override
                public java.util.concurrent.CompletionStage<?> onText(
                        java.net.http.WebSocket socket, CharSequence data, boolean last) {
                    if (last) {
                        echoed.set(data.toString());
                        textReceived.countDown();
                    }
                    socket.request(1);
                    return CompletableFuture.completedFuture(null);
                }
            };
            var client = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
            var socket = client.newWebSocketBuilder()
                    .connectTimeout(TIMEOUT)
                    .buildAsync(URI.create("ws://127.0.0.1:" + server.port() + "/chat"), listener)
                    .get(TIMEOUT.toNanos(), TimeUnit.NANOSECONDS);

            assertTrue(endpointStarted.await(TIMEOUT.toNanos(), TimeUnit.NANOSECONDS), "endpoint did not start");
            socket.sendText("hello", true).get(TIMEOUT.toNanos(), TimeUnit.NANOSECONDS);
            assertTrue(textReceived.await(TIMEOUT.toNanos(), TimeUnit.NANOSECONDS), "echo was not received");
            assertEquals("echo:hello", echoed.get());
            assertTrue(endpointThread.get().isVirtual(), "endpoint must run on the invocation runtime");
            assertFalse(endpointThread.get().getName().startsWith("nioEventLoopGroup-"));
            socket.sendClose(java.net.http.WebSocket.NORMAL_CLOSURE, "done")
                    .get(TIMEOUT.toNanos(), TimeUnit.NANOSECONDS);
        }
    }

    @Test
    void stillAnswersPingWhenOneSlowSubscriberMessageIsRetained() throws Exception {
        var pongReceived = new CountDownLatch(1);
        var app = Wave.app().routes(routes -> routes.websocket("/control", session ->
                session.inbound().subscribe(new Flow.Subscriber<>() {
                    @Override
                    public void onSubscribe(Flow.Subscription subscription) {
                        // Deliberately retain one data message without application demand.
                    }

                    @Override
                    public void onNext(WebSocketMessage item) {
                        throw new AssertionError("no demand was requested");
                    }

                    @Override
                    public void onError(Throwable failure) {
                        // The test aborts its client after proving automatic Pong progress.
                    }

                    @Override
                    public void onComplete() {
                        // The test aborts its client after proving automatic Pong progress.
                    }
                }))).build();

        try (var server = Wave.server(app).listen(0).start()) {
            var listener = new java.net.http.WebSocket.Listener() {
                @Override
                public void onOpen(java.net.http.WebSocket socket) {
                    socket.request(1);
                }

                @Override
                public java.util.concurrent.CompletionStage<?> onPong(
                        java.net.http.WebSocket socket, ByteBuffer message) {
                    pongReceived.countDown();
                    socket.request(1);
                    return CompletableFuture.completedFuture(null);
                }
            };
            var socket = HttpClient.newHttpClient().newWebSocketBuilder()
                    .connectTimeout(TIMEOUT)
                    .buildAsync(URI.create("ws://127.0.0.1:" + server.port() + "/control"), listener)
                    .get(TIMEOUT.toNanos(), TimeUnit.NANOSECONDS);

            socket.sendText("held", true).get(TIMEOUT.toNanos(), TimeUnit.NANOSECONDS);
            socket.sendPing(ByteBuffer.wrap(new byte[] {7})).get(TIMEOUT.toNanos(), TimeUnit.NANOSECONDS);
            assertTrue(pongReceived.await(TIMEOUT.toNanos(), TimeUnit.NANOSECONDS),
                    "control ping must not be blocked behind a retained application message");
            socket.abort();
        }
    }

    @Test
    void peerCloseCompletesAZeroDemandSubscriberWithoutWaitingForAnApplicationCloseItem() throws Exception {
        var inboundMessages = new AtomicInteger();
        var completed = new CountDownLatch(1);
        var failed = new AtomicReference<Throwable>();
        var app = Wave.app().routes(routes -> routes.websocket("/peer-close", session ->
                session.inbound().subscribe(new Flow.Subscriber<>() {
                    @Override
                    public void onSubscribe(Flow.Subscription subscription) {
                        // Deliberately retain no demand. The peer close must still terminate this
                        // source promptly, without treating its close frame as data backpressure.
                    }

                    @Override
                    public void onNext(WebSocketMessage item) {
                        inboundMessages.incrementAndGet();
                    }

                    @Override
                    public void onError(Throwable failure) {
                        failed.set(failure);
                    }

                    @Override
                    public void onComplete() {
                        completed.countDown();
                    }
                }))).build();

        try (var server = Wave.server(app).listen(0).start()) {
            var socket = HttpClient.newHttpClient().newWebSocketBuilder()
                    .connectTimeout(TIMEOUT)
                    .buildAsync(URI.create("ws://127.0.0.1:" + server.port() + "/peer-close"),
                            new java.net.http.WebSocket.Listener() { })
                    .get(TIMEOUT.toNanos(), TimeUnit.NANOSECONDS);
            socket.sendText("held", true).get(TIMEOUT.toNanos(), TimeUnit.NANOSECONDS);
            socket.sendClose(java.net.http.WebSocket.NORMAL_CLOSURE, "done")
                    .get(TIMEOUT.toNanos(), TimeUnit.NANOSECONDS);

            assertTrue(completed.await(TIMEOUT.toNanos(), TimeUnit.NANOSECONDS),
                    "peer close must complete an application Flow with zero remaining demand");
            assertEquals(0, inboundMessages.get(), "the optional close notification must not bypass demand");
            assertTrue(failed.get() == null, "a normal peer close must not become an application failure");
        }
    }

    @Test
    void rejectsOutboundMessageLargerThanTheConfiguredFrameBudget() throws Exception {
        var rejection = new AtomicReference<Throwable>();
        var rejected = new CountDownLatch(1);
        var endpoint = WebSocket.withLimits(session -> session.sendText("x".repeat(65)).whenComplete((ignored, failure) -> {
            rejection.set(failure);
            rejected.countDown();
        }), new WebSocketLimits(64, 128, 256, Duration.ofSeconds(1)));
        var app = Wave.app().routes(routes -> routes.websocket("/limited", endpoint)).build();

        try (var server = Wave.server(app).listen(0).start()) {
            var socket = HttpClient.newHttpClient().newWebSocketBuilder()
                    .connectTimeout(TIMEOUT)
                    .buildAsync(URI.create("ws://127.0.0.1:" + server.port() + "/limited"),
                            new java.net.http.WebSocket.Listener() { })
                    .get(TIMEOUT.toNanos(), TimeUnit.NANOSECONDS);
            assertTrue(rejected.await(TIMEOUT.toNanos(), TimeUnit.NANOSECONDS),
                    "oversized outbound message was not rejected");
            assertTrue(rejection.get() instanceof WebSocketLimitExceededException);
            socket.abort();
        }
    }

    @Test
    void tcpFinCancelsAndInterruptsABlockedEndpoint() throws Exception {
        var probe = new BlockedEndpointProbe();
        try (var server = Wave.server(probe.application()).listen(0).start();
                var socket = TestWebSocketClient.connect(URI.create("ws://127.0.0.1:" + server.port() + "/block"))) {
            establishRawWebSocket(socket, probe);
            socket.finishOutput();
            probe.assertTerminated("TCP FIN");
        }
    }

    @Test
    void tcpResetCancelsAndInterruptsABlockedEndpoint() throws Exception {
        var probe = new BlockedEndpointProbe();
        try (var server = Wave.server(probe.application()).listen(0).start();
                var socket = TestWebSocketClient.connect(URI.create("ws://127.0.0.1:" + server.port() + "/block"))) {
            establishRawWebSocket(socket, probe);
            socket.abort();
            probe.assertTerminated("TCP RST");
        }
    }

    @Test
    void rejectsAnUnmaskedClientFrameWithAProtocolErrorClose() throws Exception {
        var probe = new BlockedEndpointProbe();
        try (var server = Wave.server(probe.application()).listen(0).start();
                var socket = TestWebSocketClient.connect(URI.create("ws://127.0.0.1:" + server.port() + "/block"))) {
            establishRawWebSocket(socket, probe);
            socket.send(new TestWebSocketClient.WireFrame(
                    true, false, 0, 1, "x".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            assertCloseCode(socket.readFrame(), 1002);
            probe.assertTerminated("invalid unmasked client frame");
        }
    }

    /** Proves the upgraded pipeline is live before a FIN/RST lifecycle assertion begins. */
    private static void establishRawWebSocket(TestWebSocketClient socket, BlockedEndpointProbe probe) throws Exception {
        probe.assertStarted();
        // These are deliberately the first WebSocket frames: neither a data frame nor an arbitrary
        // delay may be used to hide an upgrade read-registration race.
        socket.sendPing(new byte[0]);
        assertPong(socket.readFrame(), new byte[0]);
        socket.sendPing(new byte[] {7});
        assertPong(socket.readFrame(), new byte[] {7});
    }

    private static final class BlockedEndpointProbe {
        private final CountDownLatch endpointStarted = new CountDownLatch(1);
        private final CountDownLatch endpointInterrupted = new CountDownLatch(1);
        private final CountDownLatch cancellationSignalled = new CountDownLatch(1);
        private final CountDownLatch sessionClosed = new CountDownLatch(1);
        private final AtomicReference<Boolean> cancellationObserved = new AtomicReference<>();

        WaveApp application() {
            return Wave.app().routes(routes -> routes.websocket("/block", session -> {
                session.closed().whenComplete((ignored, failure) -> sessionClosed.countDown());
                session.request().cancellationToken().orElseThrow().onCancellation(reason -> {
                    cancellationSignalled.countDown();
                });
                endpointStarted.countDown();
                try {
                    new CountDownLatch(1).await();
                } catch (InterruptedException expected) {
                    cancellationObserved.set(session.request().cancellationToken().orElseThrow().isCancelled());
                    endpointInterrupted.countDown();
                    Thread.currentThread().interrupt();
                }
            })).build();
        }

        void assertStarted() throws InterruptedException {
            assertTrue(endpointStarted.await(TIMEOUT.toNanos(), TimeUnit.NANOSECONDS), "endpoint did not start");
        }

        void assertTerminated(String transport) throws InterruptedException {
            assertTrue(sessionClosed.await(TIMEOUT.toNanos(), TimeUnit.NANOSECONDS),
                    transport + " did not complete the server WebSocket transport");
            assertTrue(cancellationSignalled.await(TIMEOUT.toNanos(), TimeUnit.NANOSECONDS),
                    transport + " did not cancel the WebSocket session");
            assertTrue(endpointInterrupted.await(TIMEOUT.toNanos(), TimeUnit.NANOSECONDS),
                    transport + " did not interrupt the endpoint invocation");
            assertTrue(Boolean.TRUE.equals(cancellationObserved.get()),
                    "session context must be cancelled before endpoint interruption");
        }
    }

    private static void assertPong(TestWebSocketClient.WireFrame frame, byte[] expectedPayload) {
        assertTrue(frame.fin(), "server Pong must be final");
        assertFalse(frame.masked(), "server Pong must be unmasked");
        assertEquals(10, frame.opcode(), "server frame must be Pong");
        assertArrayEquals(expectedPayload, frame.payload(), "server Pong payload");
    }

    /** Reads a finite server close frame and verifies its RFC 6455 status code. */
    private static void assertCloseCode(TestWebSocketClient.WireFrame frame, int expectedCode) {
        assertTrue(frame.fin(), "server close must be final");
        assertFalse(frame.masked(), "server close must be unmasked");
        assertEquals(8, frame.opcode(), "server frame must be close");
        var payload = frame.payload();
        assertTrue(payload.length >= 2 && payload.length <= 125, "close payload must contain a finite status code");
        var code = (Byte.toUnsignedInt(payload[0]) << 8) | Byte.toUnsignedInt(payload[1]);
        assertEquals(expectedCode, code);
    }
}
