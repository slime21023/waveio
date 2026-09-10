package io.wavejava.wave.api.lifecycle;

import io.wavejava.wave.api.config.Config;
import io.wavejava.wave.api.registry.Registry;
import java.util.Objects;

/**
 * Immutable framework context shared with services during startup.
 *
 * <p>It deliberately exposes only immutable configuration and the framework registry. Business
 * dependencies should remain constructor-injected rather than being looked up from this context.</p>
 */
public final class ServiceContext {
    private static final ServiceContext EMPTY = new ServiceContext(Config.empty(), Registry.empty());

    private final Config config;
    private final Registry registry;

    /** Creates a context from immutable configuration and framework services. */
    public ServiceContext(Config config, Registry registry) {
        this.config = Objects.requireNonNull(config, "config");
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    /** Returns a context with no configuration values and no registry services. */
    public static ServiceContext empty() {
        return EMPTY;
    }

    /** Returns the immutable application configuration snapshot. */
    public Config config() {
        return config;
    }

    /** Returns the immutable framework-service registry. */
    public Registry registry() {
        return registry;
    }
}
