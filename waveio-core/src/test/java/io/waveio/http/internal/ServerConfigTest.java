package io.waveio.http.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class ServerConfigTest {

    @Test
    void createsValidConfig() {
        var config = new ServerConfig("0.0.0.0", 8080, 4096, 8192, 1024,
                Duration.ofSeconds(15), Duration.ofSeconds(5), 7, Duration.ofSeconds(3));

        assertEquals("0.0.0.0", config.host());
        assertEquals(8080, config.port());
        assertEquals(4096, config.maxInitialLineLength());
        assertEquals(8192, config.maxHeaderSize());
        assertEquals(1024, config.maxBodySize());
        assertEquals(Duration.ofSeconds(15), config.readTimeout());
        assertEquals(Duration.ofSeconds(5), config.shutdownTimeout());
        assertEquals(7, config.maxPendingRequestsPerConnection());
        assertEquals(Duration.ofSeconds(3), config.handlerTimeout());
        assertEquals(10_000, config.maxConnections());
        assertEquals(Duration.ofSeconds(30), config.writeTimeout());
        assertEquals(Duration.ofSeconds(30), config.idleTimeout());
    }

    @Test
    void validatesHost() {
        assertThrows(IllegalArgumentException.class,
                () -> new ServerConfig(null, 8080, 4096, 8192, 1024, Duration.ofSeconds(1), Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class,
                () -> new ServerConfig("", 8080, 4096, 8192, 1024, Duration.ofSeconds(1), Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class,
                () -> new ServerConfig("   ", 8080, 4096, 8192, 1024, Duration.ofSeconds(1), Duration.ofSeconds(1)));
    }

    @Test
    void validatesPort() {
        assertThrows(IllegalArgumentException.class,
                () -> new ServerConfig("localhost", -1, 4096, 8192, 1024, Duration.ofSeconds(1), Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class,
                () -> new ServerConfig("localhost", 65536, 4096, 8192, 1024, Duration.ofSeconds(1), Duration.ofSeconds(1)));
    }

    @Test
    void validatesSizes() {
        assertThrows(IllegalArgumentException.class,
                () -> new ServerConfig("localhost", 8080, 0, 8192, 1024, Duration.ofSeconds(1), Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class,
                () -> new ServerConfig("localhost", 8080, -1, 8192, 1024, Duration.ofSeconds(1), Duration.ofSeconds(1)));

        assertThrows(IllegalArgumentException.class,
                () -> new ServerConfig("localhost", 8080, 4096, 0, 1024, Duration.ofSeconds(1), Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class,
                () -> new ServerConfig("localhost", 8080, 4096, -1, 1024, Duration.ofSeconds(1), Duration.ofSeconds(1)));

        assertThrows(IllegalArgumentException.class,
                () -> new ServerConfig("localhost", 8080, 4096, 8192, -1, Duration.ofSeconds(1), Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class,
                () -> new ServerConfig("localhost", 8080, 4096, 8192, 1024,
                        Duration.ofSeconds(1), Duration.ofSeconds(1), 0));
        assertThrows(IllegalArgumentException.class,
                () -> new ServerConfig("localhost", 8080, 4096, 8192, 1024,
                        Duration.ofSeconds(1), Duration.ofSeconds(1), 1,
                        Duration.ofSeconds(1), 0));
    }

    @Test
    void validatesTimeouts() {
        assertThrows(IllegalArgumentException.class,
                () -> new ServerConfig("localhost", 8080, 4096, 8192, 1024, null, Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class,
                () -> new ServerConfig("localhost", 8080, 4096, 8192, 1024, Duration.ZERO, Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class,
                () -> new ServerConfig("localhost", 8080, 4096, 8192, 1024, Duration.ofSeconds(-1), Duration.ofSeconds(1)));

        assertThrows(IllegalArgumentException.class,
                () -> new ServerConfig("localhost", 8080, 4096, 8192, 1024, Duration.ofSeconds(1), null));
        assertThrows(IllegalArgumentException.class,
                () -> new ServerConfig("localhost", 8080, 4096, 8192, 1024, Duration.ofSeconds(1), Duration.ZERO));
        assertThrows(IllegalArgumentException.class,
                () -> new ServerConfig("localhost", 8080, 4096, 8192, 1024, Duration.ofSeconds(1), Duration.ofSeconds(-1)));
        assertThrows(IllegalArgumentException.class,
                () -> new ServerConfig("localhost", 8080, 4096, 8192, 1024,
                        Duration.ofSeconds(1), Duration.ofSeconds(1), 1, Duration.ZERO));
        assertThrows(IllegalArgumentException.class,
                () -> new ServerConfig("localhost", 8080, 4096, 8192, 1024,
                        Duration.ofSeconds(1), Duration.ofSeconds(1), 1,
                        Duration.ofSeconds(1), 1, Duration.ZERO, Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class,
                () -> new ServerConfig("localhost", 8080, 4096, 8192, 1024,
                        Duration.ofSeconds(1), Duration.ofSeconds(1), 1,
                        Duration.ofSeconds(1), 1, Duration.ofSeconds(1), Duration.ZERO));
    }
}
