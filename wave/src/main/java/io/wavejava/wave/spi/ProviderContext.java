package io.wavejava.wave.spi;

import io.wavejava.wave.api.config.Config;
import io.wavejava.wave.api.registry.Registry;
import java.util.Objects;

/** Immutable public context offered to SPI factories at application assembly time. */
public record ProviderContext(Config config, Registry registry) {
    /** Creates a context backed by immutable application configuration and framework services. */
    public ProviderContext {
        config = Objects.requireNonNull(config, "config");
        registry = Objects.requireNonNull(registry, "registry");
    }

    /** Returns an empty context suitable for isolated provider contract tests. */
    public static ProviderContext empty() {
        return new ProviderContext(Config.empty(), Registry.empty());
    }
}
