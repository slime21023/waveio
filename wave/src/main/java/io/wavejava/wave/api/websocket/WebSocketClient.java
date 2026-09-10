package io.wavejava.wave.api.websocket;

import io.wavejava.wave.netty.WebSocketClientTransport;
import io.wavejava.wave.netty.WebSocketClientConfig;
import java.net.URI;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Bounded owned client for direct {@code ws} connections.
 *
 * <p>Each accepted connection occupies one finite admission until its physical channel close is
 * observed. This client deliberately does not queue connection attempts: a full admission limit
 * fails immediately, avoiding hidden retention of long-lived work. The 0.5 transport supports
 * direct {@code ws} only; use of redirect, retry, proxy, {@code wss}, or HTTP/2 WebSocket is
 * rejected rather than silently falling back to an unverified route.</p>
 */
public final class WebSocketClient implements AutoCloseable {
    private final WebSocketClientTransport transport;
    private final AtomicBoolean closed = new AtomicBoolean();

    private WebSocketClient(Builder builder) {
        transport = WebSocketClientTransport.netty(new WebSocketClientConfig(
                builder.limits,
                builder.maximumConnections,
                builder.maximumResponseHeaders,
                builder.maximumResponseHeaderBytes,
                builder.connectTimeout,
                builder.handshakeTimeout,
                builder.idleTimeout));
    }

    /** Starts a builder with finite direct-connection limits. */
    public static Builder builder() {
        return new Builder();
    }

    /** Builds a direct {@code ws} request with the URI already selected. */
    public WebSocketClientRequest.Builder request(URI uri) {
        return WebSocketClientRequest.builder().uri(uri);
    }

    /** Opens one direct WebSocket connection asynchronously. */
    public CompletionStage<WebSocketConnection> connect(WebSocketClientRequest request) {
        Objects.requireNonNull(request, "request");
        if (closed.get()) {
            return java.util.concurrent.CompletableFuture.failedFuture(new IllegalStateException("WebSocketClient is closed"));
        }
        return transport.connect(request);
    }

    /** Returns whether this client has stopped accepting new direct connections. */
    public boolean isClosed() {
        return closed.get();
    }

    /**
     * Aborts active connections and releases the owned event-loop and callback resources.
     *
     * <p>Each connection's own admission is still released only from its channel-close callback;
     * this method never treats logical cancellation as proof that the socket has disappeared.</p>
     */
    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            transport.close();
        }
    }

    /** Builder for a bounded direct-{@code ws} {@link WebSocketClient}. */
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

        /** Sets bounded frame, message, close, and outbound-byte limits for every connection. */
        public Builder limits(WebSocketLimits limits) {
            this.limits = Objects.requireNonNull(limits, "limits");
            return this;
        }

        /** Sets the finite count of live or establishing direct connections; attempts never queue. */
        public Builder maximumConnections(int maximumConnections) {
            this.maximumConnections = requirePositive(maximumConnections, "maximumConnections");
            return this;
        }

        /** Sets the maximum number of response header fields retained during an HTTP upgrade. */
        public Builder maximumResponseHeaders(int maximumResponseHeaders) {
            this.maximumResponseHeaders = requirePositive(maximumResponseHeaders, "maximumResponseHeaders");
            return this;
        }

        /** Sets the maximum raw response-header byte budget enforced by the HTTP decoder. */
        public Builder maximumResponseHeaderBytes(int maximumResponseHeaderBytes) {
            this.maximumResponseHeaderBytes = requirePositive(
                    maximumResponseHeaderBytes, "maximumResponseHeaderBytes");
            return this;
        }

        /** Sets the finite TCP connect timeout. */
        public Builder connectTimeout(Duration connectTimeout) {
            this.connectTimeout = requirePositive(connectTimeout, "connectTimeout");
            return this;
        }

        /** Sets the finite total HTTP upgrade establishment timeout. */
        public Builder handshakeTimeout(Duration handshakeTimeout) {
            this.handshakeTimeout = requirePositive(handshakeTimeout, "handshakeTimeout");
            return this;
        }

        /** Sets the all-idle heartbeat cadence for an established connection. */
        public Builder idleTimeout(Duration idleTimeout) {
            this.idleTimeout = requirePositive(idleTimeout, "idleTimeout");
            return this;
        }

        /** Builds an independently owned bounded direct-{@code ws} client. */
        public WebSocketClient build() {
            return new WebSocketClient(this);
        }

        private static int requirePositive(int value, String name) {
            if (value <= 0) {
                throw new IllegalArgumentException(name + " must be greater than zero: " + value);
            }
            return value;
        }

        private static Duration requirePositive(Duration value, String name) {
            Objects.requireNonNull(value, name);
            if (value.isZero() || value.isNegative()) {
                throw new IllegalArgumentException(name + " must be greater than zero: " + value);
            }
            return value;
        }
    }
}
