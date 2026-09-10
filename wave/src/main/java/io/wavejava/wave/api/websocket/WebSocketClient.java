package io.wavejava.wave.api.websocket;

import java.net.URI;
import java.util.concurrent.CompletionStage;

/** A bounded direct-WebSocket client created by {@link io.wavejava.wave.Wave}. */
public interface WebSocketClient extends AutoCloseable {
    WebSocketClientRequest.Builder request(URI uri);

    CompletionStage<WebSocketConnection> connect(WebSocketClientRequest request);

    boolean isClosed();

    @Override
    void close();
}
