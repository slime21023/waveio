package io.wavejava.wave.api.resilience;

import io.wavejava.wave.api.http.Problem;
import io.wavejava.wave.api.http.Request;
import io.wavejava.wave.api.http.Response;
import io.wavejava.wave.api.middleware.Middleware;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * Bounded, no-queue token-bucket middleware for low-cardinality request identities.
 *
 * <p>The policy never creates a bucket after its finite key table is full. It rejects with 429
 * instead of evicting active identities or retaining an unbounded cardinality map. The keyer must
 * return a stable identity such as tenant/API key/client address, never a raw path or query.</p>
 */
public final class RateLimitPolicy implements Middleware {
    private final Object lock = new Object();
    private final int capacity;
    private final int tokensPerPeriod;
    private final Duration refillPeriod;
    private final int maximumKeys;
    private final Duration keyIdleTimeout;
    private final int maximumKeyBytes;
    private final int maximumSweepPerRequest;
    private final Function<Request, String> keyer;
    private final Clock clock;
    private final LinkedHashMap<String, Bucket> buckets = new LinkedHashMap<>(16, 0.75f, true);

    private RateLimitPolicy(Builder builder) {
        capacity = builder.capacity;
        tokensPerPeriod = builder.tokensPerPeriod;
        refillPeriod = builder.refillPeriod;
        maximumKeys = builder.maximumKeys;
        keyIdleTimeout = builder.keyIdleTimeout;
        maximumKeyBytes = builder.maximumKeyBytes;
        maximumSweepPerRequest = builder.maximumSweepPerRequest;
        keyer = builder.keyer;
        clock = builder.clock;
    }

    /** Starts a finite token-bucket middleware builder. */
    public static Builder builder() {
        return new Builder();
    }

    /** Returns the number of currently retained identities after bounded expiry cleanup. */
    public int activeKeys() {
        synchronized (lock) {
            sweep(clock.instant(), maximumKeys);
            return buckets.size();
        }
    }

    @Override
    public void onRequest(Request request, Response response) {
        var decision = acquire(Objects.requireNonNull(request, "request"));
        if (decision.accepted()) {
            return;
        }
        response.header("Retry-After", Long.toString(decision.retryAfterSeconds()))
                .problem(Problem.of(429, "Too Many Requests"));
    }

    private Decision acquire(Request request) {
        final String key;
        try {
            key = validateKey(keyer.apply(request));
        } catch (RuntimeException invalidKey) {
            return Decision.rejected(1);
        }
        var now = clock.instant();
        synchronized (lock) {
            sweep(now, maximumSweepPerRequest);
            var bucket = buckets.get(key);
            if (bucket == null) {
                if (buckets.size() >= maximumKeys) {
                    return Decision.rejected(1);
                }
                bucket = new Bucket(capacity, now);
                buckets.put(key, bucket);
            }
            refill(bucket, now);
            bucket.lastSeen = now;
            if (bucket.tokens >= 1.0d) {
                bucket.tokens -= 1.0d;
                return Decision.allow();
            }
            return Decision.rejected(retryAfterSeconds(bucket.tokens));
        }
    }

    private void refill(Bucket bucket, Instant now) {
        if (!now.isAfter(bucket.lastRefilled)) {
            return;
        }
        var elapsedNanos = durationNanos(Duration.between(bucket.lastRefilled, now));
        if (elapsedNanos <= 0) {
            return;
        }
        var rate = (double) tokensPerPeriod / durationNanos(refillPeriod);
        bucket.tokens = Math.min(capacity, bucket.tokens + elapsedNanos * rate);
        bucket.lastRefilled = now;
    }

    private long retryAfterSeconds(double availableTokens) {
        var neededTokens = Math.max(0.0d, 1.0d - availableTokens);
        var nanos = neededTokens * durationNanos(refillPeriod) / tokensPerPeriod;
        return Math.max(1L, (long) Math.ceil(nanos / 1_000_000_000.0d));
    }

    private void sweep(Instant now, int maximumRemovals) {
        var removed = 0;
        Iterator<Map.Entry<String, Bucket>> entries = buckets.entrySet().iterator();
        while (entries.hasNext() && removed < maximumRemovals) {
            var entry = entries.next();
            if (!now.isBefore(safePlus(entry.getValue().lastSeen, keyIdleTimeout))) {
                entries.remove();
                removed++;
            }
        }
    }

    private String validateKey(String key) {
        var value = Objects.requireNonNull(key, "rate-limit key");
        if (value.isBlank() || value.indexOf('\0') >= 0 || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0
                || value.getBytes(StandardCharsets.UTF_8).length > maximumKeyBytes) {
            throw new IllegalArgumentException("rate-limit key is blank, unsafe, or exceeds maximumKeyBytes");
        }
        return value;
    }

