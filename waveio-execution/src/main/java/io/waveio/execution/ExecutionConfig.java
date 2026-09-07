package io.waveio.execution;

import java.time.Duration;
import java.util.Objects;

/** Explicit resource limits for an execution runtime. */
public record ExecutionConfig(int queueCapacity, int parallelism, Duration deadline) {
    /** Validates the mandatory execution capacities and deadline. */
    public ExecutionConfig {
        if (queueCapacity < 1) {
            throw new IllegalArgumentException("queueCapacity must be positive");
        }
        if (parallelism < 1) {
            throw new IllegalArgumentException("parallelism must be positive");
        }
        Objects.requireNonNull(deadline, "deadline");
        if (deadline.isZero() || deadline.isNegative()) {
            throw new IllegalArgumentException("deadline must be positive");
        }
    }
}
