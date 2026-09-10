package io.wavejava.wave.api.websocket;

import io.wavejava.wave.api.http.CancellationToken;
import io.wavejava.wave.api.http.Deadline;
import io.wavejava.wave.api.http.Headers;
import io.wavejava.wave.api.http.HttpMethod;
import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable direct-{@code ws} client handshake request.
 *
 * <p>The 0.5 client intentionally accepts only a direct {@code ws} authority. Redirects,
 * retries, proxies, {@code wss}, and HTTP/2 WebSocket negotiation remain outside this contract
 * until their 0.7 security and transport gates exist. Framework-owned upgrade headers cannot be
 * supplied by applications; the owned transport builds and validates them exactly once.</p>
 */
public final class WebSocketClientRequest {
    private static final java.util.Set<String> TRANSPORT_OWNED_HEADERS = java.util.Set.of(
            "host",
            "connection",
            "upgrade",
            "content-length",
            "transfer-encoding",
            "keep-alive",
            "proxy-connection",
            "sec-websocket-key",
            "sec-websocket-version",
            "sec-websocket-protocol",
            "sec-websocket-extensions");

    private final URI uri;
    private final Headers headers;
    private final List<String> subprotocols;
    private final Duration timeout;
    private final Deadline deadline;
    private final CancellationToken cancellationToken;

    private WebSocketClientRequest(Builder builder) {
        uri = validateUri(builder.uri);
        headers = validateHeaders(builder.headers);
        subprotocols = WebSocketLimits.validateSubprotocols(builder.subprotocols);
        timeout = builder.timeout;
        deadline = builder.deadline;
        cancellationToken = builder.cancellationToken;
    }

    /** Starts a direct WebSocket handshake request builder. */
    public static Builder builder() {
        return new Builder();
    }

    /** Creates a direct {@code ws} handshake request. */
    public static WebSocketClientRequest of(URI uri) {
        return builder().uri(uri).build();
    }

    /** Returns the absolute direct-{@code ws} target URI. */
    public URI uri() {
        return uri;
    }

    /** Returns immutable application-owned handshake headers. */
    public Headers headers() {
        return headers;
    }

    /** Returns requested subprotocols in preference order. */
    public List<String> subprotocols() {
        return subprotocols;
    }

    /** Returns an optional establishment-time timeout override. */
    public Optional<Duration> timeout() {
        return Optional.ofNullable(timeout);
    }

    /** Returns an optional absolute establishment deadline. */
    public Optional<Deadline> deadline() {
        return Optional.ofNullable(deadline);
    }

    /**
     * Returns an optional connection-lifetime cancellation signal.
     *
     * <p>Before the upgrade it cancels establishment; after a successful upgrade it aborts the
     * owned connection. It never permits a physical admission to be released before channel
     * closure is observable.</p>
     */
    public Optional<CancellationToken> cancellationToken() {
        return Optional.ofNullable(cancellationToken);
    }

    /** Returns a builder populated from this immutable request. */
    public Builder toBuilder() {
        var builder = builder().uri(uri).headers(headers).subprotocols(subprotocols);
        if (timeout != null) {
            builder.timeout(timeout);
        }
        if (deadline != null) {
            builder.deadline(deadline);
        }
        if (cancellationToken != null) {
            builder.cancellationToken(cancellationToken);
        }
        return builder;
    }

    private static URI validateUri(URI uri) {
        Objects.requireNonNull(uri, "uri");
        if (!uri.isAbsolute() || uri.getHost() == null || !"ws".equalsIgnoreCase(uri.getScheme())
                || uri.getRawFragment() != null || uri.getUserInfo() != null || uri.getPort() > 65_535) {
            throw new IllegalArgumentException(
                    "WebSocket client URI must be an absolute direct ws authority without user info or fragment: " + uri);
        }
        return URI.create(uri.toString());
    }

    private static Headers validateHeaders(Headers headers) {
        var snapshot = Objects.requireNonNull(headers, "headers");
        for (var name : snapshot.names()) {
            if (TRANSPORT_OWNED_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("WebSocket handshake header is transport-owned: " + name);
            }
        }
        return snapshot;
    }

    private static Duration requirePositive(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be greater than zero: " + timeout);
        }
        return timeout;
    }

    /** Builder for an immutable {@link WebSocketClientRequest}. */
    public static final class Builder {
        private URI uri;
        private Headers headers = Headers.empty();
        private List<String> subprotocols = List.of();
        private Duration timeout;
        private Deadline deadline;
        private CancellationToken cancellationToken;

        private Builder() {
        }

        /** Sets the absolute direct-{@code ws} URI. */
        public Builder uri(URI uri) {
            this.uri = validateUri(uri);
            return this;
        }

        /** Replaces all application-owned handshake headers. */
        public Builder headers(Headers headers) {
            this.headers = validateHeaders(headers);
            return this;
        }

        /** Replaces all values for one application-owned handshake header. */
        public Builder header(String name, String value) {
            return headers(headers.toBuilder().set(name, value).build());
        }

        /** Appends one application-owned handshake header value. */
        public Builder addHeader(String name, String value) {
            return headers(headers.toBuilder().add(name, value).build());
        }

        /** Sets preferred subprotocol tokens in wire preference order. */
        public Builder subprotocols(List<String> subprotocols) {
            this.subprotocols = WebSocketLimits.validateSubprotocols(subprotocols);
            return this;
        }

        /** Adds one preferred subprotocol after existing preferences. */
        public Builder subprotocol(String subprotocol) {
            var protocols = new LinkedHashSet<>(subprotocols);
            if (!protocols.add(Objects.requireNonNull(subprotocol, "subprotocol"))) {
                throw new IllegalArgumentException("Duplicate WebSocket subprotocol: " + subprotocol);
            }
            return subprotocols(List.copyOf(protocols));
        }

        /** Sets a finite timeout for TCP connection plus HTTP upgrade establishment. */
        public Builder timeout(Duration timeout) {
            this.timeout = requirePositive(timeout);
            return this;
        }

        /** Sets an absolute deadline for TCP connection plus HTTP upgrade establishment. */
        public Builder deadline(Deadline deadline) {
            this.deadline = Objects.requireNonNull(deadline, "deadline");
            return this;
        }

        /** Sets a cancellation token retained for the connection's full lifetime. */
        public Builder cancellationToken(CancellationToken cancellationToken) {
            this.cancellationToken = Objects.requireNonNull(cancellationToken, "cancellationToken");
            return this;
        }

        /** Builds an immutable direct-{@code ws} handshake request. */
        public WebSocketClientRequest build() {
            return new WebSocketClientRequest(this);
        }
    }
}
