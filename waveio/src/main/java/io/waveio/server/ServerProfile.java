package io.waveio.server;

import io.waveio.execution.ExecutionConfig;
import java.time.Duration;

/** Named, documented, and inspectable server resource profiles. */
public enum ServerProfile {
    /** Convenient bounded settings for local application development. */
    DEVELOPMENT(256, processors(), Duration.ofSeconds(30), 4_096, 16_384, 65_536,
            Duration.ofSeconds(60), Duration.ofSeconds(10), 1, 256),
    /** Small deterministic settings for embedded tests. */
    TESTING(8, 1, Duration.ofSeconds(1), 4_096, 8_192, 4_096,
            Duration.ofSeconds(2), Duration.ofSeconds(1), 1, 8),
    /** Bounded settings for explicit production deployment. */
    PRODUCTION(1_024, processors(), Duration.ofSeconds(30), 8_192, 16_384, 65_536,
            Duration.ofSeconds(60), Duration.ofSeconds(30), 1, 1_024);

    private final ServerOptions options;

    ServerProfile(int queueCapacity, int parallelism, Duration deadline, int initialLine, int headers, int chunk,
                  Duration idle, Duration shutdown, int observerParallelism, int observerQueueCapacity) {
        options = new ServerOptions(new ExecutionConfig(queueCapacity, parallelism, deadline),
                new ServerLimits(initialLine, headers, chunk), new ServerTimeouts(idle, shutdown),
                new ObservationConfig(observerParallelism, observerQueueCapacity));
    }

    /** Returns this profile's fully resolved immutable settings. */
    public ServerOptions options() {
        return options;
    }

    private static int processors() {
        return Math.max(2, Runtime.getRuntime().availableProcessors());
    }
}
