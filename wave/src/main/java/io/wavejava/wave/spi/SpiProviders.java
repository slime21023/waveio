package io.wavejava.wave.spi;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;

/**
 * Bounded, deterministic ServiceLoader discovery and priority validation for Wave SPI contracts.
 *
 * <p>Classpath iteration order is never a selection rule. Valid providers are sorted by descending
 * priority and then stable ID; equal priorities in one selection key fail before the application
 * starts. The finite cap prevents a malformed service-resource graph from becoming an unbounded
 * startup allocation.</p>
 */
public final class SpiProviders {
    /** Default maximum providers loaded for one SPI contract. */
    public static final int DEFAULT_MAXIMUM_PROVIDERS_PER_TYPE = 128;

    private SpiProviders() {
    }

    /** Discovers providers visible to the current module using the default finite cap. */
    public static <P extends WaveProvider> List<P> discover(Class<P> contract) {
        return discover(contract, ServiceLoader.load(requireContract(contract)), DEFAULT_MAXIMUM_PROVIDERS_PER_TYPE);
    }

    /**
     * Discovers providers with an explicit class loader, primarily for isolated external-provider
     * contract tests. The caller still receives the same finite default cap and validation.
     */
    public static <P extends WaveProvider> List<P> discover(Class<P> contract, ClassLoader loader) {
        Objects.requireNonNull(loader, "loader");
        return discover(contract, ServiceLoader.load(requireContract(contract), loader), DEFAULT_MAXIMUM_PROVIDERS_PER_TYPE);
    }

    /** Discovers providers using a caller-selected positive cap. */
    public static <P extends WaveProvider> List<P> discover(Class<P> contract, int maximumProviders) {
        return discover(contract, ServiceLoader.load(requireContract(contract)), maximumProviders);
    }

    /**
     * Validates manually assembled providers with a finite cap and returns a deterministic snapshot.
     * This is useful when an application intentionally avoids global ServiceLoader discovery.
     */
    public static <P extends WaveProvider> List<P> validate(
            Class<P> contract, Iterable<? extends P> providers, int maximumProviders) {
        requireContract(contract);
        Objects.requireNonNull(providers, "providers");
        requirePositive(maximumProviders, "maximumProviders");
        var collected = new ArrayList<P>();
        try {
            for (var provider : providers) {
                collected.add(Objects.requireNonNull(provider, "provider"));
                enforceCap(contract, collected.size(), maximumProviders);
            }
        } catch (SpiConfigurationException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new SpiConfigurationException("Could not inspect " + contract.getName() + " providers", failure);
        }
        return validateCollected(contract, collected);
    }

    private static <P extends WaveProvider> List<P> discover(
            Class<P> contract, ServiceLoader<P> loader, int maximumProviders) {
        requireContract(contract);
        requirePositive(maximumProviders, "maximumProviders");
        var collected = new ArrayList<P>();
        try {
            for (var provider : loader) {
                collected.add(Objects.requireNonNull(provider, "provider"));
                enforceCap(contract, collected.size(), maximumProviders);
            }
        } catch (SpiConfigurationException failure) {
            throw failure;
        } catch (ServiceConfigurationError | RuntimeException failure) {
            throw new SpiConfigurationException("Could not load " + contract.getName() + " providers", failure);
        }
        return validateCollected(contract, collected);
    }

    private static <P extends WaveProvider> List<P> validateCollected(Class<P> contract, List<P> providers) {
        var byId = new HashMap<String, P>();
        var bySelectionAndPriority = new HashMap<Selection, P>();
        for (var provider : providers) {
            final String id;
            final String selectionKey;
            try {
                id = WaveProvider.requireStableName(provider.id(), "provider id");
                selectionKey = WaveProvider.requireStableName(provider.selectionKey(), "provider selectionKey");
            } catch (RuntimeException failure) {
                throw new SpiConfigurationException(
                        "Invalid " + contract.getName() + " provider " + provider.getClass().getName(), failure);
            }
            var duplicateId = byId.putIfAbsent(id, provider);
            if (duplicateId != null) {
                throw new SpiConfigurationException("Duplicate " + contract.getName() + " provider id '" + id
                        + "' from " + duplicateId.getClass().getName() + " and " + provider.getClass().getName());
            }
            var selection = new Selection(selectionKey, provider.priority());
            var conflict = bySelectionAndPriority.putIfAbsent(selection, provider);
            if (conflict != null) {
                throw new SpiConfigurationException("Ambiguous " + contract.getName() + " providers for selection key '"
                        + selectionKey + "' at priority " + provider.priority() + ": "
                        + conflict.id() + " and " + id);
            }
        }
        return providers.stream()
                .sorted(Comparator.comparingInt(WaveProvider::priority).reversed().thenComparing(WaveProvider::id))
                .toList();
    }

    private static <P extends WaveProvider> Class<P> requireContract(Class<P> contract) {
        var checked = Objects.requireNonNull(contract, "contract");
        if (!checked.isInterface() || !WaveProvider.class.isAssignableFrom(checked)) {
            throw new IllegalArgumentException("SPI contract must be a WaveProvider interface: " + checked.getName());
        }
        return checked;
    }

    private static void enforceCap(Class<?> contract, int count, int maximumProviders) {
        if (count > maximumProviders) {
            throw new SpiConfigurationException(contract.getName() + " providers exceed the configured cap of "
                    + maximumProviders);
        }
    }

    private static void requirePositive(int value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be greater than zero: " + value);
        }
    }

    private record Selection(String key, int priority) {
    }
}
