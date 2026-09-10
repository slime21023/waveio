package io.wavejava.wave.api.websocket;

import java.time.Duration;
import java.util.Objects;

/** Immutable limits and timeouts used to create a direct WebSocket client. */
public record WebSocketClientOptions(
        WebSocketLimits limits,
        int maximumConnections,
        int maximumResponseHeaders,
        int maximumResponseHeaderBytes,
        Duration connectTimeout,
        Duration handshakeTimeout,
        Duration idleTimeout
) {
    public WebSocketClientOptions {
        limits = Objects.requireNonNull(limits, "limits");
        if (maximumConnections <= 0 || maximumResponseHeaders <= 0 || maximumResponseHeaderBytes <= 0) {
            throw new IllegalArgumentException("WebSocket client numeric limits must be greater than zero");
        }
        connectTimeout = requirePositive(connectTimeout, "connectTimeout");
        handshakeTimeout = requirePositive(handshakeTimeout, "handshakeTimeout");
        idleTimeout = requirePositive(idleTimeout, "idleTimeout");
    }

    public static Builder builder() {
        return new Builder();
    }

    private static Duration requirePositive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be greater than zero: " + value);
        }
        return value;
    }

    /** Builder for direct-WebSocket client options. */
    public static final class Builder {
        private WebSocketLimits limits = WebSocketLimits.defaults();
        private int maximumConnections = 64;
        private int maximumResponseHeaders = 128;
        private int maximumResponseHeaderBytes = 64 * 1024;
        private Duration connectTimeout = Duration.ofSeconds(5);
        private Duration handshakeTimeout = Duration.ofSeconds(10);
        private Duration idleTimeout = Duration.ofSeconds(30);

        private Builder() {
        }

        public Builder limits(WebSocketLimits limits) {
            this.limits = Objects.requireNonNull(limits, "limits");
            return this;
        }

        public Builder maximumConnections(int maximumConnections) {
            this.maximumConnections = requirePositive(maximumConnections, "maximumConnections");
            return this;
        }

        public Builder maximumResponseHeaders(int maximumResponseHeaders) {
            this.maximumResponseHeaders = requirePositive(maximumResponseHeaders, "maximumResponseHeaders");
            return this;
        }

        public Builder maximumResponseHeaderBytes(int maximumResponseHeaderBytes) {
            this.maximumResponseHeaderBytes = requirePositive(maximumResponseHeaderBytes, "maximumResponseHeaderBytes");
            return this;
        }

        public Builder connectTimeout(Duration connectTimeout) {
            this.connectTimeout = requirePositiveDuration(connectTimeout, "connectTimeout");
            return this;
        }

        public Builder handshakeTimeout(Duration handshakeTimeout) {
            this.handshakeTimeout = requirePositiveDuration(handshakeTimeout, "handshakeTimeout");
            return this;
        }

        public Builder idleTimeout(Duration idleTimeout) {
            this.idleTimeout = requirePositiveDuration(idleTimeout, "idleTimeout");
            return this;
        }

        public WebSocketClientOptions build() {
            return new WebSocketClientOptions(
                    limits, maximumConnections, maximumResponseHeaders, maximumResponseHeaderBytes,
                    connectTimeout, handshakeTimeout, idleTimeout);
        }

        private static int requirePositive(int value, String name) {
            if (value <= 0) throw new IllegalArgumentException(name + " must be greater than zero: " + value);
            return value;
        }

        private static Duration requirePositiveDuration(Duration value, String name) {
            return WebSocketClientOptions.requirePositive(value, name);
        }
    }
}
