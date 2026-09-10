package io.wavejava.wave.api.health;

import java.time.Duration;
import java.util.Objects;

/** Explicit count, concurrency, and time limits for readiness evaluation. */
public record HealthLimits(Duration checkTimeout, int maximumChecks, int maximumConcurrentEvaluations) {
    /** Creates finite defaults for a small operations endpoint. */
    public static HealthLimits defaults() {
        return new HealthLimits(Duration.ofSeconds(2), 32, 4);
    }

    public HealthLimits {
        checkTimeout = Objects.requireNonNull(checkTimeout, "checkTimeout");
        if (checkTimeout.isZero() || checkTimeout.isNegative()) {
            throw new IllegalArgumentException("checkTimeout must be greater than zero");
        }
        if (maximumChecks <= 0 || maximumConcurrentEvaluations <= 0) {
            throw new IllegalArgumentException("health count limits must be greater than zero");
        }
    }
}
