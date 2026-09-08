package io.waveio.netty;

import java.time.Duration;
import java.util.Objects;

/** Explicit HTTP decoder and connection-idle limits for the internal transport. */
record TransportConfig(int maximumInitialLineLength, int maximumHeaderSize, int maximumChunkSize, Duration idleTimeout) {
    TransportConfig {
        if (maximumInitialLineLength < 1 || maximumHeaderSize < 1 || maximumChunkSize < 1) {
            throw new IllegalArgumentException("HTTP limits must be positive");
        }
        Objects.requireNonNull(idleTimeout, "idleTimeout");
        if (idleTimeout.isZero() || idleTimeout.isNegative()) {
            throw new IllegalArgumentException("idleTimeout must be positive");
        }
    }
}
