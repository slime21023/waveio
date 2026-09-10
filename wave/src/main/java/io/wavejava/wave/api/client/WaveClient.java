package io.wavejava.wave.api.client;

import io.wavejava.wave.internal.client.BlockingResultWaiter;
import io.wavejava.wave.netty.CancellationBridge;
import io.wavejava.wave.netty.ClientTransport;
import io.wavejava.wave.api.http.Deadline;
import io.wavejava.wave.api.http.HttpMethod;
import io.wavejava.wave.api.server.Http2Config;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A bounded asynchronous HTTP client with byte-body requests and responses.
 *
 * <p>The 0.4 implementation owns a reusable Netty HTTP/1.1 transport. Its public API deliberately
 * exposes neither Netty types nor transport futures. {@link ClientRequestPool} bounds concurrent request
 * work, waiting requests, byte aggregation, and time budgets; a request lease is retained
 * until the private transport has either returned a fully read response channel to its bounded
 * idle cache or observed that channel's physical close.</p>
 */
public final class WaveClient implements AutoCloseable {
    private final ClientRequestPool requestPool;
    private final boolean ownsPool;
    private final RetryPolicy retryPolicy;
    private final RedirectPolicy redirectPolicy;
    private final ProxyPolicy proxyPolicy;
    private final Http2Config http2;
    private final ClientTlsConfig tls;
    private final ClientTransport transport;
    private final ScheduledExecutorService scheduler;
    private final ExecutorService orchestration;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Set<Exchange> activeExchanges = ConcurrentHashMap.newKeySet();
    private final Object lifecycleLock = new Object();

    private WaveClient(Builder builder) {
        if (builder.requestPool == null) {
            requestPool = ClientRequestPool.defaults();
            ownsPool = true;
        } else {
            requestPool = builder.requestPool;
            ownsPool = false;
        }
        retryPolicy = builder.retryPolicy;
        redirectPolicy = builder.redirectPolicy;
        proxyPolicy = builder.proxyPolicy;
        http2 = builder.http2;
        tls = builder.tls;
        scheduler = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(runnable -> {
            var thread = new Thread(runnable, "wave-client-scheduler");
            thread.setDaemon(true);
            return thread;
        });
        // All policy/retry/requestPool continuation work is intentionally off the Netty EventLoop. The
        // transport completes its physical lifecycle on the loop, then these virtual threads make
        // the next logical decision without ever running application orchestration on I/O threads.
        orchestration = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
        transport = ClientTransport.netty(requestPool, proxyPolicy, http2, tls);
    }

    /** Starts a builder for a standalone reusable client. */
    public static Builder builder() {
        return new Builder();
    }

    /** Builds a standalone client with finite default budgets and no retry or redirect following. */
    public static WaveClient create() {
        return builder().build();
    }

    /** Returns this client's bounded request pool. */
    public ClientRequestPool requestPool() {
        return requestPool;
    }

    /** Returns this client's immutable retry policy. */
    public RetryPolicy retryPolicy() {
        return retryPolicy;
    }

    /** Returns this client's immutable redirect policy. */
    public RedirectPolicy redirectPolicy() {
        return redirectPolicy;
    }

    /** Returns this client's immutable proxy policy. */
    public ProxyPolicy proxyPolicy() {
        return proxyPolicy;
    }

    /** Returns the HTTP/2 ALPN policy selected for this client's owned transport. */
    public Http2Config http2() {
        return http2;
    }

    /** Returns this client's immutable TLS trust configuration. */
    public ClientTlsConfig tls() {
        return tls;
    }

    /** Creates a GET request for use with {@link #execute(ClientRequest)} or {@link #executeAsync(ClientRequest)}. */
    public ClientRequest get(URI uri) {
        return ClientRequest.get(uri);
    }

    /** Starts a request builder with its target URI already set. */
    public ClientRequest.Builder request(URI uri) {
        return ClientRequest.builder().uri(uri);
    }

