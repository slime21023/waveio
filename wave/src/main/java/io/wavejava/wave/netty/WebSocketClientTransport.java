package io.wavejava.wave.netty;

import io.wavejava.wave.api.websocket.WebSocketClientRequest;
import io.wavejava.wave.api.websocket.WebSocketConnection;
import java.util.concurrent.CompletionStage;

/** Private seam between the exported direct-WebSocket client and its owned transport. */
public interface WebSocketClientTransport extends AutoCloseable {
    /** Starts one physical direct-{@code ws} connection attempt. */
    CompletionStage<WebSocketConnection> connect(WebSocketClientRequest request);

    /** Creates the owned Netty transport implementation. */
    static WebSocketClientTransport netty(WebSocketClientConfig config) {
        return new NettyWebSocketClientTransport(config);
    }

    @Override
    void close();
}

