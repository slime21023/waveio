package io.wavejava.wave.runtime;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/** Mutable test clock whose time advances only when a test explicitly changes it. */
final class DeterministicClock extends Clock {
    private final AtomicReference<Instant> instant;
    private final ZoneId zone;

    private DeterministicClock(AtomicReference<Instant> instant, ZoneId zone) {
        this.instant = instant;
        this.zone = zone;
    }

    static DeterministicClock at(Instant instant) {
        return new DeterministicClock(new AtomicReference<>(Objects.requireNonNull(instant, "instant")), ZoneId.of("UTC"));
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return new DeterministicClock(instant, Objects.requireNonNull(zone, "zone"));
    }

    @Override
    public Instant instant() {
        return instant.get();
    }

    /** Sets the instant observed by this clock. */
    void set(Instant instant) {
        this.instant.set(Objects.requireNonNull(instant, "instant"));
    }

    /** Advances this clock by {@code duration} and returns the resulting instant. */
    Instant advance(Duration duration) {
        Objects.requireNonNull(duration, "duration");
        if (duration.isNegative()) {
            throw new IllegalArgumentException("duration must not be negative");
        }
        return instant.updateAndGet(current -> current.plus(duration));
    }
}
