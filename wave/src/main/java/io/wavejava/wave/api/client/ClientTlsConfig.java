package io.wavejava.wave.api.client;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * TLS trust configuration for Wave's owned outbound HTTP transports.
 *
 * <p>The default uses the platform trust store and retains hostname verification. Supplying a PEM
 * trust-certificate chain selects an explicit private trust source; it never disables endpoint
 * identity checks or accepts arbitrary certificates. Client certificates and custom cipher suites
 * are intentionally outside the Wave 1.0 client contract.</p>
 */
public final class ClientTlsConfig {
    private static final ClientTlsConfig SYSTEM = new ClientTlsConfig(null);

    private final Path trustCertificateChain;

    private ClientTlsConfig(Path trustCertificateChain) {
        this.trustCertificateChain = trustCertificateChain == null
                ? null
                : requirePath(trustCertificateChain, "trustCertificateChain");
    }

    /** Returns the configuration that delegates trust to the platform store. */
    public static ClientTlsConfig system() {
        return SYSTEM;
    }

    /** Starts a builder for an explicit client trust configuration. */
    public static Builder builder() {
        return new Builder();
    }

    /** Returns an optional PEM certificate chain selected as the client's explicit trust source. */
    public Optional<Path> trustCertificateChain() {
        return Optional.ofNullable(trustCertificateChain);
    }

    private static Path requirePath(Path value, String name) {
        Objects.requireNonNull(value, name);
        if (value.toString().isBlank()) {
            throw new IllegalArgumentException(name + " must not be empty");
        }
        return value;
    }

    /** Mutable builder for an immutable {@link ClientTlsConfig}. */
    public static final class Builder {
        private Path trustCertificateChain;

        private Builder() {
        }

        /** Adds a PEM certificate chain used as the client's explicit trust source. */
        public Builder trustCertificateChain(Path trustCertificateChain) {
            this.trustCertificateChain = requirePath(trustCertificateChain, "trustCertificateChain");
            return this;
        }

        /** Builds the immutable client TLS configuration. */
        public ClientTlsConfig build() {
            return trustCertificateChain == null ? system() : new ClientTlsConfig(trustCertificateChain);
        }
    }
}
