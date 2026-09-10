package io.wavejava.wave.api.session;

import java.time.Duration;
import java.util.Objects;

/** Immutable expiry, rotation, and bounded-attribute policy for server-side sessions. */
public record SessionPolicy(
        Duration idleTimeout,
        Duration absoluteTimeout,
        Duration rotationInterval,
        int maximumAttributes,
        int maximumAttributeBytes) {
    /** Creates the default finite session policy. */
    public static SessionPolicy defaults() {
        return new SessionPolicy(
                Duration.ofMinutes(30),
                Duration.ofHours(8),
                Duration.ofMinutes(15),
                32,
                8 * 1024);
    }

    public SessionPolicy {
        idleTimeout = requirePositive(idleTimeout, "idleTimeout");
        absoluteTimeout = requirePositive(absoluteTimeout, "absoluteTimeout");
        rotationInterval = Objects.requireNonNull(rotationInterval, "rotationInterval");
        if (rotationInterval.isNegative()) {
            throw new IllegalArgumentException("rotationInterval must not be negative");
        }
        if (maximumAttributes <= 0) {
            throw new IllegalArgumentException("maximumAttributes must be greater than zero");
        }
        if (maximumAttributeBytes <= 0) {
            throw new IllegalArgumentException("maximumAttributeBytes must be greater than zero");
        }
    }

    /** Returns whether rotation is disabled for this policy. */
    public boolean rotationDisabled() {
        return rotationInterval.isZero();
    }

    private static Duration requirePositive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be greater than zero");
        }
        return value;
    }
}
