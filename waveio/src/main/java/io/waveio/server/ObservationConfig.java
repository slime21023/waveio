package io.waveio.server;

/** Explicit bounded resources for asynchronous observation delivery. */
public record ObservationConfig(int parallelism, int queueCapacity) {
    /** Validates observer worker and queue capacities. */
    public ObservationConfig {
        if (parallelism < 1 || queueCapacity < 1) {
            throw new IllegalArgumentException("observation parallelism and queueCapacity must be positive");
        }
    }
}
