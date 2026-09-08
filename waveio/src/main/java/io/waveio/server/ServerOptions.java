package io.waveio.server;

import io.waveio.execution.ExecutionConfig;
import java.util.Objects;

/** Immutable resolved operational configuration for one running server. */
public record ServerOptions(ExecutionConfig execution, ServerLimits limits, ServerTimeouts timeouts,
                            ObservationConfig observations) {
    /** Validates every resolved operational setting. */
    public ServerOptions {
        Objects.requireNonNull(execution, "execution");
        Objects.requireNonNull(limits, "limits");
        Objects.requireNonNull(timeouts, "timeouts");
        Objects.requireNonNull(observations, "observations");
    }
}
