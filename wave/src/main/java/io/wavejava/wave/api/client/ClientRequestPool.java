package io.wavejava.wave.api.client;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Bounded pool that limits simultaneous client requests; it is not a connection pool.
 *
 * <p>Each lease permits one active exchange through the owning client's transport. Sharing a
 * {@code ClientRequestPool} across clients shares only the request budget; each client owns an
 * independent physical channel cache.</p>
 */
public final class ClientRequestPool implements AutoCloseable {
    /** Default concurrent exchange lease cap. */
    public static final int DEFAULT_MAXIMUM_CONCURRENT_REQUESTS = 64;
    /** Default bounded waiting-admission cap. */
    public static final int DEFAULT_MAXIMUM_QUEUED_REQUESTS = 128;
    /** Default repeatable request body budget. */
    public static final int DEFAULT_MAXIMUM_REQUEST_BODY_BYTES = 1024 * 1024;
    /** Default aggregated response body budget. */
    public static final int DEFAULT_MAXIMUM_RESPONSE_BODY_BYTES = 8 * 1024 * 1024;
    /** Default maximum number of received response header fields. */
    public static final int DEFAULT_MAXIMUM_RESPONSE_HEADERS = 128;
    /** Default maximum logical wire bytes across received response header fields. */
    public static final int DEFAULT_MAXIMUM_RESPONSE_HEADER_BYTES = 64 * 1024;
    /** Default connect-time budget. */
    public static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(5);
    /** Default complete logical-exchange budget, including retry backoff and queue wait. */
    public static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private final Object monitor = new Object();
    private final int maximumConcurrentRequests;
    private final int maximumQueuedRequests;
    private final int maximumRequestBodyBytes;
    private final int maximumResponseBodyBytes;
    private final int maximumResponseHeaders;
    private final int maximumResponseHeaderBytes;
    private final Duration connectTimeout;
    private final Duration requestTimeout;
    private final ArrayDeque<CompletableFuture<Lease>> waiting = new ArrayDeque<>();
    private int leasedRequests;
    private boolean closed;

    private ClientRequestPool(Builder builder) {
        maximumConcurrentRequests = requirePositive(builder.maximumConcurrentRequests, "maximumConcurrentRequests");
        maximumQueuedRequests = requireNonNegative(builder.maximumQueuedRequests, "maximumQueuedRequests");
        maximumRequestBodyBytes = requirePositive(builder.maximumRequestBodyBytes, "maximumRequestBodyBytes");
        maximumResponseBodyBytes = requirePositive(builder.maximumResponseBodyBytes, "maximumResponseBodyBytes");
        maximumResponseHeaders = requirePositive(builder.maximumResponseHeaders, "maximumResponseHeaders");
        maximumResponseHeaderBytes = requirePositive(builder.maximumResponseHeaderBytes, "maximumResponseHeaderBytes");
        connectTimeout = requirePositive(builder.connectTimeout, "connectTimeout");
        requestTimeout = requirePositive(builder.requestTimeout, "requestTimeout");
    }

    /** Returns a builder with finite documented defaults. */
    public static Builder builder() {
        return new Builder();
    }

    /** Returns a new pool initialized with finite documented defaults. */
    public static ClientRequestPool defaults() {
        return builder().build();
    }

    /** Returns the active-exchange lease cap. */
    public int maximumConcurrentRequests() {
        return maximumConcurrentRequests;
    }

    /** Returns the queue cap for exchange admission. */
    public int maximumQueuedRequests() {
        return maximumQueuedRequests;
    }

    /** Returns the repeatable outbound body cap. */
    public int maximumRequestBodyBytes() {
        return maximumRequestBodyBytes;
    }

    /** Returns the aggregated inbound body cap. */
    public int maximumResponseBodyBytes() {
        return maximumResponseBodyBytes;
    }

    /** Returns the maximum accepted number of response header fields. */
    public int maximumResponseHeaders() {
        return maximumResponseHeaders;
    }

