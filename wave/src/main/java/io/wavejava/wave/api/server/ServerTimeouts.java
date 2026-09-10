package io.wavejava.wave.api.server;

import java.time.Duration;
import java.util.Objects;

/**
 * Immutable, finite time budgets for one server.
 *
 * <p>None of these values may be {@code null}, zero, negative, or implicitly unbounded. A
 * transport adapter applies them to the matching protocol phase; application code must still set
 * timeouts on its own database, filesystem, and remote-service calls.</p>
 */
public record ServerTimeouts(
        Duration requestTimeout,
        Duration readTimeout,
        Duration writeTimeout,
        Duration idleTimeout,
        Duration shutdownTimeout
) {
    /** Default deadline for one complete request invocation. */
    public static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(30);

    /** Default limit for a stalled inbound read. */
    public static final Duration DEFAULT_READ_TIMEOUT = Duration.ofSeconds(30);

    /** Default limit for a stalled outbound write. */
    public static final Duration DEFAULT_WRITE_TIMEOUT = Duration.ofSeconds(30);

    /** Default limit for an otherwise inactive connection. */
    public static final Duration DEFAULT_IDLE_TIMEOUT = Duration.ofMinutes(1);

    /** Default deadline for graceful server shutdown. */
    public static final Duration DEFAULT_SHUTDOWN_TIMEOUT = Duration.ofSeconds(30);

    private static final ServerTimeouts DEFAULTS = new ServerTimeouts(
            DEFAULT_REQUEST_TIMEOUT,
            DEFAULT_READ_TIMEOUT,
            DEFAULT_WRITE_TIMEOUT,
            DEFAULT_IDLE_TIMEOUT,
            DEFAULT_SHUTDOWN_TIMEOUT);

    /**
     * Validates a complete timeout snapshot.
     *
     * @throws NullPointerException if a timeout is {@code null}
     * @throws IllegalArgumentException if a timeout is zero or negative
     */
    public ServerTimeouts {
        requestTimeout = requirePositive(requestTimeout, "requestTimeout");
        readTimeout = requirePositive(readTimeout, "readTimeout");
        writeTimeout = requirePositive(writeTimeout, "writeTimeout");
        idleTimeout = requirePositive(idleTimeout, "idleTimeout");
        shutdownTimeout = requirePositive(shutdownTimeout, "shutdownTimeout");
    }

    /** Returns the finite timeouts used when the application does not override server budgets. */
    public static ServerTimeouts defaults() {
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

    private static Duration requirePositive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be greater than zero: " + value);
        }
        return value;
    }

    /** Mutable builder for an immutable {@link ServerTimeouts} snapshot. */
    public static final class Builder {
        private Duration requestTimeout = DEFAULT_REQUEST_TIMEOUT;
        private Duration readTimeout = DEFAULT_READ_TIMEOUT;
        private Duration writeTimeout = DEFAULT_WRITE_TIMEOUT;
        private Duration idleTimeout = DEFAULT_IDLE_TIMEOUT;
        private Duration shutdownTimeout = DEFAULT_SHUTDOWN_TIMEOUT;

        private Builder() {
        }

        private Builder(ServerTimeouts timeouts) {
            requestTimeout = timeouts.requestTimeout;
            readTimeout = timeouts.readTimeout;
            writeTimeout = timeouts.writeTimeout;
            idleTimeout = timeouts.idleTimeout;
            shutdownTimeout = timeouts.shutdownTimeout;
        }

        /** Sets the deadline for one complete request invocation. */
        public Builder requestTimeout(Duration requestTimeout) {
            this.requestTimeout = requirePositive(requestTimeout, "requestTimeout");
            return this;
        }

        /** Sets the limit for a stalled inbound read. */
        public Builder readTimeout(Duration readTimeout) {
            this.readTimeout = requirePositive(readTimeout, "readTimeout");
            return this;
        }

        /** Sets the limit for a stalled outbound write. */
        public Builder writeTimeout(Duration writeTimeout) {
            this.writeTimeout = requirePositive(writeTimeout, "writeTimeout");
            return this;
        }

        /** Sets the limit for an otherwise inactive connection. */
        public Builder idleTimeout(Duration idleTimeout) {
            this.idleTimeout = requirePositive(idleTimeout, "idleTimeout");
            return this;
        }

        /** Sets the deadline for graceful server shutdown. */
        public Builder shutdownTimeout(Duration shutdownTimeout) {
            this.shutdownTimeout = requirePositive(shutdownTimeout, "shutdownTimeout");
            return this;
        }

        /** Builds an immutable timeout snapshot. */
        public ServerTimeouts build() {
            return new ServerTimeouts(requestTimeout, readTimeout, writeTimeout, idleTimeout, shutdownTimeout);
        }
    }
}
