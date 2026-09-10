package io.wavejava.wave.netty;

import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.ApplicationProtocolConfig;
import io.netty.handler.ssl.ApplicationProtocolNames;
import io.wavejava.wave.api.server.Http2Config;
import io.wavejava.wave.api.server.TlsConfig;
import java.io.IOException;
import java.util.Objects;

/** Creates Netty TLS contexts behind the public path-only {@link TlsConfig} boundary. */
final class TlsSupport {
    private TlsSupport() {
    }

    static SslContext build(TlsConfig config) {
        return build(config, Http2Config.disabled());
    }

    /** Builds TLS material, optionally advertising HTTP/2 and HTTP/1.1 through ALPN. */
    static SslContext build(TlsConfig config, Http2Config http2) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(http2, "http2");
        try {
            var builder = SslContextBuilder.forServer(config.certificateChain().toFile(), config.privateKey().toFile());
            config.trustCertificateChain().ifPresent(path -> builder.trustManager(path.toFile()));
            if (http2.isEnabled()) {
                builder.applicationProtocolConfig(new ApplicationProtocolConfig(
                        ApplicationProtocolConfig.Protocol.ALPN,
                        ApplicationProtocolConfig.SelectorFailureBehavior.NO_ADVERTISE,
                        ApplicationProtocolConfig.SelectedListenerFailureBehavior.ACCEPT,
                        ApplicationProtocolNames.HTTP_2,
                        ApplicationProtocolNames.HTTP_1_1));
            }
            return builder.build();
        } catch (Exception failure) {
            throw new IllegalStateException("Could not create server TLS context from configured certificate material", failure);
        }
    }
}