    /** Executes one logical exchange synchronously, preserving interruption through the awaiter. */
    public ClientResponse execute(ClientRequest request) {
        return BlockingResultWaiter.await(executeAsync(request));
    }

    /**
     * Executes one logical exchange asynchronously.
     *
     * <p>The returned stage is cancellable. Cancellation releases queued or leased request-pool work,
     * cancels the active transport future, and observes any request cancellation token. Retry
     * backoff and redirect hops share the same finite whole-exchange deadline.</p>
     */
    public CompletionStage<ClientResponse> executeAsync(ClientRequest request) {
        Objects.requireNonNull(request, "request");
        if (request.bodyLength() > requestPool.maximumRequestBodyBytes()) {
            return CompletableFuture.failedFuture(new ClientLimitExceededException(
                    "request body bytes", requestPool.maximumRequestBodyBytes(), request.bodyLength()));
        }

        final Exchange exchange;
        try {
            exchange = new Exchange(request, effectiveDeadline(request));
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(failure);
        }
        // Admission, initial arming, and close must form one lifecycle boundary. Otherwise close()
        // could stop the scheduler after the open check but before this exchange has been tracked,
        // leaving an unresolved future outside the close snapshot.
        synchronized (lifecycleLock) {
            if (closed.get()) {
                return CompletableFuture.failedFuture(new IllegalStateException("WaveClient is closed"));
            }
            activeExchanges.add(exchange);
            exchange.start();
        }
        return exchange.result;
    }

    /** Returns whether this client has stopped accepting new exchanges. */
    public boolean isClosed() {
        return closed.get();
    }

