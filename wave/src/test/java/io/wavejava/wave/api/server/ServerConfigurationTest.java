package io.wavejava.wave.api.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class ServerConfigurationTest {
    @Test
    void serverLimitsHaveFinitePositiveDefaultsAndCanBeCopied() {
        var defaults = ServerLimits.defaults();

        assertEquals(ServerLimits.DEFAULT_MAXIMUM_CONNECTIONS, defaults.maximumConnections());
        assertEquals(ServerLimits.DEFAULT_MAXIMUM_IN_FLIGHT_REQUESTS, defaults.maximumInFlightRequests());
        assertEquals(ServerLimits.DEFAULT_MAXIMUM_REQUEST_LINE_BYTES, defaults.maximumRequestLineBytes());
        assertEquals(ServerLimits.DEFAULT_MAXIMUM_REQUEST_HEADER_BYTES, defaults.maximumRequestHeaderBytes());
        assertEquals(ServerLimits.DEFAULT_MAXIMUM_REQUEST_BODY_BYTES, defaults.maximumRequestBodyBytes());
        assertEquals(
                ServerLimits.DEFAULT_MAXIMUM_PENDING_REQUESTS_PER_CONNECTION,
                defaults.maximumPendingRequestsPerConnection());
        assertEquals(
                ServerLimits.DEFAULT_MAXIMUM_PENDING_RESPONSE_BYTES_PER_CONNECTION,
                defaults.maximumPendingResponseBytesPerConnection());
        assertEquals(
                ServerLimits.DEFAULT_MAXIMUM_OUTBOUND_STREAM_BYTES_PER_CONNECTION,
                defaults.maximumOutboundStreamBytesPerConnection());

        var custom = defaults.toBuilder()
                .maximumConnections(100)
                .maximumInFlightRequests(50)
                .maximumRequestLineBytes(512)
                .maximumRequestHeaderBytes(1_024)
                .maximumRequestBodyBytes(2_048)
                .maximumPendingRequestsPerConnection(3)
                .maximumPendingResponseBytesPerConnection(4_096)
                .maximumOutboundStreamBytesPerConnection(2_048)
                .build();

        assertEquals(100, custom.maximumConnections());
        assertEquals(2_048, custom.maximumRequestBodyBytes());
        assertEquals(4_096, custom.maximumPendingResponseBytesPerConnection());
        assertEquals(2_048, custom.maximumStreamingResponseBytesPerConnection());
        assertEquals(ServerLimits.defaults(), ServerLimits.builder().build());
    }

    @Test
    void serverLimitsRejectZeroAndNegativeBudgets() {
        assertThrows(IllegalArgumentException.class, () -> ServerLimits.builder().maximumConnections(0));
        assertThrows(IllegalArgumentException.class, () -> ServerLimits.builder().maximumInFlightRequests(-1));
        assertThrows(IllegalArgumentException.class, () -> ServerLimits.builder().maximumRequestLineBytes(0));
        assertThrows(IllegalArgumentException.class, () -> ServerLimits.builder().maximumRequestHeaderBytes(-1));
        assertThrows(IllegalArgumentException.class, () -> ServerLimits.builder().maximumRequestBodyBytes(0));
        assertThrows(
                IllegalArgumentException.class,
                () -> ServerLimits.builder().maximumPendingRequestsPerConnection(-1));
        assertThrows(
                IllegalArgumentException.class,
                () -> ServerLimits.builder().maximumPendingResponseBytesPerConnection(0));
        assertThrows(
                IllegalArgumentException.class,
                () -> ServerLimits.builder().maximumOutboundStreamBytesPerConnection(-1));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ServerLimits(1, 1, 1, 1, 1, 1, 0));
    }

    @Test
    void serverTimeoutsHaveFinitePositiveDefaultsAndCanBeCopied() {
        var defaults = ServerTimeouts.defaults();

        assertEquals(Duration.ofSeconds(30), defaults.requestTimeout());
        assertEquals(Duration.ofSeconds(30), defaults.readTimeout());
        assertEquals(Duration.ofSeconds(30), defaults.writeTimeout());
        assertEquals(Duration.ofSeconds(30), defaults.shutdownTimeout());
        assertEquals(Duration.ofMinutes(1), defaults.idleTimeout());

        var custom = defaults.toBuilder()
                .requestTimeout(Duration.ofSeconds(2))
                .readTimeout(Duration.ofSeconds(3))
                .writeTimeout(Duration.ofSeconds(4))
                .idleTimeout(Duration.ofSeconds(5))
                .shutdownTimeout(Duration.ofSeconds(6))
                .build();

        assertEquals(Duration.ofSeconds(2), custom.requestTimeout());
        assertEquals(Duration.ofSeconds(6), custom.shutdownTimeout());
        assertEquals(ServerTimeouts.defaults(), ServerTimeouts.builder().build());
    }

    @Test
    void serverTimeoutsRejectNullZeroAndNegativeDurations() {
        assertThrows(NullPointerException.class, () -> ServerTimeouts.builder().requestTimeout(null));
        assertThrows(IllegalArgumentException.class, () -> ServerTimeouts.builder().readTimeout(Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> ServerTimeouts.builder().writeTimeout(Duration.ofNanos(-1)));
        assertThrows(IllegalArgumentException.class, () -> ServerTimeouts.builder().idleTimeout(Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> ServerTimeouts.builder().shutdownTimeout(Duration.ofSeconds(-1)));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ServerTimeouts(Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ZERO));
    }

    @Test
    void tlsConfigRequiresCertificateAndKeyAndKeepsTrustOptional() {
        var certificate = Path.of("certificates", "server.pem");
        var key = Path.of("certificates", "server.key");
        var trust = Path.of("certificates", "clients.pem");

        var withoutTrust = TlsConfig.builder().certificateChain(certificate).privateKey(key).build();
        assertEquals(certificate, withoutTrust.certificateChain());
        assertEquals(key, withoutTrust.privateKey());
        assertFalse(withoutTrust.trustCertificateChain().isPresent());

        var withTrust = withoutTrust.toBuilder().trustCertificateChain(trust).build();
        assertEquals(trust, withTrust.trustCertificateChain().orElseThrow());
        assertEquals(withoutTrust, withTrust.toBuilder().clearTrustCertificateChain().build());
    }

    @Test
    void tlsConfigRejectsMissingOrEmptyPathsWithoutFilesystemAccess() {
        assertThrows(NullPointerException.class, () -> TlsConfig.builder().certificateChain(null));
        assertThrows(IllegalArgumentException.class, () -> TlsConfig.builder().privateKey(Path.of("")));
        assertThrows(IllegalArgumentException.class, () -> TlsConfig.builder().trustCertificateChain(Path.of("")));
        assertThrows(NullPointerException.class, () -> TlsConfig.builder().build());
        assertThrows(
                NullPointerException.class,
                () -> TlsConfig.builder().certificateChain(Path.of("cert.pem")).build());
        assertTrue(TlsConfig.builder()
                .certificateChain(Path.of("cert.pem"))
                .privateKey(Path.of("key.pem"))
                .build()
                .trustCertificateChain()
                .isEmpty());
    }
}
