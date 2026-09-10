package io.wavejava.wave.api.application;

import io.wavejava.wave.api.config.Config;
import io.wavejava.wave.api.http.ExceptionMapper;
import io.wavejava.wave.api.lifecycle.Service;
import io.wavejava.wave.api.lifecycle.ServiceContext;
import io.wavejava.wave.api.lifecycle.ServiceLifecycle;
import io.wavejava.wave.api.middleware.Middleware;
import io.wavejava.wave.api.registry.Registry;
import io.wavejava.wave.api.routing.Routes;
import io.wavejava.wave.spi.SpiCatalog;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/**
 * An immutable application assembled before a server accepts traffic.
 *
 * <p>It owns no execution or transport resources; those are created for each server run by the
 * unexported runtime.</p>
 */
public final class WaveApp {
    private final Routes routes;
    private final List<Middleware> middleware;
    private final List<ExceptionMapperRegistration<?>> exceptionMappers;
    private final Config config;
    private final Registry registry;
    private final List<Service> services;
    private final SpiCatalog providers;

    private WaveApp(
            Routes routes,
            List<Middleware> middleware,
            List<ExceptionMapperRegistration<?>> exceptionMappers,
            Config config,
            Registry registry,
            List<Service> services,
            SpiCatalog providers) {
        this.routes = Objects.requireNonNull(routes, "routes");
        this.middleware = List.copyOf(middleware);
        this.exceptionMappers = List.copyOf(exceptionMappers);
        this.config = Objects.requireNonNull(config, "config");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.services = List.copyOf(services);
        this.providers = Objects.requireNonNull(providers, "providers");
    }

    /** Starts an application builder. */
    public static Builder builder() {
        return new Builder();
    }

    /** Returns this application's immutable route table. */
    public Routes routes() {
        return routes;
    }

    /** Returns middleware in immutable request-entry order. */
    public List<Middleware> middleware() {
        return middleware;
    }

    /** Returns exception mappers in their immutable registration order. */
    public List<ExceptionMapperRegistration<?>> exceptionMappers() {
        return exceptionMappers;
    }

    /** Returns the immutable configuration snapshot assembled for this application. */
    public Config config() {
        return config;
    }

    /** Returns the immutable framework-service registry assembled for this application. */
    public Registry registry() {
        return registry;
    }

    /** Returns registered framework services in application registration order. */
    public List<Service> services() {
        return services;
    }

    /** Returns the immutable, startup-validated SPI provider snapshot for this application. */
    public SpiCatalog providers() {
        return providers;
    }

    /** Mutable builder that validates all application composition before returning an app. */
    public static final class Builder {
        private final List<Middleware> middleware = new ArrayList<>();
        private final List<ExceptionMapperRegistration<?>> exceptionMappers = new ArrayList<>();
        private final List<Service> services = new ArrayList<>();
        private Routes routes = Routes.builder().build();
        private Config config = Config.empty();
        private Registry registry = Registry.empty();
        private SpiCatalog providers;
        private boolean discoverSpiProviders = true;
        private boolean built;

        private Builder() {
        }

        /** Appends middleware in request registration order. */
        public Builder middleware(Middleware middleware) {
            ensureMutable();
            this.middleware.add(Objects.requireNonNull(middleware, "middleware"));
            return this;
        }

        /** Sets the complete route table. */
        public Builder routes(Routes routes) {
            ensureMutable();
            this.routes = Objects.requireNonNull(routes, "routes");
            return this;
        }

        /** Builds and sets routes from one callback. */
        public Builder routes(Consumer<? super Routes.Builder> definitions) {
            ensureMutable();
            this.routes = Routes.of(Objects.requireNonNull(definitions, "definitions"));
            return this;
        }

        /** Sets the immutable configuration snapshot available to framework services at startup. */
        public Builder config(Config config) {
            ensureMutable();
            this.config = Objects.requireNonNull(config, "config");
            return this;
        }

        /** Sets the immutable framework-service registry available to services at startup. */
        public Builder registry(Registry registry) {
            ensureMutable();
            this.registry = Objects.requireNonNull(registry, "registry");
            return this;
        }

        /** Registers one framework service for every server run of this application. */
        public Builder service(Service service) {
            ensureMutable();
            services.add(Objects.requireNonNull(service, "service"));
            return this;
        }

        /** Registers framework services in iteration order. */
        public Builder services(Iterable<? extends Service> services) {
            ensureMutable();
            Objects.requireNonNull(services, "services");
            for (var service : services) {
                service(service);
            }
            return this;
        }

        /**
         * Sets a prevalidated SPI provider snapshot instead of automatic ServiceLoader discovery.
         *
         * <p>Use this for hermetic tests or applications that intentionally assemble providers
         * from an explicit module graph. Service providers still create a fresh service per server
         * run; parser, renderer, and session-store providers remain available through
         * {@link WaveApp#providers()}.</p>
         */
        public Builder providers(SpiCatalog providers) {
            ensureMutable();
            this.providers = Objects.requireNonNull(providers, "providers");
            return this;
        }

        /**
         * Enables or disables bounded ServiceLoader discovery when no explicit catalog is supplied.
         *
         * <p>Discovery is enabled by default and completes during {@link #build()}, before a
         * {@link WaveServer} can bind a listener. Disabling it does not affect an explicitly set
         * {@link #providers(SpiCatalog)} snapshot.</p>
         */
        public Builder discoverSpiProviders(boolean discoverSpiProviders) {
            ensureMutable();
            this.discoverSpiProviders = discoverSpiProviders;
            return this;
        }

        /** Registers one exception mapper. The first matching mapper is selected. */
        public <T extends Exception> Builder exceptionMapper(
                Class<T> exceptionType, ExceptionMapper<? super T> mapper) {
            ensureMutable();
            var type = Objects.requireNonNull(exceptionType, "exceptionType");
            if (!exceptionMappers.stream().map(ExceptionMapperRegistration::exceptionType).noneMatch(type::equals)) {
                throw new IllegalArgumentException("An exception mapper is already registered for " + type.getName());
            }
            exceptionMappers.add(new ExceptionMapperRegistration<>(type, Objects.requireNonNull(mapper, "mapper")));
            return this;
        }

        /** Validates and creates the immutable application. */
        public WaveApp build() {
            ensureMutable();
            built = true;
            validateMapperOrder();
            validateServiceGraph();
            var resolvedProviders = providers == null
                    ? (discoverSpiProviders ? SpiCatalog.discover() : SpiCatalog.empty())
                    : providers;
            return new WaveApp(routes, middleware, exceptionMappers, config, registry, services, resolvedProviders);
        }

        private void validateMapperOrder() {
            var types = new HashSet<Class<? extends Exception>>();
            for (var registration : exceptionMappers) {
                if (!types.add(registration.exceptionType())) {
                    throw new IllegalArgumentException("Duplicate exception mapper type: "
                            + registration.exceptionType().getName());
                }
            }
        }

        private void validateServiceGraph() {
            ServiceLifecycle.builder()
                    .context(new ServiceContext(config, registry))
                    .addAll(services)
                    .build();
        }

        private void ensureMutable() {
            if (built) {
                throw new IllegalStateException("WaveApp.Builder has already built an application");
            }
        }
    }

    /** One exception type and its mapper, retained as immutable application assembly data. */
    public record ExceptionMapperRegistration<T extends Exception>(
            Class<T> exceptionType, ExceptionMapper<? super T> mapper) {
        public ExceptionMapperRegistration {
            Objects.requireNonNull(exceptionType, "exceptionType");
            Objects.requireNonNull(mapper, "mapper");
        }
    }
}