    /**
     * Cancels active exchanges, stops the deadline scheduler, and closes the private transport.
     *
     * <p>An externally supplied {@link ClientRequestPool} remains open for its other owners; a default
     * request pool created by this client is closed with it.</p>
     */
    @Override
    public void close() {
        Exchange[] exchanges;
        synchronized (lifecycleLock) {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            exchanges = activeExchanges.toArray(Exchange[]::new);
        }
        for (var exchange : exchanges) {
            exchange.cancel("client closed");
        }
        // Keep the scheduler/orchestration executors alive while closeFuture callbacks drain. A
        // cancellation completes the caller result promptly, but its admitted lease is released
        // only by the later physical transport completion callback.
        transport.close();
        scheduler.shutdownNow();
        orchestration.shutdown();
        try {
            orchestration.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        if (ownsPool) {
            requestPool.close();
        }
    }

    private EffectiveDeadline effectiveDeadline(ClientRequest request) {
        var startedAt = Instant.now();
        var timeout = requestPool.requestTimeout();
        if (request.timeout().isPresent() && request.timeout().orElseThrow().compareTo(timeout) < 0) {
            timeout = request.timeout().orElseThrow();
        }
        var expiresAt = plusSaturated(startedAt, timeout);
        if (request.deadline().isPresent() && request.deadline().orElseThrow().expiresAt().isBefore(expiresAt)) {
            expiresAt = request.deadline().orElseThrow().expiresAt();
        }
        var effectiveTimeout = Duration.between(startedAt, expiresAt);
        if (effectiveTimeout.isZero() || effectiveTimeout.isNegative()) {
            throw new ClientTimeoutException(Duration.ZERO, null);
        }
        return new EffectiveDeadline(Deadline.at(expiresAt), effectiveTimeout);
    }

    private static Instant plusSaturated(Instant instant, Duration duration) {
        try {
            return instant.plus(duration);
        } catch (ArithmeticException ignored) {
            return Instant.MAX;
        }
    }

    private final class Exchange {
        private final ClientRequest originalRequest;
        private final EffectiveDeadline deadline;
        private final CompletableFuture<ClientResponse> result = new CompletableFuture<>();
        private final CancellationBridge cancellation;

        private Exchange(ClientRequest originalRequest, EffectiveDeadline deadline) {
            this.originalRequest = originalRequest;
            this.deadline = deadline;
            cancellation = new CancellationBridge(
                    scheduler,
                    originalRequest.cancellationToken().orElse(null),
                    this::onCancellation);
        }

        private void start() {
            result.whenComplete((response, failure) -> {
                if (result.isCancelled()) {
                    cancellation.cancel("client future cancelled");
                }
                cancellation.close();
                activeExchanges.remove(this);
            });
            try {
                cancellation.arm(deadline.deadline());
            } catch (RuntimeException failure) {
                completeFailure(failure);
                return;
            }
            if (!cancellation.isSignalled()) {
                attempt(originalRequest, 0, 1);
            }
        }

        private void cancel(String reason) {
            cancellation.cancel(reason);
        }

        private void onCancellation(CancellationBridge.Signal signal) {
            if (signal.timedOut()) {
                completeFailure(new ClientTimeoutException(deadline.timeout(), null));
            } else {
                completeFailure(new ClientCancelledException(signal.reason()));
            }
        }

        private void attempt(ClientRequest request, int redirectsFollowed, int retryAttempt) {
            if (result.isDone() || cancellation.isSignalled()) {
                return;
            }
            var remaining = deadline.deadline().remaining();
            if (remaining.isZero()) {
                cancellation.timeout();
                return;
            }
            var acquisition = requestPool.acquire();
            cancellation.register(acquisition);
            acquisition.whenCompleteAsync((lease, admissionFailure) -> {
                cancellation.unregister(acquisition);
                if (admissionFailure != null) {
                    if (!cancellation.isSignalled()) {
                        completeFailure(unwrap(admissionFailure));
                    }
                    return;
                }
                if (cancellation.isSignalled()) {
                    lease.close();
                    return;
                }
                send(lease, request, redirectsFollowed, retryAttempt);
            }, orchestration);
        }

        private void send(ClientRequestPool.Lease lease, ClientRequest request, int redirectsFollowed, int retryAttempt) {
            final ClientTransport.Exchange outbound;
            var remaining = deadline.deadline().remaining();
            if (remaining.isZero()) {
                lease.close();
                cancellation.timeout();
                return;
            }
            try {
                outbound = transport.send(request);
            } catch (RuntimeException failure) {
                lease.close();
                handleTransportFailure(request, redirectsFollowed, retryAttempt, failure);
                return;
            }
            cancellation.register(outbound.cancellationHandle());
            outbound.completion().whenCompleteAsync((response, transportFailure) -> {
                cancellation.unregister(outbound.cancellationHandle());
                lease.close();
                if (cancellation.isSignalled()) {
                    return;
                }
                try {
                    if (transportFailure != null) {
                        handleTransportFailure(request, redirectsFollowed, retryAttempt, unwrap(transportFailure));
                        return;
                    }
                    handleResponse(request, response, redirectsFollowed, retryAttempt);
                } catch (RuntimeException callbackFailure) {
                    completeFailure(callbackFailure);
                }
            }, orchestration);
        }

        private void handleResponse(
                ClientRequest request,
                ClientTransport.TransportResponse response,
                int redirectsFollowed,
                int retryAttempt) {
            var redirected = redirectRequest(request, response, redirectsFollowed);
            if (redirected != null) {
                // Redirects are governed by RedirectPolicy. They deliberately do not consume a
                // RetryPolicy attempt, so a transient response after any permitted redirect
                // still receives its full configured retry budget.
                attempt(redirected, redirectsFollowed + 1, retryAttempt);
                return;
            }
            if (retryPolicy.shouldRetryResponse(request, retryAttempt, response.status())) {
                scheduleRetry(request, redirectsFollowed, retryAttempt + 1, retryAttempt);
                return;
            }
            completeResponse(new ClientResponse(
                    response.status(), response.headers(), response.body(), request.uri(), redirectsFollowed));
        }

        private void handleTransportFailure(
                ClientRequest request,
                int redirectsFollowed,
                int retryAttempt,
                Throwable failure) {
            if (cancellation.isSignalled()) {
                return;
            }
            if (failure instanceof CancellationException) {
                cancellation.cancel("transport exchange cancelled");
                return;
            }
            if (failure instanceof ClientLimitExceededException
                    || failure instanceof ClientRequestPoolRejectedException
                    || failure instanceof IllegalArgumentException
                    || failure instanceof UnsupportedOperationException) {
                completeFailure(failure);
                return;
            }
            if (retryPolicy.shouldRetryTransportFailure(request, retryAttempt)) {
                scheduleRetry(request, redirectsFollowed, retryAttempt + 1, retryAttempt);
                return;
            }
            completeFailure(failure);
        }

        private void scheduleRetry(
                ClientRequest request,
                int redirectsFollowed,
                int nextRetryAttempt,
                int retryNumber) {
            final Duration delay;
            try {
                delay = BackoffDelays.checkedDelay(retryPolicy.backoff(), retryNumber);
            } catch (RuntimeException failure) {
                completeFailure(failure);
                return;
            }
            if (cancellation.isSignalled()) {
                return;
            }
            var retryHandle = new CompletableFuture<Void>();
            var scheduledRetry = new AtomicReference<ScheduledFuture<?>>();
            retryHandle.whenComplete((ignored, failure) -> {
                if (!retryHandle.isCancelled()) {
                    return;
                }
                var scheduled = scheduledRetry.get();
                if (scheduled != null) {
                    scheduled.cancel(true);
                }
            });
            cancellation.register(retryHandle);
            if (cancellation.isSignalled()) {
                return;
            }
            try {
                var scheduled = scheduler.schedule(() -> {
                            // A retry handle represents only pending delay work. Drop it before
                            // making the next attempt so a long retry sequence cannot retain
                            // every already-fired ScheduledFuture until the logical exchange ends.
                            cancellation.unregister(retryHandle);
                            retryHandle.complete(null);
                            attempt(request, redirectsFollowed, nextRetryAttempt);
                        },
                        toNanosSaturated(delay),
                        TimeUnit.NANOSECONDS);
                scheduledRetry.set(scheduled);
                if (retryHandle.isCancelled()) {
                    scheduled.cancel(true);
                }
            } catch (RejectedExecutionException failure) {
                cancellation.unregister(retryHandle);
                retryHandle.completeExceptionally(failure);
                if (!cancellation.isSignalled()) {
                    completeFailure(new IllegalStateException("WaveClient is closed", failure));
                }
            }
        }

        private ClientRequest redirectRequest(
                ClientRequest request,
                ClientTransport.TransportResponse response,
                int redirectsFollowed) {
            if (!redirectPolicy.allows(request, response.status(), redirectsFollowed)) {
                return null;
            }
            var location = response.headers().first("Location");
            if (location.isEmpty()) {
                return null;
            }
            try {
                var target = request.uri().resolve(URI.create(location.get()));
                if (!isFollowableHttpTarget(target)) {
                    return null;
                }
                var headers = request.headers();
                if (!sameOrigin(request.uri(), target)) {
                    headers = headers.toBuilder()
                            .remove("Authorization")
                            .remove("Proxy-Authorization")
                            .remove("Cookie")
                            .build();
                }
                var builder = request.toBuilder().uri(target).headers(headers);
                if (response.status() == 303 && !request.method().name().equals("HEAD")) {
                    builder.method(HttpMethod.GET).body(new byte[0]).retrySafe(true);
                }
                return builder.build();
            } catch (IllegalArgumentException failure) {
                return null;
            }
        }

        private void completeResponse(ClientResponse response) {
            result.complete(response);
        }

        private void completeFailure(Throwable failure) {
            if (failure instanceof CompletionException completion && completion.getCause() != null) {
                failure = completion.getCause();
            }
            result.completeExceptionally(failure);
        }
    }

    private static boolean isFollowableHttpTarget(URI target) {
        if (!target.isAbsolute() || target.getHost() == null) {
            return false;
        }
        return target.getScheme().equalsIgnoreCase("http") || target.getScheme().equalsIgnoreCase("https");
    }

    private static boolean sameOrigin(URI left, URI right) {
        return left.getScheme() != null && right.getScheme() != null
                && left.getHost() != null && right.getHost() != null
                && left.getScheme().equalsIgnoreCase(right.getScheme())
                && left.getHost().equalsIgnoreCase(right.getHost())
                && effectivePort(left) == effectivePort(right);
    }

    private static int effectivePort(URI uri) {
        if (uri.getPort() >= 0) {
            return uri.getPort();
        }
        return uri.getScheme().equalsIgnoreCase("https") ? 443 : 80;
    }

    private static Throwable unwrap(Throwable failure) {
        var current = failure;
        while ((current instanceof CompletionException || current instanceof ExecutionException) && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static long toNanosSaturated(Duration duration) {
        try {
            return duration.toNanos();
        } catch (ArithmeticException ignored) {
            return Long.MAX_VALUE;
        }
    }

    private record EffectiveDeadline(Deadline deadline, Duration timeout) {
        private EffectiveDeadline {
            Objects.requireNonNull(deadline, "deadline");
            Objects.requireNonNull(timeout, "timeout");
        }
    }

    /** Builder for a standalone {@link WaveClient}. */
    public static final class Builder {
        private ClientRequestPool requestPool;
        private RetryPolicy retryPolicy = RetryPolicy.none();
        private RedirectPolicy redirectPolicy = RedirectPolicy.never();
        private ProxyPolicy proxyPolicy = ProxyPolicy.direct();
        private Http2Config http2 = Http2Config.disabled();
        private ClientTlsConfig tls = ClientTlsConfig.system();

        private Builder() {
        }

        /** Uses a caller-owned bounded request pool, which remains open when this client closes. */
        public Builder requestPool(ClientRequestPool requestPool) {
            this.requestPool = Objects.requireNonNull(requestPool, "requestPool");
            return this;
        }

        /** Sets the immutable retry policy. */
        public Builder retryPolicy(RetryPolicy retryPolicy) {
            this.retryPolicy = Objects.requireNonNull(retryPolicy, "retryPolicy");
            return this;
        }

        /** Sets the immutable redirect policy. */
        public Builder redirectPolicy(RedirectPolicy redirectPolicy) {
            this.redirectPolicy = Objects.requireNonNull(redirectPolicy, "redirectPolicy");
            return this;
        }

        /**
         * Sets the immutable proxy-routing policy.
         *
         * <p>The byte HTTP/1.1 transport supports ordinary HTTP absolute-form proxy requests.
         * With an enabled HTTP/2 configuration, HTTPS requests use a bounded CONNECT tunnel before
         * origin TLS/ALPN negotiation. A {@code PREFER} downgrade to HTTP/1.1 cannot use that
         * tunnel yet; use {@code REQUIRE} when an HTTPS proxy route must remain supported.</p>
         */
        public Builder proxyPolicy(ProxyPolicy proxyPolicy) {
            this.proxyPolicy = Objects.requireNonNull(proxyPolicy, "proxyPolicy");
            return this;
        }

        /**
         * Selects TLS/ALPN HTTP/2 behavior for HTTPS origins, including CONNECT-tunnelled routes.
         *
         * <p>The default remains HTTP/1.1. In {@link Http2Config.Mode#PREFER} mode an ALPN peer
         * which selects HTTP/1.1 is retried through the owned HTTP/1.1 transport; {@code REQUIRE}
         * rejects that downgrade before an application response is exposed.</p>
         */
        public Builder http2(Http2Config http2) {
            this.http2 = Objects.requireNonNull(http2, "http2");
            return this;
        }

        /** Sets platform or explicitly anchored TLS trust while retaining hostname verification. */
        public Builder tls(ClientTlsConfig tls) {
            this.tls = Objects.requireNonNull(tls, "tls");
            return this;
        }

        /** Builds a reusable bounded client. */
        public WaveClient build() {
            return new WaveClient(this);
        }
    }
}



