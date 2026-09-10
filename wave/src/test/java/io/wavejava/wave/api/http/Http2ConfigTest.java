package io.wavejava.wave.api.http;

import io.wavejava.wave.api.http.Http2Config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class Http2ConfigTest {
    @Test
    void disabledConfigurationDoesNotAdvertiseHttp2() {
        var config = Http2Config.disabled();

        assertEquals(Http2Config.Mode.DISABLED, config.mode());
        assertFalse(config.isEnabled());
        assertFalse(config.requiresHttp2());
        assertTrue(config.maximumConcurrentStreams() > 0);
        assertTrue(config.maximumInboundBytesPerConnection() > 0);
        assertTrue(config.maximumOutboundBytesPerConnection() > 0);
    }

    @Test
    void builderUsesAlpnPreferenceAndRetainsFiniteBudgets() {
        var config = Http2Config.builder().build();

        assertEquals(Http2Config.Mode.PREFER, config.mode());
        assertTrue(config.isEnabled());
        assertFalse(config.requiresHttp2());
        assertTrue(config.maximumOutboundBytesPerStream() <= config.maximumOutboundBytesPerConnection());
    }

    @Test
    void requireModeIsExplicitAndFrameAndBudgetInvariantsFailClosed() {
        var required = Http2Config.builder().mode(Http2Config.Mode.REQUIRE).build();

        assertTrue(required.requiresHttp2());
        assertThrows(IllegalArgumentException.class, () -> Http2Config.builder()
                .maximumFrameBytes(16 * 1024 - 1)
                .build());
        assertThrows(IllegalArgumentException.class, () -> Http2Config.builder()
                .maximumOutboundBytesPerConnection(10)
                .maximumOutboundBytesPerStream(11)
                .build());
        assertThrows(IllegalArgumentException.class, () -> Http2Config.builder()
                .maximumConcurrentStreams(2)
                .maximumInboundBytesPerConnection(32 * 1024)
                .initialStreamWindowBytes(32 * 1024)
                .build());
        assertThrows(IllegalArgumentException.class, () -> Http2Config.builder()
                .initialConnectionWindowBytes(Http2Config.MINIMUM_INITIAL_CONNECTION_WINDOW_BYTES - 1)
                .build());
    }
}
