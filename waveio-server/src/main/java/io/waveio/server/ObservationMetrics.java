package io.waveio.server;

/** Immutable counters for observer delivery outcomes. */
public record ObservationMetrics(long delivered, long rejected, long failures) {
    /** Creates counters, rejecting negative values. */
    public ObservationMetrics {
        if (delivered < 0 || rejected < 0 || failures < 0) {
            throw new IllegalArgumentException("metrics must not be negative");
        }
    }
}
