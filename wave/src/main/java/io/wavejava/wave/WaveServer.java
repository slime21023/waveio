package io.wavejava.wave;

import io.wavejava.wave.netty.Http1Server;
import io.wavejava.wave.api.lifecycle.ServiceLifecycle;
import io.wavejava.wave.api.health.HealthRegistry;
import io.wavejava.wave.api.observability.Observability;
import io.wavejava.wave.api.server.ServerLimits;
import io.wavejava.wave.api.server.ServerTimeouts;
import io.wavejava.wave.api.server.TlsConfig;
import io.wavejava.wave.api.server.RequestBodyMode;
import io.wavejava.wave.api.server.ForwardedHeaderPolicy;
import io.wavejava.wave.api.server.Http2Config;
import java.util.Objects;

/** Configures and starts a bounded HTTP/1.1 server for one immutable {@link WaveApp}. */
public final class WaveServer {
    private final WaveApp application;
    private int port = -1;
    private ServerLimits limits = ServerLimits.defaults();
    private ServerTimeouts timeouts = ServerTimeouts.defaults();
    private TlsConfig tls;
    private ForwardedHeaderPolicy forwardedHeaders = ForwardedHeaderPolicy.disabled();
    private Http2Config http2 = Http2Config.disabled();
    private HealthRegistry health;
    private Observability observability = Observability.disabled();
    private boolean compression;
    private RequestBodyMode requestBodyMode = RequestBodyMode.AGGREGATED;
    private boolean started;

    private WaveServer(WaveApp application) {
        this.application = application;
    }

    static WaveServer forApp(WaveApp application) {
        return new WaveServer(application);
    }

    /** Selects a local port; use {@code 0} to let the operating system select an ephemeral port. */
    public WaveServer listen(int port) {
        ensureNotStarted();
        if (port < 0 || port > 65_535) {
            throw new IllegalArgumentException("port must be between 0 and 65535: " + port);
        }
        this.port = port;
        return this;
    }

    /** Sets all finite resource limits for this server. */
    public WaveServer limits(ServerLimits limits) {
        ensureNotStarted();
        this.limits = Objects.requireNonNull(limits, "limits");
        return this;
    }

    /** Sets all finite timeout budgets for this server. */
    public WaveServer timeouts(ServerTimeouts timeouts) {
        ensureNotStarted();
        this.timeouts = Objects.requireNonNull(timeouts, "timeouts");
        return this;
    }

    /** Enables TLS using certificate and key paths that will be validated during startup. */
    public WaveServer tls(TlsConfig tls) {
        ensureNotStarted();
        this.tls = Objects.requireNonNull(tls, "tls");
        return this;
    }

    /**
     * Selects the explicit trusted-proxy policy used to derive {@code Request.publicAddress()}.
     *
     * <p>The default trusts no peer. Forwarding headers from an untrusted or malformed source are
     * ignored rather than changing the application's visible public origin.</p>
     */
    public WaveServer forwardedHeaders(ForwardedHeaderPolicy forwardedHeaders) {
        ensureNotStarted();
        this.forwardedHeaders = Objects.requireNonNull(forwardedHeaders, "forwardedHeaders");
        return this;
    }

    /**
     * Enables bounded TLS/ALPN HTTP/2 negotiation or requires it according to {@code config}.
     *
     * <p>Wave does not accept h2c prior-knowledge or HTTP/1.1 Upgrade; an enabled configuration
     * therefore requires {@link #tls(TlsConfig)} before startup.</p>
     */
    public WaveServer http2(Http2Config http2) {
        ensureNotStarted();
        this.http2 = Objects.requireNonNull(http2, "http2");
        return this;
    }

    /**
     * Associates a lifecycle-aware health registry with this server run.
     *
     * <p>Routes remain explicitly registered through {@code HealthEndpoints}; this association
     * only drives readiness from service startup, bind failure, and shutdown transitions.</p>
     */
    public WaveServer health(HealthRegistry health) {
        ensureNotStarted();
        this.health = Objects.requireNonNull(health, "health");
        return this;
    }

    /**
     * Associates bounded asynchronous access-log, metrics, and tracing bridges with this server.
     *
     * <p>Bridge callbacks run outside Netty EventLoops and are fed by a finite queue. Prometheus
     * endpoints remain explicit application routes through {@code PrometheusBridge.handler()}.</p>
     */
    public WaveServer observability(Observability observability) {
        ensureNotStarted();
        this.observability = Objects.requireNonNull(observability, "observability");
        return this;
    }

