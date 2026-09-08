package io.waveio.server;

import java.time.Instant;
import java.util.Objects;

/** An immutable event emitted by a server-facing component. */
public record ObservationEvent(String name, Instant occurredAt) {
    /** Creates an observation event. */
    public ObservationEvent {
        Objects.requireNonNull(name, "name");
        if (name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        Objects.requireNonNull(occurredAt, "occurredAt");
    }
}