    /** Returns the maximum accepted logical wire bytes across response header fields. */
    public int maximumResponseHeaderBytes() {
        return maximumResponseHeaderBytes;
    }

    /** Returns the transport connection-establishment timeout. */
    public Duration connectTimeout() {
        return connectTimeout;
    }

    /** Returns the end-to-end logical-exchange timeout. */
    public Duration requestTimeout() {
        return requestTimeout;
    }

    /** Returns a thread-safe instantaneous pool snapshot. */
    public Snapshot snapshot() {
        synchronized (monitor) {
            return new Snapshot(leasedRequests, waiting.size(), closed);
        }
    }

    /** Returns whether this request pool has been closed. */
    public boolean isClosed() {
        synchronized (monitor) {
            return closed;
        }
    }

    /** Rejects waiting work and prevents future client exchanges from being admitted. */
    @Override
    public void close() {
        List<CompletableFuture<Lease>> rejected;
        synchronized (monitor) {
            if (closed) {
                return;
            }
            closed = true;
            rejected = List.copyOf(waiting);
            waiting.clear();
        }
        var failure = new IllegalStateException("Client request pool is closed");
        for (var waiter : rejected) {
            waiter.completeExceptionally(failure);
        }
    }

    /** Acquires one bounded internal request admission. */
    public CompletableFuture<Lease> acquire() {
        CompletableFuture<Lease> queued = null;
        synchronized (monitor) {
            if (closed) {
                return CompletableFuture.failedFuture(new IllegalStateException("Client request pool is closed"));
            }
            if (leasedRequests < maximumConcurrentRequests) {
                leasedRequests++;
                return CompletableFuture.completedFuture(new Lease(this));
            }
            if (waiting.size() >= maximumQueuedRequests) {
                return CompletableFuture.failedFuture(new ClientRequestPoolRejectedException(maximumConcurrentRequests, maximumQueuedRequests));
            }
            queued = new CompletableFuture<>();
            waiting.addLast(queued);
        }
        var waiter = queued;
        waiter.whenComplete((lease, failure) -> {
            if (waiter.isCancelled()) {
                removeCancelledWaiter(waiter);
            }
        });
        return queued;
    }

    private void removeCancelledWaiter(CompletableFuture<Lease> waiter) {
        synchronized (monitor) {
            waiting.remove(waiter);
        }
    }

    private void release(Lease lease) {
        CompletableFuture<Lease> next = null;
        synchronized (monitor) {
            if (lease.released) {
                return;
            }
            lease.released = true;
            while (!waiting.isEmpty() && next == null) {
                var candidate = waiting.removeFirst();
                if (!candidate.isDone()) {
                    next = candidate;
                }
            }
            if (next == null) {
                leasedRequests--;
            }
        }
        if (next != null && !next.complete(new Lease(this))) {
            // A cancellation can race just after selection. Return the unused lease and promote
            // the next waiting request so a canceled waiter never consumes pool capacity.
            synchronized (monitor) {
                leasedRequests--;
            }
            releasePromotedWaiter();
        }
    }

    private void releasePromotedWaiter() {
        CompletableFuture<Lease> next = null;
        synchronized (monitor) {
            if (leasedRequests >= maximumConcurrentRequests || closed) {
                return;
            }
            while (!waiting.isEmpty() && next == null) {
                var candidate = waiting.removeFirst();
                if (!candidate.isDone()) {
                    leasedRequests++;
                    next = candidate;
                }
            }
        }
        if (next != null && !next.complete(new Lease(this))) {
            synchronized (monitor) {
                leasedRequests--;
            }
            releasePromotedWaiter();
        }
    }

