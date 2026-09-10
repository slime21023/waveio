package io.wavejava.wave.netty;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.wavejava.wave.api.server.Http2Config;
import io.wavejava.wave.api.server.ServerLimits;
import org.junit.jupiter.api.Test;

class Http2ConnectionStateTest {
    @Test
    void partitionsInboundAndOutboundRetentionAcrossAdmittedStreams() {
        var config = Http2Config.builder()
                .maximumConcurrentStreams(2)
                .initialStreamWindowBytes(64 * 1024)
                .initialConnectionWindowBytes(128 * 1024)
                .maximumInboundBytesPerConnection(128 * 1024)
                .maximumOutboundBytesPerConnection(128 * 1024)
                .maximumOutboundBytesPerStream(96 * 1024)
                .build();
        var limits = ServerLimits.defaults().toBuilder().maximumRequestBodyBytes(64 * 1024).build();
        var state = new Http2ConnectionState(config, limits);

        assertEquals(64 * 1024, state.maximumAggregateRequestBodyBytes());
        assertEquals(16 * 1024, state.outboundBytesPerStream());
        assertTrue(state.tryAcquireStream());
        assertTrue(state.tryAcquireStream());
        assertFalse(state.tryAcquireStream());
        state.releaseStream();
        assertTrue(state.tryAcquireStream());
    }
}
