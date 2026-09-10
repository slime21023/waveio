package io.wavejava.wave.api.server;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * Filesystem locations required to enable TLS for a server.
 *
 * <p>The certificate chain and private key are mandatory. A trust certificate chain is optional
 * and is intended for a future transport adapter to configure client-certificate verification.
 * This value object performs no filesystem I/O: path existence, readability, and certificate/key
 * compatibility are verified when TLS is started.</p>
 */
public final class TlsConfig {
    private final Path certificateChain;
    private final Path privateKey;
    private final Path trustCertificateChain;

    private TlsConfig(Path certificateChain, Path privateKey, Path trustCertificateChain) {
        this.certificateChain = requirePath(certificateChain, "certificateChain");
        this.privateKey = requirePath(privateKey, "privateKey");
        this.trustCertificateChain = trustCertificateChain == null
                ? null
                : requirePath(trustCertificateChain, "trustCertificateChain");
    }

    /** Returns a builder for TLS configuration. */
    public static Builder builder() {
        return new Builder();
    }

    /** Returns the server certificate-chain file path. */
    public Path certificateChain() {
        return certificateChain;
    }

    /** Returns the server private-key file path. */
    public Path privateKey() {
        return privateKey;
    }

    /** Returns the optional trust certificate-chain file path. */
    public Optional<Path> trustCertificateChain() {
        return Optional.ofNullable(trustCertificateChain);
    }

    /** Returns a builder initialized with this configuration. */
    public Builder toBuilder() {
        return new Builder(this);
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof TlsConfig config)) {
            return false;
        }
        return certificateChain.equals(config.certificateChain)
                && privateKey.equals(config.privateKey)
                && Objects.equals(trustCertificateChain, config.trustCertificateChain);
    }

    @Override
    public int hashCode() {
        return Objects.hash(certificateChain, privateKey, trustCertificateChain);
    }

    @Override
    public String toString() {
        return "TlsConfig[certificateChain=" + certificateChain
                + ", privateKey=" + privateKey
                + ", trustCertificateChain=" + trustCertificateChain + ']';
    }

    private static Path requirePath(Path value, String name) {
        Objects.requireNonNull(value, name);
        if (value.toString().isBlank()) {
            throw new IllegalArgumentException(name + " must not be empty");
        }
        return value;
    }

    /** Mutable builder for an immutable {@link TlsConfig} snapshot. */
    public static final class Builder {
        private Path certificateChain;
        private Path privateKey;
        private Path trustCertificateChain;

        private Builder() {
        }

        private Builder(TlsConfig config) {
            certificateChain = config.certificateChain;
            privateKey = config.privateKey;
            trustCertificateChain = config.trustCertificateChain;
        }

        /** Sets the server certificate-chain file path. */
        public Builder certificateChain(Path certificateChain) {
            this.certificateChain = requirePath(certificateChain, "certificateChain");
            return this;
        }

        /** Sets the server private-key file path. */
        public Builder privateKey(Path privateKey) {
            this.privateKey = requirePath(privateKey, "privateKey");
            return this;
        }

        /** Sets the trust certificate-chain file path used for client-certificate verification. */
        public Builder trustCertificateChain(Path trustCertificateChain) {
            this.trustCertificateChain = requirePath(trustCertificateChain, "trustCertificateChain");
            return this;
        }

        /** Removes the optional trust certificate-chain setting. */
        public Builder clearTrustCertificateChain() {
            trustCertificateChain = null;
            return this;
        }

        /** Builds an immutable TLS configuration. */
        public TlsConfig build() {
            return new TlsConfig(certificateChain, privateKey, trustCertificateChain);
        }
    }
}
