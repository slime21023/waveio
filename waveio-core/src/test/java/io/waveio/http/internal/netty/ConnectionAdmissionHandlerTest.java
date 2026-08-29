package io.waveio.http.internal.netty;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

class ConnectionAdmissionHandlerTest {

    @Test
    void enforcesOneGlobalLimitAcrossChannelsAndReleasesOnClose() {
        var admission = new ConnectionAdmissionHandler(2);
        var first = new EmbeddedChannel(admission);
        var second = new EmbeddedChannel(admission);
        var rejected = new EmbeddedChannel(admission);

        assertTrue(first.isActive());
        assertTrue(second.isActive());
        assertFalse(rejected.isActive());
        assertEquals(2, admission.activeConnections());

        first.close();
        assertEquals(1, admission.activeConnections());

        var replacement = new EmbeddedChannel(admission);
        assertTrue(replacement.isActive());
        assertEquals(2, admission.activeConnections());

        second.close();
        replacement.close();
        rejected.finishAndReleaseAll();
        assertEquals(0, admission.activeConnections());
    }

    @Test
    void closeAndInactiveReleasePermitOnlyOnce() {
        var admission = new ConnectionAdmissionHandler(1);
        var channel = new EmbeddedChannel(admission);

        channel.close();
        channel.finishAndReleaseAll();

        assertEquals(0, admission.activeConnections());
    }
}
