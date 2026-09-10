package io.wavejava.wave;

import io.wavejava.wave.api.application.WaveApp;
import io.wavejava.wave.api.client.WaveClient;
import io.wavejava.wave.api.client.WaveClientOptions;
import io.wavejava.wave.api.server.WaveServer;
import io.wavejava.wave.api.sse.SseClient;
import io.wavejava.wave.api.sse.SseClientOptions;
import io.wavejava.wave.api.websocket.WebSocketClient;
import io.wavejava.wave.api.websocket.WebSocketClientOptions;
import io.wavejava.wave.internal.bootstrap.BuiltInWaveFactory;

/** Entry point for building a wave application and server. */
public final class Wave {
    private Wave() {
    }

    /** Starts a builder for one immutable application. */
    public static WaveApp.Builder app() {
        return WaveApp.builder();
    }

    /** Creates an unbound server for {@code app}. */
    public static WaveServer server(WaveApp app) {
        return BuiltInWaveFactory.server(app);
    }

    /** Creates a bounded HTTP client with default options. */
    public static WaveClient client() {
        return client(WaveClientOptions.builder().build());
    }

    /** Creates a bounded HTTP client from immutable options. */
    public static WaveClient client(WaveClientOptions options) {
        return BuiltInWaveFactory.client(options);
    }

    /** Creates a bounded SSE client with default options. */
    public static SseClient sseClient() {
        return sseClient(SseClientOptions.builder().build());
    }

    /** Creates a bounded SSE client from immutable options. */
    public static SseClient sseClient(SseClientOptions options) {
        return BuiltInWaveFactory.sseClient(options);
    }

    /** Creates a direct WebSocket client with default limits. */
    public static WebSocketClient webSocketClient() {
        return webSocketClient(WebSocketClientOptions.builder().build());
    }

    /** Creates a direct WebSocket client from immutable options. */
    public static WebSocketClient webSocketClient(WebSocketClientOptions options) {
        return BuiltInWaveFactory.webSocketClient(options);
    }
}
