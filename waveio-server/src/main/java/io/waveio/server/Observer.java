package io.waveio.server;

/** Receives immutable server observations asynchronously. */
@FunctionalInterface
public interface Observer {
    /** Handles one event. Implementations may throw; failures are isolated and recorded. */
    void onEvent(ObservationEvent event);
}
