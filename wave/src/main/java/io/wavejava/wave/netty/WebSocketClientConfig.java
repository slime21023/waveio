package io.wavejava.wave.netty;

import io.wavejava.wave.api.websocket.WebSocketLimits;
import java.time.Duration;
import java.util.Objects;

/** Internal immutable configuration passed from the public WebSocket client to Netty. */
public record WebSocketClientConfig(
        WebSocketLimits limits,
        int maximumConnections,
        int maximumResponseHeaders,
        int maximumResponseHeaderBytes,
        Duration connectTimeout,
        Duration handshakeTimeout,
        Duration idleTimeout
) {
    public WebSocketClientConfig {
        limits = Objects.requireNonNull(limits, "limits");
        if (maximumConnections <= 0 || maximumResponseHeaders <= 0 || maximumResponseHeaderBytes <= 0) {
            throw new IllegalArgumentException("WebSocket client numeric limits must be greater than zero");
        }
        connectTimeout = requirePositive(connectTimeout, "connectTimeout");
        handshakeTimeout = requirePositive(handshakeTimeout, "handshakeTimeout");
        idleTimeout = requirePositive(idleTimeout, "idleTimeout");
    }

    private static Duration requirePositive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be greater than zero: " + value);
        }
        return value;
    }
}
