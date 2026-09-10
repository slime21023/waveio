package io.wavejava.wave.spi.testing;

import io.wavejava.wave.spi.SpiProviders;
import io.wavejava.wave.spi.WaveProvider;
import java.util.List;
import java.util.Objects;

/**
 * Dependency-free contract kit for provider projects compiled outside the Wave repository.
 *
 * <p>Provider test suites can call these methods with their own service-resource class loader to
 * verify metadata, deterministic ordering, finite discovery, and conflict behavior without
 * reaching into Wave runtime or transport packages.</p>
 */
public final class ProviderContract {
    private ProviderContract() {
    }

    /** Validates a manually assembled provider set using the supplied finite cap. */
    public static <P extends WaveProvider> List<P> validate(
            Class<P> contract, Iterable<? extends P> providers, int maximumProviders) {
        return SpiProviders.validate(contract, providers, maximumProviders);
    }

    /** Discovers and validates providers from an isolated class loader. */
    public static <P extends WaveProvider> List<P> discover(Class<P> contract, ClassLoader loader) {
        Objects.requireNonNull(loader, "loader");
        return SpiProviders.discover(contract, loader);
    }
}
