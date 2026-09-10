package io.wavejava.wave.api.websocket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.wavejava.wave.api.http.Headers;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Public direct-WebSocket client validation and transport-boundary contracts. */
class WebSocketClientContractTest {
    @Test
    void requestAcceptsOnlyDirectWsAndProtectsHandshakeOwnedHeaders() {
        assertThrows(IllegalArgumentException.class,
                () -> WebSocketClientRequest.of(URI.create("wss://example.test/chat")));
        assertThrows(IllegalArgumentException.class,
                () -> WebSocketClientRequest.builder()
                        .uri(URI.create("ws://example.test/chat"))
                        .header("Sec-WebSocket-Key", "caller-owned")
                        .build());
        assertThrows(IllegalArgumentException.class,
                () -> WebSocketClientRequest.builder()
                        .uri(URI.create("ws://example.test/chat"))
                        .subprotocols(List.of("chat", "chat"))
                        .build());

        var request = WebSocketClientRequest.builder()
                .uri(URI.create("ws://example.test:8080/chat?room=blue"))
                .headers(Headers.of("Authorization", "Bearer safe"))
                .subprotocol("chat.v1")
                .timeout(Duration.ofSeconds(1))
                .build();
        assertEquals("ws", request.uri().getScheme());
        assertEquals(List.of("chat.v1"), request.subprotocols());
        assertEquals("Bearer safe", request.headers().first("Authorization").orElseThrow());
    }

    @Test
    void clientBuilderRequiresFiniteBudgetsAndClosesIdempotently() {
        assertThrows(IllegalArgumentException.class, () -> WebSocketClientOptions.builder().maximumConnections(0));
        assertThrows(IllegalArgumentException.class, () -> WebSocketClientOptions.builder().idleTimeout(Duration.ZERO));

        var client = io.wavejava.wave.Wave.webSocketClient(WebSocketClientOptions.builder().maximumConnections(1).build());
        assertFalse(client.isClosed());
        client.close();
        client.close();
        assertTrue(client.isClosed());
        assertTrue(client.connect(WebSocketClientRequest.of(URI.create("ws://127.0.0.1:1/")))
                .toCompletableFuture().isCompletedExceptionally());
    }
}
