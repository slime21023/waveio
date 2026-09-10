package io.wavejava.wave.api.observability;

/** Finite queue policy for asynchronous access-log, metrics, and tracing delivery. */
public record ObservabilityLimits(int maximumQueuedEvents) {
    private static final int DEFAULT_MAXIMUM_QUEUED_EVENTS = 1_024;
    private static final int ABSOLUTE_MAXIMUM_QUEUED_EVENTS = 1_000_000;

    public ObservabilityLimits {
        if (maximumQueuedEvents <= 0 || maximumQueuedEvents > ABSOLUTE_MAXIMUM_QUEUED_EVENTS) {
            throw new IllegalArgumentException("maximumQueuedEvents must be between 1 and "
                    + ABSOLUTE_MAXIMUM_QUEUED_EVENTS + ": " + maximumQueuedEvents);
        }
    }

    /** Returns the default finite queue of 1,024 access events. */
    public static ObservabilityLimits defaults() {
        return new ObservabilityLimits(DEFAULT_MAXIMUM_QUEUED_EVENTS);
    }
}
