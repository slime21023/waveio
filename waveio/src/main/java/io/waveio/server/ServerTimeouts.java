package io.waveio.server;

import java.time.Duration;
import java.util.Objects;

/** Explicit positive timeout values for connection idleness and shutdown. */
public record ServerTimeouts(Duration idleTimeout, Duration shutdownGrace) {
    /** Validates all timeout values. */
    public ServerTimeouts {
        idleTimeout = positive(idleTimeout, "idleTimeout");
        shutdownGrace = positive(shutdownGrace, "shutdownGrace");
    }
    private static Duration positive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) { throw new IllegalArgumentException(name + " must be positive"); }
        return value;
    }
}
