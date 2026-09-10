package io.wavejava.wave.spi;

import io.wavejava.wave.spi.lifecycle.ServiceProvider;
import io.wavejava.wave.spi.render.ParserProvider;
import io.wavejava.wave.spi.render.RendererProvider;
import io.wavejava.wave.spi.session.SessionStoreProvider;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Immutable, validated snapshot of Wave extension providers.
 *
 * <p>Discovery happens during application assembly, never on a request path. Each family carries
 * its own finite cap, and its returned order is deterministic by priority and provider ID.</p>
 */
public final class SpiCatalog {
    private static final SpiCatalog EMPTY = new SpiCatalog(List.of(), List.of(), List.of(), List.of());

    private final List<ParserProvider> parserProviders;
    private final List<RendererProvider> rendererProviders;
    private final List<SessionStoreProvider> sessionStoreProviders;
    private final List<ServiceProvider> serviceProviders;

    private SpiCatalog(
            List<ParserProvider> parserProviders,
            List<RendererProvider> rendererProviders,
            List<SessionStoreProvider> sessionStoreProviders,
            List<ServiceProvider> serviceProviders) {
        this.parserProviders = List.copyOf(parserProviders);
        this.rendererProviders = List.copyOf(rendererProviders);
        this.sessionStoreProviders = List.copyOf(sessionStoreProviders);
        this.serviceProviders = List.copyOf(serviceProviders);
    }

    /** Returns an empty immutable provider snapshot. */
    public static SpiCatalog empty() {
        return EMPTY;
    }

    /** Discovers all built-in Wave SPI families through ServiceLoader with finite default caps. */
    public static SpiCatalog discover() {
        return builder().discover().build();
    }

    /** Starts a bounded manual or ServiceLoader-backed provider catalog. */
    public static Builder builder() {
        return new Builder();
    }

    /** Returns parser providers in deterministic precedence order. */
    public List<ParserProvider> parserProviders() {
        return parserProviders;
    }

    /** Returns renderer providers in deterministic precedence order. */
    public List<RendererProvider> rendererProviders() {
        return rendererProviders;
    }

    /** Returns session-store providers in deterministic precedence order. */
    public List<SessionStoreProvider> sessionStoreProviders() {
        return sessionStoreProviders;
    }

    /** Returns lifecycle-service providers in deterministic precedence order. */
    public List<ServiceProvider> serviceProviders() {
        return serviceProviders;
    }

    /** Mutable builder for a bounded immutable provider catalog. */
    public static final class Builder {
        private int maximumProvidersPerType = SpiProviders.DEFAULT_MAXIMUM_PROVIDERS_PER_TYPE;
        private final List<ParserProvider> parserProviders = new ArrayList<>();
        private final List<RendererProvider> rendererProviders = new ArrayList<>();
        private final List<SessionStoreProvider> sessionStoreProviders = new ArrayList<>();
        private final List<ServiceProvider> serviceProviders = new ArrayList<>();
        private boolean built;

        private Builder() {
        }

        /** Sets the finite cap for each discovered provider family. */
        public Builder maximumProvidersPerType(int maximumProvidersPerType) {
            ensureMutable();
            if (maximumProvidersPerType <= 0) {
                throw new IllegalArgumentException("maximumProvidersPerType must be greater than zero: "
                        + maximumProvidersPerType);
            }
            this.maximumProvidersPerType = maximumProvidersPerType;
            return this;
        }

        /** Adds one parser provider. */
        public Builder parser(ParserProvider provider) {
            ensureMutable();
            parserProviders.add(Objects.requireNonNull(provider, "provider"));
            return this;
        }

        /** Adds one renderer provider. */
        public Builder renderer(RendererProvider provider) {
            ensureMutable();
            rendererProviders.add(Objects.requireNonNull(provider, "provider"));
            return this;
        }

        /** Adds one session-store provider. */
        public Builder sessionStore(SessionStoreProvider provider) {
            ensureMutable();
            sessionStoreProviders.add(Objects.requireNonNull(provider, "provider"));
            return this;
        }

        /** Adds one lifecycle-service provider. */
        public Builder service(ServiceProvider provider) {
            ensureMutable();
            serviceProviders.add(Objects.requireNonNull(provider, "provider"));
            return this;
        }

        /** Adds all providers visible through the current module's ServiceLoader graph. */
        public Builder discover() {
            ensureMutable();
            parserProviders.addAll(SpiProviders.discover(ParserProvider.class, maximumProvidersPerType));
            rendererProviders.addAll(SpiProviders.discover(RendererProvider.class, maximumProvidersPerType));
            sessionStoreProviders.addAll(SpiProviders.discover(SessionStoreProvider.class, maximumProvidersPerType));
            serviceProviders.addAll(SpiProviders.discover(ServiceProvider.class, maximumProvidersPerType));
            return this;
        }

        /** Validates priorities and returns an immutable provider snapshot. */
        public SpiCatalog build() {
            ensureMutable();
            built = true;
            return new SpiCatalog(
                    validateParsers(),
                    validateRenderers(),
                    SpiProviders.validate(SessionStoreProvider.class, sessionStoreProviders, maximumProvidersPerType),
                    SpiProviders.validate(ServiceProvider.class, serviceProviders, maximumProvidersPerType));
        }

        private List<ParserProvider> validateParsers() {
            var validated = SpiProviders.validate(ParserProvider.class, parserProviders, maximumProvidersPerType);
            for (var provider : validated) {
                try {
                    Objects.requireNonNull(provider.parser(),
                            () -> "Parser provider " + provider.id() + " returned null");
                } catch (RuntimeException failure) {
                    throw new SpiConfigurationException(
                            "Could not obtain parser from provider '" + provider.id() + "'", failure);
                }
            }
            return validated;
        }

        private List<RendererProvider> validateRenderers() {
            var validated = SpiProviders.validate(RendererProvider.class, rendererProviders, maximumProvidersPerType);
            for (var provider : validated) {
                try {
                    Objects.requireNonNull(provider.renderer(),
                            () -> "Renderer provider " + provider.id() + " returned null");
                } catch (RuntimeException failure) {
                    throw new SpiConfigurationException(
                            "Could not obtain renderer from provider '" + provider.id() + "'", failure);
                }
            }
            return validated;
        }

        private void ensureMutable() {
            if (built) {
                throw new IllegalStateException("SpiCatalog.Builder has already built a catalog");
            }
        }
    }
}
