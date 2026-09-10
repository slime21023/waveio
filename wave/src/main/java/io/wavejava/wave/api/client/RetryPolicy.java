package io.wavejava.wave.api.client;

import io.wavejava.wave.api.http.HttpMethod;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable, idempotency-safe retry policy for bounded byte-body requests.
 *
 * <p>{@code maximumAttempts} counts the initial exchange. A request is retried only when it is
 * marked retry-safe: standard idempotent HTTP methods are safe by default, while other methods
 * require explicit {@link ClientRequest.Builder#retrySafe(boolean)} opt-in. Retrying a POST just
 * because a server returned a transient status is therefore impossible by default.</p>
 */
public final class RetryPolicy {
    private static final RetryPolicy NONE = new RetryPolicy(1, Set.of(), false, BackoffPolicy.none());

    private final int maximumAttempts;
    private final Set<Integer> retryStatusCodes;
    private final boolean retryTransportFailures;
    private final BackoffPolicy backoff;

    private RetryPolicy(
            int maximumAttempts,
            Set<Integer> retryStatusCodes,
            boolean retryTransportFailures,
            BackoffPolicy backoff) {
        this.maximumAttempts = requirePositive(maximumAttempts, "maximumAttempts");
        this.retryStatusCodes = Set.copyOf(retryStatusCodes);
        this.retryTransportFailures = retryTransportFailures;
        this.backoff = Objects.requireNonNull(backoff, "backoff");
    }

    /**
     * Returns a policy that performs one retry-budget attempt and never retries.
     *
     * <p>Automatic redirect hops are governed separately by {@link RedirectPolicy} and do not
     * consume this budget.</p>
     */
    public static RetryPolicy none() {
        return NONE;
    }

    /** Starts a retry-policy builder with one permitted attempt and no retry triggers. */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns the maximum number of retry-budget attempts, including the initial attempt.
     *
     * <p>Automatic redirect hops do not consume this budget; they are bounded by
     * {@link RedirectPolicy#maximumRedirects()} instead.</p>
     */
    public int maximumAttempts() {
        return maximumAttempts;
    }

    /** Returns response status codes that can trigger a safe retry. */
    public Set<Integer> retryStatusCodes() {
        return retryStatusCodes;
    }

    /** Returns whether transport failures may trigger a safe retry. */
    public boolean retryTransportFailures() {
        return retryTransportFailures;
    }

    /** Returns the policy used to schedule the retry delay. */
    public BackoffPolicy backoff() {
        return backoff;
    }

    /** Returns whether a response status may be retried after {@code completedAttempts}. */
    public boolean shouldRetryResponse(ClientRequest request, int completedAttempts, int statusCode) {
        return canRetry(request, completedAttempts) && retryStatusCodes.contains(statusCode);
    }

    /** Returns whether a transport failure may be retried after {@code completedAttempts}. */
    public boolean shouldRetryTransportFailure(ClientRequest request, int completedAttempts) {
        return canRetry(request, completedAttempts) && retryTransportFailures;
    }

    /** Returns whether a method is conventionally idempotent for automatic retry purposes. */
    public static boolean isIdempotent(HttpMethod method) {
        Objects.requireNonNull(method, "method");
        return switch (method.name()) {
            case "GET", "HEAD", "PUT", "DELETE", "OPTIONS", "TRACE" -> true;
            default -> false;
        };
    }

    private boolean canRetry(ClientRequest request, int completedAttempts) {
        Objects.requireNonNull(request, "request");
        if (completedAttempts <= 0) {
            throw new IllegalArgumentException("completedAttempts must be greater than zero: " + completedAttempts);
        }
        return completedAttempts < maximumAttempts && request.retrySafe();
    }

    private static int requirePositive(int value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be greater than zero: " + value);
        }
        return value;
    }

    /** Mutable builder for immutable retry policy snapshots. */
    public static final class Builder {
        private int maximumAttempts = 1;
        private final Set<Integer> retryStatusCodes = new LinkedHashSet<>();
        private boolean retryTransportFailures;
        private BackoffPolicy backoff = BackoffPolicy.none();

        private Builder() {
        }

        /**
         * Sets the retry-budget limit, including the first request but excluding redirect hops.
         */
        public Builder maximumAttempts(int maximumAttempts) {
            this.maximumAttempts = requirePositive(maximumAttempts, "maximumAttempts");
            return this;
        }

        /** Adds a response status code that can trigger a safe retry. */
        public Builder retryOnStatus(int statusCode) {
            if (statusCode < 100 || statusCode > 599) {
                throw new IllegalArgumentException("Invalid HTTP response status: " + statusCode);
            }
            retryStatusCodes.add(statusCode);
            return this;
        }

        /** Replaces retryable response status codes. */
        public Builder retryOnStatuses(Iterable<Integer> statusCodes) {
            Objects.requireNonNull(statusCodes, "statusCodes");
            retryStatusCodes.clear();
            for (var statusCode : statusCodes) {
                retryOnStatus(Objects.requireNonNull(statusCode, "statusCode"));
            }
            return this;
        }

        /** Enables or disables safe retries after transport failures. */
        public Builder retryTransportFailures(boolean retryTransportFailures) {
            this.retryTransportFailures = retryTransportFailures;
            return this;
        }

        /** Sets the bounded retry delay policy. */
        public Builder backoff(BackoffPolicy backoff) {
            this.backoff = Objects.requireNonNull(backoff, "backoff");
            return this;
        }

        /** Builds an immutable retry policy. */
        public RetryPolicy build() {
            return new RetryPolicy(maximumAttempts, retryStatusCodes, retryTransportFailures, backoff);
        }
    }
}
