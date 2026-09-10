package io.wavejava.wave.api.server;

import java.util.Objects;

/**
 * Immutable HTTP/2 transport and framework-memory limits shared by server and client adapters.
 *
 * <p>Wave 1.0 enables HTTP/2 only over TLS/ALPN; h2c prior knowledge and HTTP/1.1 Upgrade are
 * deliberately unsupported. HTTP/2 flow-control windows regulate peer transmission but do not by
 * themselves bound framework retention, so this configuration carries independent connection and
 * stream byte budgets as well.</p>
 */
public record Http2Config(
        Mode mode,
        int maximumConcurrentStreams,
        int maximumHeaderListBytes,
        int maximumFrameBytes,
        int initialStreamWindowBytes,
        int initialConnectionWindowBytes,
        long maximumInboundBytesPerConnection,
        long maximumOutboundBytesPerConnection,
        long maximumOutboundBytesPerStream) {
    /** Negotiation behavior for an HTTP/2-capable endpoint. */
    public enum Mode {
        /** Do not advertise or accept HTTP/2. */
        DISABLED,
        /** Prefer HTTP/2 via ALPN but retain an HTTP/1.1 fallback. */
        PREFER,
        /** Require successful HTTP/2 ALPN negotiation. */
        REQUIRE
    }

    private static final int DEFAULT_MAXIMUM_CONCURRENT_STREAMS = 32;
    private static final int DEFAULT_MAXIMUM_HEADER_LIST_BYTES = 16 * 1024;
    private static final int DEFAULT_MAXIMUM_FRAME_BYTES = 16 * 1024;
    private static final int DEFAULT_INITIAL_STREAM_WINDOW_BYTES = 64 * 1024;
    private static final int DEFAULT_INITIAL_CONNECTION_WINDOW_BYTES = 1024 * 1024;
    /** RFC 7540's fixed initial connection receive window; HTTP/2 has no setting to lower it. */
    public static final int MINIMUM_INITIAL_CONNECTION_WINDOW_BYTES = 65_535;
    private static final long DEFAULT_MAXIMUM_INBOUND_BYTES_PER_CONNECTION = 32L * 1024 * 1024;
    private static final long DEFAULT_MAXIMUM_OUTBOUND_BYTES_PER_CONNECTION = 4L * 1024 * 1024;
    private static final long DEFAULT_MAXIMUM_OUTBOUND_BYTES_PER_STREAM = 1024L * 1024;

    /** Returns a finite configuration that disables HTTP/2. */
    public static Http2Config disabled() {
        return new Http2Config(
                Mode.DISABLED,
                DEFAULT_MAXIMUM_CONCURRENT_STREAMS,
                DEFAULT_MAXIMUM_HEADER_LIST_BYTES,
                DEFAULT_MAXIMUM_FRAME_BYTES,
                DEFAULT_INITIAL_STREAM_WINDOW_BYTES,
                DEFAULT_INITIAL_CONNECTION_WINDOW_BYTES,
                DEFAULT_MAXIMUM_INBOUND_BYTES_PER_CONNECTION,
                DEFAULT_MAXIMUM_OUTBOUND_BYTES_PER_CONNECTION,
                DEFAULT_MAXIMUM_OUTBOUND_BYTES_PER_STREAM);
    }

    /** Starts an HTTP/2-enabled builder with safe finite defaults and {@link Mode#PREFER}. */
    public static Builder builder() {
        return new Builder();
    }

    public Http2Config {
        mode = Objects.requireNonNull(mode, "mode");
        maximumConcurrentStreams = requirePositive(maximumConcurrentStreams, "maximumConcurrentStreams");
        maximumHeaderListBytes = requirePositive(maximumHeaderListBytes, "maximumHeaderListBytes");
        if (maximumFrameBytes < 16 * 1024 || maximumFrameBytes > 16_777_215) {
            throw new IllegalArgumentException("maximumFrameBytes must be between 16384 and 16777215: "
                    + maximumFrameBytes);
        }
        initialStreamWindowBytes = requirePositive(initialStreamWindowBytes, "initialStreamWindowBytes");
        initialConnectionWindowBytes = requirePositive(initialConnectionWindowBytes, "initialConnectionWindowBytes");
        if (initialConnectionWindowBytes < MINIMUM_INITIAL_CONNECTION_WINDOW_BYTES) {
            throw new IllegalArgumentException("initialConnectionWindowBytes cannot be below the HTTP/2 default "
                    + "connection window of " + MINIMUM_INITIAL_CONNECTION_WINDOW_BYTES);
        }
        maximumInboundBytesPerConnection = requirePositive(
                maximumInboundBytesPerConnection, "maximumInboundBytesPerConnection");
        maximumOutboundBytesPerConnection = requirePositive(
                maximumOutboundBytesPerConnection, "maximumOutboundBytesPerConnection");
        maximumOutboundBytesPerStream = requirePositive(
                maximumOutboundBytesPerStream, "maximumOutboundBytesPerStream");
        if (maximumOutboundBytesPerStream > maximumOutboundBytesPerConnection) {
            throw new IllegalArgumentException(
                    "maximumOutboundBytesPerStream must not exceed maximumOutboundBytesPerConnection");
        }
        if (initialConnectionWindowBytes > maximumInboundBytesPerConnection) {
            throw new IllegalArgumentException(
                    "initialConnectionWindowBytes must not exceed maximumInboundBytesPerConnection");
        }
        if (initialStreamWindowBytes > maximumInboundBytesPerConnection / maximumConcurrentStreams) {
            throw new IllegalArgumentException(
                    "initialStreamWindowBytes must fit inside the per-stream inbound connection partition");
        }
    }

    /** Returns whether HTTP/2 is negotiated or required. */
    public boolean isEnabled() {
        return mode != Mode.DISABLED;
    }

    /** Returns whether an ALPN peer that selects HTTP/1.1 must be rejected. */
    public boolean requiresHttp2() {
        return mode == Mode.REQUIRE;
    }

    private static int requirePositive(int value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be greater than zero: " + value);
        }
        return value;
    }

    private static long requirePositive(long value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be greater than zero: " + value);
        }
        return value;
    }

    /** Mutable builder for an immutable HTTP/2 limit snapshot. */
    public static final class Builder {
        private Mode mode = Mode.PREFER;
        private int maximumConcurrentStreams = DEFAULT_MAXIMUM_CONCURRENT_STREAMS;
        private int maximumHeaderListBytes = DEFAULT_MAXIMUM_HEADER_LIST_BYTES;
        private int maximumFrameBytes = DEFAULT_MAXIMUM_FRAME_BYTES;
        private int initialStreamWindowBytes = DEFAULT_INITIAL_STREAM_WINDOW_BYTES;
        private int initialConnectionWindowBytes = DEFAULT_INITIAL_CONNECTION_WINDOW_BYTES;
        private long maximumInboundBytesPerConnection = DEFAULT_MAXIMUM_INBOUND_BYTES_PER_CONNECTION;
        private long maximumOutboundBytesPerConnection = DEFAULT_MAXIMUM_OUTBOUND_BYTES_PER_CONNECTION;
        private long maximumOutboundBytesPerStream = DEFAULT_MAXIMUM_OUTBOUND_BYTES_PER_STREAM;

        private Builder() {
        }

        /** Sets ALPN negotiation behavior. */
        public Builder mode(Mode mode) {
            this.mode = Objects.requireNonNull(mode, "mode");
            return this;
        }

        /** Sets the advertised/admitted maximum concurrent peer streams. */
        public Builder maximumConcurrentStreams(int maximumConcurrentStreams) {
            this.maximumConcurrentStreams = requirePositive(maximumConcurrentStreams, "maximumConcurrentStreams");
            return this;
        }

        /** Sets the HTTP/2 header-list byte limit. */
        public Builder maximumHeaderListBytes(int maximumHeaderListBytes) {
            this.maximumHeaderListBytes = requirePositive(maximumHeaderListBytes, "maximumHeaderListBytes");
            return this;
        }

        /** Sets the RFC 7540 maximum frame payload size. */
        public Builder maximumFrameBytes(int maximumFrameBytes) {
            this.maximumFrameBytes = maximumFrameBytes;
            return this;
        }

        /** Sets the per-stream HTTP/2 flow-control window. */
        public Builder initialStreamWindowBytes(int initialStreamWindowBytes) {
            this.initialStreamWindowBytes = requirePositive(initialStreamWindowBytes, "initialStreamWindowBytes");
            return this;
        }

        /** Sets Wave's connection-level memory/admission window. */
        public Builder initialConnectionWindowBytes(int initialConnectionWindowBytes) {
            this.initialConnectionWindowBytes = requirePositive(
                    initialConnectionWindowBytes, "initialConnectionWindowBytes");
            return this;
        }

        /** Sets the total framework-owned inbound-body reservation per HTTP/2 connection. */
        public Builder maximumInboundBytesPerConnection(long maximumInboundBytesPerConnection) {
            this.maximumInboundBytesPerConnection = requirePositive(
                    maximumInboundBytesPerConnection, "maximumInboundBytesPerConnection");
            return this;
        }

        /** Sets the total framework-owned outbound-byte reservation per HTTP/2 connection. */
        public Builder maximumOutboundBytesPerConnection(long maximumOutboundBytesPerConnection) {
            this.maximumOutboundBytesPerConnection = requirePositive(
                    maximumOutboundBytesPerConnection, "maximumOutboundBytesPerConnection");
            return this;
        }

        /** Sets the framework-owned outbound-byte reservation per HTTP/2 stream. */
        public Builder maximumOutboundBytesPerStream(long maximumOutboundBytesPerStream) {
            this.maximumOutboundBytesPerStream = requirePositive(
                    maximumOutboundBytesPerStream, "maximumOutboundBytesPerStream");
            return this;
        }

        /** Builds the immutable configuration. */
        public Http2Config build() {
            return new Http2Config(
                    mode,
                    maximumConcurrentStreams,
                    maximumHeaderListBytes,
                    maximumFrameBytes,
                    initialStreamWindowBytes,
                    initialConnectionWindowBytes,
                    maximumInboundBytesPerConnection,
                    maximumOutboundBytesPerConnection,
                    maximumOutboundBytesPerStream);
        }
    }
}