    /** Enables or disables HTTP response compression. Compression is disabled by default. */
    public WaveServer compression(boolean compression) {
        ensureNotStarted();
        this.compression = compression;
        return this;
    }

    /**
     * Selects whether HTTP/1.1 request bodies are aggregated or delivered through Flow.
     *
     * <p>The default is {@link RequestBodyMode#AGGREGATED}, preserving the 0.1--0.3 request
     * contract. {@link RequestBodyMode#STREAMING} exposes a mutually exclusive
     * {@code Request.streamingBody()} source and requires a handler to consume or cancel that
     * source before the connection can accept more request-body input.</p>
     */
    public WaveServer requestBodyMode(RequestBodyMode requestBodyMode) {
        ensureNotStarted();
        this.requestBodyMode = Objects.requireNonNull(requestBodyMode, "requestBodyMode");
        return this;
    }

    /** Convenience switch for {@link #requestBodyMode(RequestBodyMode)}. */
    public WaveServer requestStreaming(boolean enabled) {
        return requestBodyMode(enabled ? RequestBodyMode.STREAMING : RequestBodyMode.AGGREGATED);
    }

    /** Sets the inclusive aggregated request body budget while retaining the other limits. */
    public WaveServer maximumRequestBodyBytes(long maximumRequestBodyBytes) {
        ensureNotStarted();
        if (maximumRequestBodyBytes <= 0 || maximumRequestBodyBytes > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("maximumRequestBodyBytes must be between 1 and "
                    + Integer.MAX_VALUE + ": " + maximumRequestBodyBytes);
        }
        limits = limits.toBuilder().maximumRequestBodyBytes((int) maximumRequestBodyBytes).build();
        return this;
    }

    /**
     * Starts application services, then binds the configured port and begins accepting HTTP/1.1
     * connections.
     *
     * <p>If a service fails to start, its lifecycle rolls back before this method returns and the
     * transport listener is never bound. If transport startup fails after services started, those
     * services are stopped before the failure is rethrown.</p>
     */
    public RunningServer start() {
        ensureNotStarted();
        if (port < 0) {
            throw new IllegalStateException("A port must be selected with listen(int) before start()");
        }
        if (http2.isEnabled() && tls == null) {
            throw new IllegalStateException("HTTP/2 requires TLS/ALPN; h2c is not supported");
        }
        var services = application.newServiceLifecycle();
        started = true;
        if (health != null) {
            health.markStarting();
        }
        try {
            services.start().toCompletableFuture().join();
            var running = Http1Server.start(
                    application::dispatch,
                    port,
                    limits,
                    timeouts,
                    tls,
                    forwardedHeaders,
                    http2,
                    compression,
                    requestBodyMode,
                    services,
                    observability);
            if (health == null) {
                return running;
            }
            health.markReady();
            return new HealthAwareRunningServer(running, health);
        } catch (RuntimeException failure) {
            if (health != null) {
                health.markFailed();
            }
            stopServicesAfterFailedStartup(services, failure);
            throw unwrapLifecycleFailure(failure);
        }
    }

    private void ensureNotStarted() {
        if (started) {
            throw new IllegalStateException("WaveServer has already been started");
        }
    }

    private static void stopServicesAfterFailedStartup(ServiceLifecycle services, RuntimeException startupFailure) {
        if (services.state() != ServiceLifecycle.State.STARTED) {
            return;
        }
        try {
            services.stop().toCompletableFuture().join();
        } catch (RuntimeException stopFailure) {
            startupFailure.addSuppressed(unwrapLifecycleFailure(stopFailure));
        }
    }

    private static RuntimeException unwrapLifecycleFailure(RuntimeException failure) {
        if (failure instanceof java.util.concurrent.CompletionException completionFailure
                && completionFailure.getCause() instanceof RuntimeException cause) {
            return cause;
        }
        return failure;
    }

    /** Makes readiness fail before the delegate begins listener and connection shutdown. */
    private static final class HealthAwareRunningServer implements RunningServer {
        private final RunningServer delegate;
        private final HealthRegistry health;

        private HealthAwareRunningServer(RunningServer delegate, HealthRegistry health) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
            this.health = Objects.requireNonNull(health, "health");
        }

        @Override
        public int port() {
            return delegate.port();
        }

        @Override
        public boolean isRunning() {
            return delegate.isRunning();
        }

        @Override
        public void close() {
            health.markStopping();
            try {
                delegate.close();
            } finally {
                health.markStopped();
            }
        }
    }
}
