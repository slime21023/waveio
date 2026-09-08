package io.waveio.server;

import static org.junit.jupiter.api.Assertions.assertThrows;

import io.waveio.execution.ExecutionConfig;
import io.waveio.http.HttpResponse;
import io.waveio.http.HttpStatus;
import io.waveio.registry.Registry;
import io.waveio.task.Task;
import java.net.InetSocketAddress;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class ServerSpecTest {
    @Test void rejectsImplicitOrInvalidResourceConfiguration() {
        assertThrows(IllegalArgumentException.class, () -> new ServerLimits(0, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new ServerTimeouts(Duration.ZERO, Duration.ofSeconds(1), Duration.ofSeconds(1)));
        assertThrows(NullPointerException.class, () -> new ServerSpec(new InetSocketAddress(0), context -> Task.success(null), Registry.empty(), null, new ServerLimits(1, 1, 1), new ServerTimeouts(Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(1))));
    }
}
