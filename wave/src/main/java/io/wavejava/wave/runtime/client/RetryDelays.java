package io.wavejava.wave.runtime.client;

import io.wavejava.wave.api.client.BackoffPolicy;
import java.time.Duration;
import java.util.Objects;

/** Runtime validation at the retry-policy invocation boundary. */
final class RetryDelays {
    private RetryDelays() {
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
