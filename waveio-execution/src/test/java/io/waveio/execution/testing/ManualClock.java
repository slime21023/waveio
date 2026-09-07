package io.waveio.execution.testing;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/** A test clock that changes only when the test explicitly advances it. */
public final class ManualClock {
    private Instant now;

    /** Creates a clock at the supplied initial instant. */
    public ManualClock(Instant initial) {
        now = Objects.requireNonNull(initial, "initial");
    }

    /** Returns the current test instant. */
    public Instant now() {
        return now;
    }

    /** Advances the clock by a non-negative duration. */
    public void advance(Duration duration) {
        Objects.requireNonNull(duration, "duration");
        if (duration.isNegative()) {
            throw new IllegalArgumentException("duration must not be negative");
        }
        now = now.plus(duration);
    }
}
