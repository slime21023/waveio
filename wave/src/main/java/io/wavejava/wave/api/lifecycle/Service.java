package io.wavejava.wave.api.lifecycle;

import java.util.Set;
import java.util.concurrent.CompletionStage;

/**
 * A framework-owned asynchronous resource with explicit lifecycle dependencies.
 *
 * <p>Implementations must return a stage that completes only after the corresponding operation
 * has finished. The lifecycle does not run application work on a transport event loop; callers
 * choose how the service itself schedules any blocking work.</p>
 */
public interface Service {
    /**
     * Stable identifier used for dependency declarations and diagnostics.
     *
     * <p>Services of the same implementation class that are both registered must override this
     * value with distinct IDs.</p>
     */
    default String id() {
        return getClass().getName();
    }

    /** IDs of services that must have started successfully before this service starts. */
    default Set<String> dependencies() {
        return Set.of();
    }

    /** Starts this service after all declared dependencies have completed startup. */
    CompletionStage<Void> start(ServiceContext context);

    /** Stops this service. The lifecycle calls it in reverse successful-startup order. */
    CompletionStage<Void> stop();
}
