package io.wavejava.wave.api.websocket;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.wavejava.wave.Wave;
import io.wavejava.wave.api.http.HttpMethod;
import io.wavejava.wave.api.http.Request;
import io.wavejava.wave.api.http.Response;
import io.wavejava.wave.internal.http.ResponseDataReader;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Public WebSocket value and ordinary-handler acceptance contracts. */
class WebSocketContractTest {
    @Test
    void binaryAndControlMessagesDefensivelyOwnTheirPayloads() {
        var source = new byte[] {1, 2, 3};
        var binary = WebSocketMessage.binary(source);
        source[0] = 9;
        assertArrayEquals(new byte[] {1, 2, 3}, binary.bytes());

        var buffer = ByteBuffer.wrap(new byte[] {4, 5, 6});
        buffer.position(1);
        assertArrayEquals(new byte[] {5, 6}, WebSocketMessage.binary(buffer).bytes());
        assertThrows(IllegalArgumentException.class, () -> WebSocketMessage.ping(new byte[126]));
        assertThrows(IllegalArgumentException.class, () -> WebSocketMessage.close(1005, "reserved"));
    }

    @Test
    void limitsRequireFiniteAndInternallyConsistentBudgets() {
        assertThrows(IllegalArgumentException.class,
                () -> new WebSocketLimits(1024, 512, 1024, Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class,
                () -> new WebSocketLimits(1024, 1024, 1024, Duration.ZERO));
        assertThrows(IllegalArgumentException.class,
                () -> new WebSocketLimits(64, 64, 64, Duration.ofSeconds(1)));
        new WebSocketLimits(64, 64, 78, Duration.ofSeconds(1));
        assertThrows(IllegalArgumentException.class,
                () -> WebSocket.withSubprotocols(session -> { }, List.of("chat", "chat")));
    }

    @Test
    void routeHelperUsesTheExistingVoidHttpHandlerPipeline() {
        WebSocket endpoint = session -> { };
        var app = Wave.app().routes(routes -> routes.websocket("/chat", endpoint)).build();
        var response = app.handle(Request.of(HttpMethod.GET, "/chat"));

        assertTrue(response.isCommitted());
        assertEquals(endpoint, ResponseDataReader.read(response).webSocket().orElseThrow());
    }

    @Test
    void responseRejectsHttpEntityHeadersForAnUpgrade() {
        var response = Response.create().header("Content-Length", "0");
        assertThrows(IllegalStateException.class, () -> response.webSocket(session -> { }));
    }
}
