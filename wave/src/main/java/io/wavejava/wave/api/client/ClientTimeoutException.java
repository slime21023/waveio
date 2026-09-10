package io.wavejava.wave.api.client;

import java.time.Duration;
import java.util.Objects;

/** Thrown when the finite end-to-end time budget of a client exchange expires. */
public final class ClientTimeoutException extends RuntimeException {
    private final Duration timeout;

    public ClientTimeoutException(Duration timeout, Throwable cause) {
        super("Client exchange exceeded its timeout of " + Objects.requireNonNull(timeout, "timeout"), cause);
        this.timeout = timeout;
    }

    /** Returns the effective timeout budget for the logical exchange. */
    public Duration timeout() {
        return timeout;
    }
}