    private static int requirePositive(int value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be greater than zero: " + value);
        }
        return value;
    }

    private static int requireNonNegative(int value, String name) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " must not be negative: " + value);
        }
        return value;
    }

    private static Duration requirePositive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be greater than zero: " + value);
        }
        return value;
    }

    /** Read-only current admission state. */
    public record Snapshot(int leasedRequests, int queuedRequests, boolean closed) {
        public Snapshot {
            if (leasedRequests < 0 || queuedRequests < 0) {
                throw new IllegalArgumentException("Pool counters must not be negative");
            }
        }
    }

    /** Mutable builder for an immutable limit snapshot and a new request pool. */
    public static final class Builder {
        private int maximumConcurrentRequests = DEFAULT_MAXIMUM_CONCURRENT_REQUESTS;
        private int maximumQueuedRequests = DEFAULT_MAXIMUM_QUEUED_REQUESTS;
        private int maximumRequestBodyBytes = DEFAULT_MAXIMUM_REQUEST_BODY_BYTES;
        private int maximumResponseBodyBytes = DEFAULT_MAXIMUM_RESPONSE_BODY_BYTES;
        private int maximumResponseHeaders = DEFAULT_MAXIMUM_RESPONSE_HEADERS;
        private int maximumResponseHeaderBytes = DEFAULT_MAXIMUM_RESPONSE_HEADER_BYTES;
        private Duration connectTimeout = DEFAULT_CONNECT_TIMEOUT;
        private Duration requestTimeout = DEFAULT_REQUEST_TIMEOUT;

        private Builder() {
        }

        /** Sets the finite active-exchange lease cap. */
        public Builder maximumConcurrentRequests(int maximumConcurrentRequests) {
            this.maximumConcurrentRequests = requirePositive(maximumConcurrentRequests, "maximumConcurrentRequests");
            return this;
        }

        /** Sets the finite waiting-admission cap; zero rejects work once all leases are in use. */
        public Builder maximumQueuedRequests(int maximumQueuedRequests) {
            this.maximumQueuedRequests = requireNonNegative(maximumQueuedRequests, "maximumQueuedRequests");
            return this;
        }

        /** Sets the maximum repeatable request-body size. */
        public Builder maximumRequestBodyBytes(int maximumRequestBodyBytes) {
            this.maximumRequestBodyBytes = requirePositive(maximumRequestBodyBytes, "maximumRequestBodyBytes");
            return this;
        }

        /** Sets the maximum fully aggregated response-body size. */
        public Builder maximumResponseBodyBytes(int maximumResponseBodyBytes) {
            this.maximumResponseBodyBytes = requirePositive(maximumResponseBodyBytes, "maximumResponseBodyBytes");
            return this;
        }

        /** Sets the maximum number of accepted response header fields. */
        public Builder maximumResponseHeaders(int maximumResponseHeaders) {
            this.maximumResponseHeaders = requirePositive(maximumResponseHeaders, "maximumResponseHeaders");
            return this;
        }

        /** Sets the maximum accepted logical wire bytes across response header fields. */
        public Builder maximumResponseHeaderBytes(int maximumResponseHeaderBytes) {
            this.maximumResponseHeaderBytes = requirePositive(
                    maximumResponseHeaderBytes, "maximumResponseHeaderBytes");
            return this;
        }

        /** Sets the finite connection-establishment timeout. */
        public Builder connectTimeout(Duration connectTimeout) {
            this.connectTimeout = requirePositive(connectTimeout, "connectTimeout");
            return this;
        }

        /** Sets the finite whole-logical-exchange timeout. */
        public Builder requestTimeout(Duration requestTimeout) {
            this.requestTimeout = requirePositive(requestTimeout, "requestTimeout");
            return this;
        }

        /** Builds a new independently closable, bounded request pool. */
        public ClientRequestPool build() {
            return new ClientRequestPool(this);
        }
    }

    /** One request admission which must be closed exactly once. */
    public static final class Lease implements AutoCloseable {
        private final ClientRequestPool owner;
        private boolean released;

        private Lease(ClientRequestPool owner) {
            this.owner = owner;
        }

        @Override
        public void close() {
            owner.release(this);
        }
    }
}