    private static long durationNanos(Duration duration) {
        try {
            return duration.toNanos();
        } catch (ArithmeticException ignored) {
            return Long.MAX_VALUE;
        }
    }

    private static Instant safePlus(Instant instant, Duration duration) {
        try {
            return instant.plus(duration);
        } catch (RuntimeException ignored) {
            return Instant.MAX;
        }
    }

    private static final class Bucket {
        private double tokens;
        private Instant lastRefilled;
        private Instant lastSeen;

        private Bucket(int capacity, Instant now) {
            tokens = capacity;
            lastRefilled = now;
            lastSeen = now;
        }
    }

    private record Decision(boolean accepted, long retryAfterSeconds) {
        private static Decision allow() {
            return new Decision(true, 0);
        }

        private static Decision rejected(long retryAfterSeconds) {
            return new Decision(false, retryAfterSeconds);
        }
    }

    /** Builder for a finite token-bucket policy. */
    public static final class Builder {
        private int capacity = 60;
        private int tokensPerPeriod = 60;
        private Duration refillPeriod = Duration.ofMinutes(1);
        private int maximumKeys = 10_000;
        private Duration keyIdleTimeout = Duration.ofMinutes(10);
        private int maximumKeyBytes = 256;
        private int maximumSweepPerRequest = 32;
        private Function<Request, String> keyer = request -> request.remoteAddress()
                .map(address -> address.toString())
                .orElse("anonymous");
        private Clock clock = Clock.systemUTC();

        private Builder() {
        }

        /** Sets the maximum immediately available tokens for one identity. */
        public Builder capacity(int capacity) {
            this.capacity = requirePositive(capacity, "capacity");
            return this;
        }

        /** Sets the number of tokens restored per refill period. */
        public Builder tokensPerPeriod(int tokensPerPeriod) {
            this.tokensPerPeriod = requirePositive(tokensPerPeriod, "tokensPerPeriod");
            return this;
        }

        /** Sets the finite refill period. */
        public Builder refillPeriod(Duration refillPeriod) {
            this.refillPeriod = requirePositive(refillPeriod, "refillPeriod");
            return this;
        }

        /** Sets the finite number of identities whose token state can be retained. */
        public Builder maximumKeys(int maximumKeys) {
            this.maximumKeys = requirePositive(maximumKeys, "maximumKeys");
            return this;
        }

        /** Sets idle expiry for retained identity buckets. */
        public Builder keyIdleTimeout(Duration keyIdleTimeout) {
            this.keyIdleTimeout = requirePositive(keyIdleTimeout, "keyIdleTimeout");
            return this;
        }

        /** Sets the bounded UTF-8 size of one identity key. */
        public Builder maximumKeyBytes(int maximumKeyBytes) {
            this.maximumKeyBytes = requirePositive(maximumKeyBytes, "maximumKeyBytes");
            return this;
        }

        /** Sets the maximum expired buckets removed while admitting one request. */
        public Builder maximumSweepPerRequest(int maximumSweepPerRequest) {
            this.maximumSweepPerRequest = requirePositive(maximumSweepPerRequest, "maximumSweepPerRequest");
            return this;
        }

        /** Sets a stable low-cardinality request identity extractor. */
        public Builder keyer(Function<Request, String> keyer) {
            this.keyer = Objects.requireNonNull(keyer, "keyer");
            return this;
        }

        /** Sets a clock for deterministic token-bucket tests. */
        public Builder clock(Clock clock) {
            this.clock = Objects.requireNonNull(clock, "clock");
            return this;
        }

        /** Validates all count/byte/time budgets and creates the middleware. */
        public RateLimitPolicy build() {
            // Access all fields here so builder mutation cannot leave an unchecked null.
            requirePositive(capacity, "capacity");
            requirePositive(tokensPerPeriod, "tokensPerPeriod");
            refillPeriod = requirePositive(refillPeriod, "refillPeriod");
            requirePositive(maximumKeys, "maximumKeys");
            keyIdleTimeout = requirePositive(keyIdleTimeout, "keyIdleTimeout");
            requirePositive(maximumKeyBytes, "maximumKeyBytes");
            requirePositive(maximumSweepPerRequest, "maximumSweepPerRequest");
            keyer = Objects.requireNonNull(keyer, "keyer");
            clock = Objects.requireNonNull(clock, "clock");
            return new RateLimitPolicy(this);
        }

        private static int requirePositive(int value, String name) {
            if (value <= 0) {
                throw new IllegalArgumentException(name + " must be greater than zero");
            }
            return value;
        }

        private static Duration requirePositive(Duration value, String name) {
            Objects.requireNonNull(value, name);
            if (value.isZero() || value.isNegative()) {
                throw new IllegalArgumentException(name + " must be greater than zero");
            }
            return value;
        }
    }
}
