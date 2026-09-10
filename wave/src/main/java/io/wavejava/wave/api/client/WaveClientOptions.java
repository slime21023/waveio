package io.wavejava.wave.api.client;

import io.wavejava.wave.api.http.Http2Config;
import java.util.Objects;
import java.util.Optional;

/** Immutable settings used when {@link io.wavejava.wave.Wave} creates a {@link WaveClient}. */
public final class WaveClientOptions {
    private final ClientRequestPool requestPool;
    private final RetryPolicy retryPolicy;
    private final RedirectPolicy redirectPolicy;
    private final ProxyPolicy proxyPolicy;
    private final Http2Config http2;
    private final ClientTlsConfig tls;

    private WaveClientOptions(Builder builder) {
        requestPool = builder.requestPool;
        retryPolicy = builder.retryPolicy;
        redirectPolicy = builder.redirectPolicy;
        proxyPolicy = builder.proxyPolicy;
        http2 = builder.http2;
        tls = builder.tls;
    }

    public static Builder builder() {
        return new Builder();
    }

    public Optional<ClientRequestPool> requestPool() {
        return Optional.ofNullable(requestPool);
    }

    public RetryPolicy retryPolicy() {
        return retryPolicy;
    }

    public RedirectPolicy redirectPolicy() {
        return redirectPolicy;
    }

    public ProxyPolicy proxyPolicy() {
        return proxyPolicy;
    }

    public Http2Config http2() {
        return http2;
    }

    public ClientTlsConfig tls() {
        return tls;
    }

    /** Builder for immutable {@link WaveClientOptions}. */
    public static final class Builder {
        private ClientRequestPool requestPool;
        private RetryPolicy retryPolicy = RetryPolicy.none();
        private RedirectPolicy redirectPolicy = RedirectPolicy.never();
        private ProxyPolicy proxyPolicy = ProxyPolicy.direct();
        private Http2Config http2 = Http2Config.disabled();
        private ClientTlsConfig tls = ClientTlsConfig.system();

        private Builder() {
        }

        public Builder requestPool(ClientRequestPool requestPool) {
            this.requestPool = Objects.requireNonNull(requestPool, "requestPool");
            return this;
        }

        public Builder retryPolicy(RetryPolicy retryPolicy) {
            this.retryPolicy = Objects.requireNonNull(retryPolicy, "retryPolicy");
            return this;
        }

        public Builder redirectPolicy(RedirectPolicy redirectPolicy) {
            this.redirectPolicy = Objects.requireNonNull(redirectPolicy, "redirectPolicy");
            return this;
        }

        public Builder proxyPolicy(ProxyPolicy proxyPolicy) {
            this.proxyPolicy = Objects.requireNonNull(proxyPolicy, "proxyPolicy");
            return this;
        }

        public Builder http2(Http2Config http2) {
            this.http2 = Objects.requireNonNull(http2, "http2");
            return this;
        }

        public Builder tls(ClientTlsConfig tls) {
            this.tls = Objects.requireNonNull(tls, "tls");
            return this;
        }

        public WaveClientOptions build() {
            return new WaveClientOptions(this);
        }
    }
}
