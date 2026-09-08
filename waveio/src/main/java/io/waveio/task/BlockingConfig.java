package io.waveio.task;

/** Explicit concurrency and queue limits for virtual-thread blocking work. */
public record BlockingConfig(int concurrency, int queueCapacity) {
    /** Validates the blocking resource limits. */
    public BlockingConfig {
        if (concurrency < 1) {
            throw new IllegalArgumentException("concurrency must be positive");
        }
        if (queueCapacity < 0) {
            throw new IllegalArgumentException("queueCapacity must not be negative");
        }
    }
}
