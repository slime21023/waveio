package io.wavejava.wave.api.health;

import io.wavejava.wave.api.http.CancellationToken;
import io.wavejava.wave.api.http.Deadline;
import java.util.Objects;

/** Deadline and cancellation signal owned by one health-check evaluation. */
public final class HealthCheckContext {
    private final Deadline deadline;
    private final CancellationToken cancellationToken;

    HealthCheckContext(Deadline deadline, CancellationToken cancellationToken) {
        this.deadline = Objects.requireNonNull(deadline, "deadline");
        this.cancellationToken = Objects.requireNonNull(cancellationToken, "cancellationToken");
    }

    /** Returns the absolute deadline for this check. */
    public Deadline deadline() {
        return deadline;
    }

    /** Returns the cooperative cancellation signal for timeout, interruption, or shutdown. */
    public CancellationToken cancellationToken() {
        return cancellationToken;
    }
}
