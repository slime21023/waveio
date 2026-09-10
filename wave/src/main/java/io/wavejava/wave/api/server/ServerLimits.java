package io.wavejava.wave.api.server;

/**
 * Immutable, finite resource budgets for one server.
 *
 * <p>Every value is strictly positive. The defaults deliberately bound connections, in-flight
 * work, HTTP decoding, and HTTP/1.1 response sequencing so a server never acquires an unbounded
 * queue by omission. Byte budgets are {@code int} values because the HTTP/1.1 decoder and
 * aggregated buffers cannot represent a larger single allocation.</p>
 */
public record ServerLimits(
        int maximumConnections,
        int maximumInFlightRequests,
        int maximumRequestLineBytes,
        int maximumRequestHeaderBytes,
        int maximumRequestBodyBytes,
        int maximumPendingRequestsPerConnection,
        int maximumPendingResponseBytesPerConnection,
        int maximumOutboundStreamBytesPerConnection
) {
    /** Default maximum number of simultaneously open transport connections. */
    public static final int DEFAULT_MAXIMUM_CONNECTIONS = 10_000;

    /** Default maximum number of requests admitted to application invocation. */
    public static final int DEFAULT_MAXIMUM_IN_FLIGHT_REQUESTS = 10_000;

    /** Default maximum HTTP request-line size in bytes. */
    public static final int DEFAULT_MAXIMUM_REQUEST_LINE_BYTES = 8 * 1024;

    /** Default maximum HTTP request-header size in bytes. */
    public static final int DEFAULT_MAXIMUM_REQUEST_HEADER_BYTES = 16 * 1024;

    /** Default maximum aggregated request-body size in bytes. */
    public static final int DEFAULT_MAXIMUM_REQUEST_BODY_BYTES = 1024 * 1024;

    /** Default maximum number of queued HTTP/1.1 requests per connection. */
    public static final int DEFAULT_MAXIMUM_PENDING_REQUESTS_PER_CONNECTION = 16;

    /** Default maximum bytes queued behind an HTTP/1.1 response sequence per connection. */
    public static final int DEFAULT_MAXIMUM_PENDING_RESPONSE_BYTES_PER_CONNECTION = 1024 * 1024;

    /** Default maximum outbound bytes retained for one active HTTP response stream. */
    public static final int DEFAULT_MAXIMUM_OUTBOUND_STREAM_BYTES_PER_CONNECTION = 1024 * 1024;

    private static final ServerLimits DEFAULTS = new ServerLimits(
            DEFAULT_MAXIMUM_CONNECTIONS,
            DEFAULT_MAXIMUM_IN_FLIGHT_REQUESTS,
            DEFAULT_MAXIMUM_REQUEST_LINE_BYTES,
            DEFAULT_MAXIMUM_REQUEST_HEADER_BYTES,
            DEFAULT_MAXIMUM_REQUEST_BODY_BYTES,
            DEFAULT_MAXIMUM_PENDING_REQUESTS_PER_CONNECTION,
            DEFAULT_MAXIMUM_PENDING_RESPONSE_BYTES_PER_CONNECTION,
            DEFAULT_MAXIMUM_OUTBOUND_STREAM_BYTES_PER_CONNECTION);

    /**
     * Compatibility constructor for callers using the 0.2/0.3 limit snapshot.
     *
     * <p>New callers should use {@link Builder} to set the separate streaming outbound budget.</p>
     */
    public ServerLimits(
            int maximumConnections,
            int maximumInFlightRequests,
            int maximumRequestLineBytes,
            int maximumRequestHeaderBytes,
            int maximumRequestBodyBytes,
            int maximumPendingRequestsPerConnection,
            int maximumPendingResponseBytesPerConnection) {
        this(
                maximumConnections,
                maximumInFlightRequests,
                maximumRequestLineBytes,
                maximumRequestHeaderBytes,
                maximumRequestBodyBytes,
                maximumPendingRequestsPerConnection,
                maximumPendingResponseBytesPerConnection,
                DEFAULT_MAXIMUM_OUTBOUND_STREAM_BYTES_PER_CONNECTION);
    }

    /**
     * Validates a complete limits snapshot.
     *
     * @throws IllegalArgumentException when any budget is zero or negative
     */
    public ServerLimits {
        maximumConnections = requirePositive(maximumConnections, "maximumConnections");
        maximumInFlightRequests = requirePositive(maximumInFlightRequests, "maximumInFlightRequests");
        maximumRequestLineBytes = requirePositive(maximumRequestLineBytes, "maximumRequestLineBytes");
        maximumRequestHeaderBytes = requirePositive(maximumRequestHeaderBytes, "maximumRequestHeaderBytes");
        maximumRequestBodyBytes = requirePositive(maximumRequestBodyBytes, "maximumRequestBodyBytes");
        maximumPendingRequestsPerConnection = requirePositive(
                maximumPendingRequestsPerConnection, "maximumPendingRequestsPerConnection");
        maximumPendingResponseBytesPerConnection = requirePositive(
                maximumPendingResponseBytesPerConnection, "maximumPendingResponseBytesPerConnection");
        maximumOutboundStreamBytesPerConnection = requirePositive(
                maximumOutboundStreamBytesPerConnection, "maximumOutboundStreamBytesPerConnection");
    }

    /** Returns the finite limits used when the application does not override server budgets. */
    public static ServerLimits defaults() {
        return DEFAULTS;
    }

    /** Returns a builder initialized with the documented defaults. */
    public static Builder builder() {
        return new Builder();
    }

    /** Returns a builder initialized with this snapshot's values. */
    public Builder toBuilder() {
        return new Builder(this);
    }

    /** Alias describing the same per-connection Flow outbound budget. */
    public int maximumStreamingResponseBytesPerConnection() {
        return maximumOutboundStreamBytesPerConnection;
    }

    private static int requirePositive(int value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be greater than zero: " + value);
        }
        return value;
    }

    /** Mutable builder for an immutable {@link ServerLimits} snapshot. */
    public static final class Builder {
        private int maximumConnections = DEFAULT_MAXIMUM_CONNECTIONS;
        private int maximumInFlightRequests = DEFAULT_MAXIMUM_IN_FLIGHT_REQUESTS;
        private int maximumRequestLineBytes = DEFAULT_MAXIMUM_REQUEST_LINE_BYTES;
        private int maximumRequestHeaderBytes = DEFAULT_MAXIMUM_REQUEST_HEADER_BYTES;
        private int maximumRequestBodyBytes = DEFAULT_MAXIMUM_REQUEST_BODY_BYTES;
        private int maximumPendingRequestsPerConnection = DEFAULT_MAXIMUM_PENDING_REQUESTS_PER_CONNECTION;
        private int maximumPendingResponseBytesPerConnection = DEFAULT_MAXIMUM_PENDING_RESPONSE_BYTES_PER_CONNECTION;
        private int maximumOutboundStreamBytesPerConnection = DEFAULT_MAXIMUM_OUTBOUND_STREAM_BYTES_PER_CONNECTION;

        private Builder() {
        }

        private Builder(ServerLimits limits) {
            maximumConnections = limits.maximumConnections;
            maximumInFlightRequests = limits.maximumInFlightRequests;
            maximumRequestLineBytes = limits.maximumRequestLineBytes;
            maximumRequestHeaderBytes = limits.maximumRequestHeaderBytes;
            maximumRequestBodyBytes = limits.maximumRequestBodyBytes;
            maximumPendingRequestsPerConnection = limits.maximumPendingRequestsPerConnection;
            maximumPendingResponseBytesPerConnection = limits.maximumPendingResponseBytesPerConnection;
            maximumOutboundStreamBytesPerConnection = limits.maximumOutboundStreamBytesPerConnection;
        }

        /** Sets the maximum number of simultaneously open transport connections. */
        public Builder maximumConnections(int maximumConnections) {
            this.maximumConnections = requirePositive(maximumConnections, "maximumConnections");
            return this;
        }

        /** Sets the maximum number of requests admitted to application invocation. */
        public Builder maximumInFlightRequests(int maximumInFlightRequests) {
            this.maximumInFlightRequests = requirePositive(maximumInFlightRequests, "maximumInFlightRequests");
            return this;
        }

        /** Sets the maximum HTTP request-line size in bytes. */
        public Builder maximumRequestLineBytes(int maximumRequestLineBytes) {
            this.maximumRequestLineBytes = requirePositive(maximumRequestLineBytes, "maximumRequestLineBytes");
            return this;
        }

        /** Sets the maximum HTTP request-header size in bytes. */
        public Builder maximumRequestHeaderBytes(int maximumRequestHeaderBytes) {
            this.maximumRequestHeaderBytes = requirePositive(maximumRequestHeaderBytes, "maximumRequestHeaderBytes");
            return this;
        }

        /** Sets the maximum aggregated request-body size in bytes. */
        public Builder maximumRequestBodyBytes(int maximumRequestBodyBytes) {
            this.maximumRequestBodyBytes = requirePositive(maximumRequestBodyBytes, "maximumRequestBodyBytes");
            return this;
        }

        /** Sets the maximum number of queued HTTP/1.1 requests per connection. */
        public Builder maximumPendingRequestsPerConnection(int maximumPendingRequestsPerConnection) {
            this.maximumPendingRequestsPerConnection = requirePositive(
                    maximumPendingRequestsPerConnection, "maximumPendingRequestsPerConnection");
            return this;
        }

        /** Sets the maximum bytes queued behind an HTTP/1.1 response sequence per connection. */
        public Builder maximumPendingResponseBytesPerConnection(int maximumPendingResponseBytesPerConnection) {
            this.maximumPendingResponseBytesPerConnection = requirePositive(
                    maximumPendingResponseBytesPerConnection, "maximumPendingResponseBytesPerConnection");
            return this;
        }

        /** Sets the maximum outbound bytes retained for one active HTTP response stream. */
        public Builder maximumOutboundStreamBytesPerConnection(int maximumOutboundStreamBytesPerConnection) {
            this.maximumOutboundStreamBytesPerConnection = requirePositive(
                    maximumOutboundStreamBytesPerConnection, "maximumOutboundStreamBytesPerConnection");
            return this;
        }

        /** Alias for {@link #maximumOutboundStreamBytesPerConnection(int)}. */
        public Builder maximumStreamingResponseBytesPerConnection(int maximumStreamingResponseBytesPerConnection) {
            return maximumOutboundStreamBytesPerConnection(maximumStreamingResponseBytesPerConnection);
        }

        /** Builds an immutable limits snapshot. */
        public ServerLimits build() {
            return new ServerLimits(
                    maximumConnections,
                    maximumInFlightRequests,
                    maximumRequestLineBytes,
                    maximumRequestHeaderBytes,
                    maximumRequestBodyBytes,
                    maximumPendingRequestsPerConnection,
                    maximumPendingResponseBytesPerConnection,
                    maximumOutboundStreamBytesPerConnection);
        }
    }
}
