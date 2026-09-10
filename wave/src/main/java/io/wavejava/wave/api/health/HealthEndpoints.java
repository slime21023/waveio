package io.wavejava.wave.api.health;

import io.wavejava.wave.api.http.Response;
import io.wavejava.wave.api.routing.Handler;
import java.util.LinkedHashMap;
import java.util.Objects;

/** Factories for explicit liveness and readiness route handlers. */
public final class HealthEndpoints {
    private HealthEndpoints() {
    }

    /** Returns a handler that reports process liveness without dependency work. */
    public static Handler liveness(HealthRegistry registry) {
        var source = Objects.requireNonNull(registry, "registry");
        return (request, response) -> write(response, source.liveness());
    }

    /** Returns a handler that reports lifecycle-aware, bounded dependency readiness. */
    public static Handler readiness(HealthRegistry registry) {
        var source = Objects.requireNonNull(registry, "registry");
        return (request, response) -> write(response, source.readiness());
    }

    private static void write(Response response, HealthStatus status) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("status", status.state().name());
        payload.put("summary", status.summary());
        if (!status.details().isEmpty()) {
            payload.put("details", status.details());
        }
        response.status(status.isUp() ? 200 : 503).json(payload);
    }
}
