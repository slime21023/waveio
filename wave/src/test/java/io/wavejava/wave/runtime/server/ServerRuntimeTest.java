package io.wavejava.wave.runtime.server;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.wavejava.wave.Wave;
import io.wavejava.wave.api.observability.Observability;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class ServerRuntimeTest {
    @Test
    void finishShutdownBeginsInvocationShutdownWhenTransportDidNot() {
        var runtime = new ServerRuntime(Wave.app().build(), Observability.disabled());
        runtime.start();

        assertNull(runtime.finishShutdown(Duration.ofSeconds(1)));
        assertTrue(runtime.invocations().isShutdown());
        assertTrue(runtime.invocations().isTerminated());
    }
}
