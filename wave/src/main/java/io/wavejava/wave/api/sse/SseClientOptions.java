package io.wavejava.wave.api.sse;

import java.time.Duration;
import java.util.Objects;

/** Immutable limits and timeouts for a {@link SseClient}. */
public record SseClientOptions(
        /** TCP connect deadline; the Netty transport uses at least one millisecond and clamps larger values. */
        Duration connectTimeout,
        Duration responseOpenTimeout,
        Duration idleTimeout,
        Duration initialReconnectDelay,
        int maximumReconnectAttempts,
        int maximumHeaderBytes,
        int maximumHeaderCount,
        int maximumLineBytes,
        int maximumEventBytes,
        int maximumConnections,
        Duration maximumReconnectDelay,
        String initialLastEventId) {
    /** Default maximum bytes in the response status line and all response headers. */
    public static final int DEFAULT_MAXIMUM_HEADER_BYTES = 16 * 1024;
    /** Default maximum response header field count. */
    public static final int DEFAULT_MAXIMUM_HEADER_COUNT = 100;
    /** Default bound for one UTF-8 event-stream line. */
    public static final int DEFAULT_MAXIMUM_LINE_BYTES = 64 * 1024;
    /** Default bound for one accumulated event block. */
    public static final int DEFAULT_MAXIMUM_EVENT_BYTES = 256 * 1024;
    /** Default finite budget for reconnect attempts after the first connection. */
    public static final int DEFAULT_MAXIMUM_RECONNECT_ATTEMPTS = 1_000;
    /** Default cap for live or reconnecting subscriptions. */
    public static final int DEFAULT_MAXIMUM_CONNECTIONS = 1_000;
    /** Default cap for a server-provided reconnect delay. */
    public static final Duration DEFAULT_MAXIMUM_RECONNECT_DELAY = Duration.ofMinutes(5);
    /** Default response-head deadline. */
    public static final Duration DEFAULT_RESPONSE_OPEN_TIMEOUT = Duration.ofSeconds(15);
    /** Default silence deadline after a stream opens. */
    public static final Duration DEFAULT_IDLE_TIMEOUT = Duration.ofMinutes(2);

    /** Validates all client limits. */
    public SseClientOptions {
        connectTimeout = positive(connectTimeout, "connectTimeout");
        responseOpenTimeout = positive(responseOpenTimeout, "responseOpenTimeout");
        idleTimeout = positive(idleTimeout, "idleTimeout");
        initialReconnectDelay = nonNegative(initialReconnectDelay, "initialReconnectDelay");
        maximumReconnectAttempts = nonNegative(maximumReconnectAttempts, "maximumReconnectAttempts");
        maximumHeaderBytes = positive(maximumHeaderBytes, "maximumHeaderBytes");
        maximumHeaderCount = positive(maximumHeaderCount, "maximumHeaderCount");
        maximumLineBytes = positive(maximumLineBytes, "maximumLineBytes");
        maximumEventBytes = positive(maximumEventBytes, "maximumEventBytes");
        maximumConnections = positive(maximumConnections, "maximumConnections");
        maximumReconnectDelay = nonNegative(maximumReconnectDelay, "maximumReconnectDelay");
        if (initialLastEventId != null && !safeHeaderValue(initialLastEventId)) {
            throw new IllegalArgumentException("initialLastEventId must be safe for an HTTP header");
        }
    }

    /** Starts with the documented bounded defaults. */
    public static Builder builder() {
        return new Builder();
    }

    /** Builder for {@link SseClientOptions}. */
    public static final class Builder {
        private Duration connectTimeout = Duration.ofSeconds(10);
        private Duration responseOpenTimeout = DEFAULT_RESPONSE_OPEN_TIMEOUT;
        private Duration idleTimeout = DEFAULT_IDLE_TIMEOUT;
        private Duration initialReconnectDelay = Duration.ofSeconds(1);
        private int maximumReconnectAttempts = DEFAULT_MAXIMUM_RECONNECT_ATTEMPTS;
        private int maximumHeaderBytes = DEFAULT_MAXIMUM_HEADER_BYTES;
        private int maximumHeaderCount = DEFAULT_MAXIMUM_HEADER_COUNT;
        private int maximumLineBytes = DEFAULT_MAXIMUM_LINE_BYTES;
        private int maximumEventBytes = DEFAULT_MAXIMUM_EVENT_BYTES;
        private int maximumConnections = DEFAULT_MAXIMUM_CONNECTIONS;
        private Duration maximumReconnectDelay = DEFAULT_MAXIMUM_RECONNECT_DELAY;
        private String initialLastEventId;

        private Builder() {
        }

        public Builder connectTimeout(Duration value) { connectTimeout = positive(value, "connectTimeout"); return this; }
        public Builder responseOpenTimeout(Duration value) { responseOpenTimeout = positive(value, "responseOpenTimeout"); return this; }
        public Builder idleTimeout(Duration value) { idleTimeout = positive(value, "idleTimeout"); return this; }
        public Builder initialReconnectDelay(Duration value) { initialReconnectDelay = nonNegative(value, "initialReconnectDelay"); return this; }
        public Builder maximumReconnectAttempts(int value) { maximumReconnectAttempts = nonNegative(value, "maximumReconnectAttempts"); return this; }
        public Builder maximumHeaderBytes(int value) { maximumHeaderBytes = positive(value, "maximumHeaderBytes"); return this; }
        public Builder maximumHeaderCount(int value) { maximumHeaderCount = positive(value, "maximumHeaderCount"); return this; }
        public Builder maximumLineBytes(int value) { maximumLineBytes = positive(value, "maximumLineBytes"); return this; }
        public Builder maximumEventBytes(int value) { maximumEventBytes = positive(value, "maximumEventBytes"); return this; }
        public Builder maximumConnections(int value) { maximumConnections = positive(value, "maximumConnections"); return this; }
        public Builder maximumReconnectDelay(Duration value) { maximumReconnectDelay = nonNegative(value, "maximumReconnectDelay"); return this; }

        public Builder initialLastEventId(String value) {
            initialLastEventId = Objects.requireNonNull(value, "initialLastEventId");
            if (!safeHeaderValue(initialLastEventId)) {
                throw new IllegalArgumentException("initialLastEventId must be safe for an HTTP header");
            }
            return this;
        }

        /** Builds immutable client options. */
        public SseClientOptions build() {
            return new SseClientOptions(
                    connectTimeout, responseOpenTimeout, idleTimeout, initialReconnectDelay,
                    maximumReconnectAttempts, maximumHeaderBytes, maximumHeaderCount,
                    maximumLineBytes, maximumEventBytes, maximumConnections,
                    maximumReconnectDelay, initialLastEventId);
        }
    }

    private static int positive(int value, String name) {
        if (value <= 0) throw new IllegalArgumentException(name + " must be greater than zero: " + value);
        return value;
    }

    private static int nonNegative(int value, String name) {
        if (value < 0) throw new IllegalArgumentException(name + " must not be negative");
        return value;
    }

    private static Duration positive(Duration value, String name) {
        var result = Objects.requireNonNull(value, name);
        if (result.isZero() || result.isNegative()) throw new IllegalArgumentException(name + " must be greater than zero");
        return result;
    }

    private static Duration nonNegative(Duration value, String name) {
        var result = Objects.requireNonNull(value, name);
        if (result.isNegative()) throw new IllegalArgumentException(name + " must not be negative");
        return result;
    }

    private static boolean safeHeaderValue(String value) {
        for (var index = 0; index < value.length(); index++) {
            var character = value.charAt(index);
            if (character == '\r' || character == '\n' || character == 0 || character == 0x7f
                    || (character < 0x20 && character != '\t')) return false;
        }
        return true;
    }
}
