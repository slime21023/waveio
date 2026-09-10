package io.wavejava.wave.api.http;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * An immutable point in time after which a request invocation must no longer continue.
 *
 * <p>A deadline is only a value and does not schedule cancellation by itself. The invocation
 * runtime is responsible for cancelling its {@link CancellationToken} when this deadline elapses.
 * The overloads accepting {@link Instant} let callers and tests observe the deadline against a
 * caller-controlled clock.</p>
 */
public final class Deadline {
    private final Instant expiresAt;

    private Deadline(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }

    /** Creates a deadline that expires at the supplied instant. */
    public static Deadline at(Instant expiresAt) {
        return new Deadline(Objects.requireNonNull(expiresAt, "expiresAt"));
    }

    /** Creates a deadline relative to the current system clock. */
    public static Deadline after(Duration duration) {
        Objects.requireNonNull(duration, "duration");
        return at(Instant.now().plus(duration));
    }

    /** Returns the instant at which this deadline expires. */
    public Instant expiresAt() {
        return expiresAt;
    }

    /** Returns whether this deadline has elapsed according to the system clock. */
    public boolean isExpired() {
        return isExpired(Instant.now());
    }

    /** Returns whether this deadline has elapsed at {@code now}. */
    public boolean isExpired(Instant now) {
        Objects.requireNonNull(now, "now");
        return !expiresAt.isAfter(now);
    }

    /** Returns the non-negative time remaining according to the system clock. */
    public Duration remaining() {
        return remaining(Instant.now());
    }

    /** Returns the non-negative time remaining at {@code now}. */
    public Duration remaining(Instant now) {
        Objects.requireNonNull(now, "now");
        var remaining = Duration.between(now, expiresAt);
        return remaining.isNegative() ? Duration.ZERO : remaining;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Deadline deadline && expiresAt.equals(deadline.expiresAt);
    }

    @Override
    public int hashCode() {
        return expiresAt.hashCode();
    }

    @Override
    public String toString() {
        return "Deadline[expiresAt=" + expiresAt + ']';
    }
}
