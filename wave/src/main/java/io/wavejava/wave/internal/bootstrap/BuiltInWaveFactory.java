package io.wavejava.wave.internal.bootstrap;

import io.wavejava.wave.api.application.WaveApp;
import io.wavejava.wave.api.client.WaveClient;
import io.wavejava.wave.api.client.WaveClientOptions;
import io.wavejava.wave.api.server.WaveServer;
import io.wavejava.wave.api.sse.SseClient;
import io.wavejava.wave.api.sse.SseClientOptions;
import io.wavejava.wave.api.websocket.WebSocketClient;
import io.wavejava.wave.api.websocket.WebSocketClientOptions;
import io.wavejava.wave.netty.NettyHttp1ClientTransport;
import io.wavejava.wave.netty.NettyHttp2ClientTransport;
import io.wavejava.wave.netty.NettyServerTransport;
import io.wavejava.wave.netty.NettySseClient;
import io.wavejava.wave.netty.NettyWebSocketClient;
import io.wavejava.wave.runtime.client.DefaultWaveClient;
import io.wavejava.wave.runtime.server.DefaultWaveServer;
import java.util.Objects;

/** The one internal place that selects wave's built-in runtime and transport implementations. */
public final class BuiltInWaveFactory {
    private BuiltInWaveFactory() {
    }

    public static WaveServer server(WaveApp application) {
        return new DefaultWaveServer(Objects.requireNonNull(application, "application"), new NettyServerTransport());
    }

    public static WaveClient client(WaveClientOptions options) {
        var configured = Objects.requireNonNull(options, "options");
        return new DefaultWaveClient(configured, pool -> configured.http2().isEnabled()
                ? new NettyHttp2ClientTransport(pool, configured.proxyPolicy(), configured.http2(), configured.tls())
                : new NettyHttp1ClientTransport(pool, configured.proxyPolicy(), configured.tls()));
    }

    public static WebSocketClient webSocketClient(WebSocketClientOptions options) {
        return new NettyWebSocketClient(Objects.requireNonNull(options, "options"));
    }

    public static SseClient sseClient(SseClientOptions options) {
        return new NettySseClient(Objects.requireNonNull(options, "options"));
    }
}
