package io.wavejava.wave.api.websocket;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.wavejava.wave.Wave;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** Raw-wire server conformance coverage using the private bounded TestWebSocketClient fixture. */
class WebSocketWireIntegrationTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    @Test
    void fragmentedTextAndInterleavedPingPreserveApplicationMessageAndControlProgress() throws Exception {
        var received = new AtomicReference<String>();
        var delivered = new CountDownLatch(1);
        var app = Wave.app().routes(routes -> routes.websocket("/fragment", session ->
                session.inbound().subscribe(new Flow.Subscriber<>() {
                    @Override
                    public void onSubscribe(Flow.Subscription subscription) {
                        subscription.request(Long.MAX_VALUE);
                    }

                    @Override
                    public void onNext(WebSocketMessage item) {
                        item.text().ifPresent(text -> {
                            received.set(text);
                            delivered.countDown();
                        });
                    }

                    @Override
                    public void onError(Throwable failure) {
                        throw new AssertionError(failure);
                    }

                    @Override
                    public void onComplete() {
                        // The raw client performs the normal close after assertions.
                    }
                }))).build();

        try (var server = Wave.server(app).listen(0).start();
                var client = TestWebSocketClient.connect(uri(server.port(), "/fragment"))) {
            client.send(new TestWebSocketClient.WireFrame(
                    false, true, 0, 1, "hel".getBytes(StandardCharsets.UTF_8)));
            client.sendPing(new byte[] {4, 5});
            assertPong(client.readFrame(), new byte[] {4, 5});
            client.send(new TestWebSocketClient.WireFrame(
                    true, true, 0, 0, "lo".getBytes(StandardCharsets.UTF_8)));

            assertTrue(delivered.await(TIMEOUT.toNanos(), TimeUnit.NANOSECONDS),
                    "fragmented application message was not delivered");
            assertEquals("hello", received.get());
            client.sendClose(1000, "done");
            assertCloseCode(client.readFrame(), 1000);
        }
    }

    @Test
    void invalidRsvAndFrameLimitProduceDeterministicProtocolCloseCodes() throws Exception {
        var endpoint = WebSocket.withLimits(session -> { }, new WebSocketLimits(4, 8, 32, Duration.ofSeconds(1)));
        var app = Wave.app().routes(routes -> routes.websocket("/limited", endpoint)).build();

        try (var server = Wave.server(app).listen(0).start();
                var invalidRsv = TestWebSocketClient.connect(uri(server.port(), "/limited"))) {
            invalidRsv.send(new TestWebSocketClient.WireFrame(true, true, 1, 1, new byte[] {1}));
            assertCloseCode(invalidRsv.readFrame(), 1002);
        }

        try (var server = Wave.server(app).listen(0).start();
                var oversized = TestWebSocketClient.connect(uri(server.port(), "/limited"))) {
            oversized.sendText("12345");
            assertCloseCode(oversized.readFrame(), 1009);
        }
    }

    private static URI uri(int port, String path) {
        return URI.create("ws://127.0.0.1:" + port + path);
    }

    private static void assertPong(TestWebSocketClient.WireFrame frame, byte[] expected) {
        assertTrue(frame.fin());
        assertFalse(frame.masked());
        assertEquals(10, frame.opcode());
        assertArrayEquals(expected, frame.payload());
    }

    private static void assertCloseCode(TestWebSocketClient.WireFrame frame, int expectedCode) {
        assertTrue(frame.fin());
        assertFalse(frame.masked());
        assertEquals(8, frame.opcode());
        var payload = frame.payload();
        assertTrue(payload.length >= 2);
        assertEquals(expectedCode, (Byte.toUnsignedInt(payload[0]) << 8) | Byte.toUnsignedInt(payload[1]));
    }
}
