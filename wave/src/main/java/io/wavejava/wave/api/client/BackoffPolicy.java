package io.wavejava.wave.api.client;

import java.time.Duration;
import java.util.Objects;

/** Computes a finite delay before a retry attempt. */
@FunctionalInterface
public interface BackoffPolicy {
    /**
     * Returns the delay before retry number {@code retryNumber}, where the first retry is one.
     * Implementations must return a non-null, non-negative duration.
     */
    Duration delayBeforeRetry(int retryNumber);

    /** Returns a policy that schedules each retry without delay. */
    static BackoffPolicy none() {
        return retryNumber -> validateRetryNumber(retryNumber, Duration.ZERO);
    }

    /** Returns a policy with the same finite delay before every retry. */
    static BackoffPolicy fixed(Duration delay) {
        var validatedDelay = requireNonNegative(delay, "delay");
        return retryNumber -> validateRetryNumber(retryNumber, validatedDelay);
    }

    /**
     * Returns a capped exponential backoff policy.
     *
     * <p>The first retry waits {@code initialDelay}; each following retry doubles the previous
     * delay up to {@code maximumDelay}. Arithmetic saturates at the configured maximum.</p>
     */
    static BackoffPolicy exponential(Duration initialDelay, Duration maximumDelay) {
        var initial = requireNonNegative(initialDelay, "initialDelay");
        var maximum = requireNonNegative(maximumDelay, "maximumDelay");
        if (initial.compareTo(maximum) > 0) {
            throw new IllegalArgumentException("initialDelay must not exceed maximumDelay");
        }
        return retryNumber -> {
            validateRetryNumber(retryNumber, Duration.ZERO);
            var delay = initial;
            for (var attempt = 1; attempt < retryNumber && delay.compareTo(maximum) < 0; attempt++) {
                delay = doubledCapped(delay, maximum);
            }
            return delay;
        };
    }

    private static Duration validateRetryNumber(int retryNumber, Duration delay) {
        if (retryNumber <= 0) {
            throw new IllegalArgumentException("retryNumber must be greater than zero: " + retryNumber);
        }
        return requireNonNegative(delay, "backoff delay");
    }

    private static Duration requireNonNegative(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isNegative()) {
            throw new IllegalArgumentException(name + " must not be negative: " + value);
        }
        return value;
    }

    private static Duration doubledCapped(Duration delay, Duration maximum) {
        try {
            var doubled = delay.multipliedBy(2);
            return doubled.compareTo(maximum) > 0 ? maximum : doubled;
        } catch (ArithmeticException ignored) {
            return maximum;
        }
    }
}

/** Internal validation at the policy invocation boundary. */
final class BackoffDelays {
    private BackoffDelays() {
    }

    static Duration checkedDelay(BackoffPolicy policy, int retryNumber) {
        Objects.requireNonNull(policy, "policy");
        if (retryNumber <= 0) {
            throw new IllegalArgumentException("retryNumber must be greater than zero: " + retryNumber);
        }
        var delay = Objects.requireNonNull(policy.delayBeforeRetry(retryNumber), "backoff delay");
        if (delay.isNegative()) {
            throw new IllegalArgumentException("backoff delay must not be negative: " + delay);
        }
        return delay;
    }
}
