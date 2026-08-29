package io.waveio.http.internal;

import java.time.Duration;

public record ServerConfig(String host, int port, int maxInitialLineLength,
        int maxHeaderSize, int maxBodySize, Duration readTimeout,
        Duration shutdownTimeout, int maxPendingRequestsPerConnection,
        Duration handlerTimeout, int maxConnections, Duration writeTimeout,
        Duration idleTimeout, TlsConfig tls, io.waveio.http.body.BodyCodec bodyCodec) {

    public ServerConfig(String host, int port, int maxInitialLineLength,
            int maxHeaderSize, int maxBodySize, Duration readTimeout,
            Duration shutdownTimeout, int maxPendingRequestsPerConnection,
            Duration handlerTimeout, int maxConnections, Duration writeTimeout,
            Duration idleTimeout, TlsConfig tls) {
        this(host, port, maxInitialLineLength, maxHeaderSize, maxBodySize, readTimeout,
                shutdownTimeout, maxPendingRequestsPerConnection, handlerTimeout,
                maxConnections, writeTimeout, idleTimeout, tls, null);
    }
    public ServerConfig(String host, int port, int maxInitialLineLength,
            int maxHeaderSize, int maxBodySize, Duration readTimeout,
            Duration shutdownTimeout) {
        this(host, port, maxInitialLineLength, maxHeaderSize, maxBodySize,
                readTimeout, shutdownTimeout, 16, Duration.ofSeconds(30), 10_000,
                Duration.ofSeconds(30), Duration.ofSeconds(30), null);
    }

    public ServerConfig(String host, int port, int maxInitialLineLength,
            int maxHeaderSize, int maxBodySize, Duration readTimeout,
            Duration shutdownTimeout, int maxPendingRequestsPerConnection) {
        this(host, port, maxInitialLineLength, maxHeaderSize, maxBodySize,
                readTimeout, shutdownTimeout, maxPendingRequestsPerConnection,
                Duration.ofSeconds(30), 10_000, Duration.ofSeconds(30),
                Duration.ofSeconds(30), null);
    }

    public ServerConfig(String host, int port, int maxInitialLineLength,
            int maxHeaderSize, int maxBodySize, Duration readTimeout,
            Duration shutdownTimeout, int maxPendingRequestsPerConnection,
            Duration handlerTimeout) {
        this(host, port, maxInitialLineLength, maxHeaderSize, maxBodySize,
                readTimeout, shutdownTimeout, maxPendingRequestsPerConnection,
                handlerTimeout, 10_000, Duration.ofSeconds(30), Duration.ofSeconds(30), null);
    }

    public ServerConfig(String host, int port, int maxInitialLineLength,
            int maxHeaderSize, int maxBodySize, Duration readTimeout,
            Duration shutdownTimeout, int maxPendingRequestsPerConnection,
            Duration handlerTimeout, int maxConnections) {
        this(host, port, maxInitialLineLength, maxHeaderSize, maxBodySize,
                readTimeout, shutdownTimeout, maxPendingRequestsPerConnection,
                handlerTimeout, maxConnections, Duration.ofSeconds(30),
                Duration.ofSeconds(30), null);
    }

    public ServerConfig(String host, int port, int maxInitialLineLength,
            int maxHeaderSize, int maxBodySize, Duration readTimeout,
            Duration shutdownTimeout, int maxPendingRequestsPerConnection,
            Duration handlerTimeout, int maxConnections, Duration writeTimeout,
            Duration idleTimeout) {
        this(host, port, maxInitialLineLength, maxHeaderSize, maxBodySize, readTimeout,
                shutdownTimeout, maxPendingRequestsPerConnection, handlerTimeout,
                maxConnections, writeTimeout, idleTimeout, null);
    }

    public ServerConfig {
        if (host == null || host.isBlank()) throw new IllegalArgumentException("host must not be blank");
        if (port < 0 || port > 65_535) throw new IllegalArgumentException("port must be between 0 and 65535");
        if (maxInitialLineLength <= 0) throw new IllegalArgumentException("maxInitialLineLength must be positive");
        if (maxHeaderSize <= 0) throw new IllegalArgumentException("maxHeaderSize must be positive");
        if (maxBodySize < 0) throw new IllegalArgumentException("maxBodySize must not be negative");
        if (readTimeout == null || readTimeout.isNegative() || readTimeout.isZero()) {
            throw new IllegalArgumentException("readTimeout must be positive");
        }
        if (shutdownTimeout == null || shutdownTimeout.isNegative() || shutdownTimeout.isZero()) {
            throw new IllegalArgumentException("shutdownTimeout must be positive");
        }
        if (maxPendingRequestsPerConnection <= 0) {
            throw new IllegalArgumentException("maxPendingRequestsPerConnection must be positive");
        }
        if (handlerTimeout == null || handlerTimeout.isNegative() || handlerTimeout.isZero()) {
            throw new IllegalArgumentException("handlerTimeout must be positive");
        }
        if (maxConnections <= 0) {
            throw new IllegalArgumentException("maxConnections must be positive");
        }
        if (writeTimeout == null || writeTimeout.isNegative() || writeTimeout.isZero()) {
            throw new IllegalArgumentException("writeTimeout must be positive");
        }
        if (idleTimeout == null || idleTimeout.isNegative() || idleTimeout.isZero()) {
            throw new IllegalArgumentException("idleTimeout must be positive");
        }
    }
}
