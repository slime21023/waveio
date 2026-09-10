package io.wavejava.wave.api.health;

import java.util.concurrent.CompletionStage;

/** An asynchronously completable readiness dependency check. */
public interface HealthCheck {
    /** Returns a stable, low-cardinality check name used in safe diagnostics. */
    String name();

    /** Starts or returns one bounded check operation using the supplied cancellation context. */
    CompletionStage<HealthStatus> check(HealthCheckContext context);
}
