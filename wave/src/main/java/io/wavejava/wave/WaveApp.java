package io.wavejava.wave;

import io.wavejava.wave.api.config.Config;
import io.wavejava.wave.api.http.ExceptionMapper;
import io.wavejava.wave.api.http.HttpException;
import io.wavejava.wave.api.http.Problem;
import io.wavejava.wave.api.http.Request;
import io.wavejava.wave.api.http.Response;
import io.wavejava.wave.api.http.ResponseState;
import io.wavejava.wave.api.lifecycle.Service;
import io.wavejava.wave.api.lifecycle.ServiceContext;
import io.wavejava.wave.api.lifecycle.ServiceLifecycle;
import io.wavejava.wave.api.middleware.Middleware;
import io.wavejava.wave.api.registry.Registry;
import io.wavejava.wave.api.middleware.Outcome;
import io.wavejava.wave.api.routing.RouteMatch;
import io.wavejava.wave.api.routing.Routes;
import io.wavejava.wave.runtime.ApplicationResult;
import io.wavejava.wave.spi.ProviderContext;
import io.wavejava.wave.spi.SpiCatalog;
import io.wavejava.wave.spi.SpiConfigurationException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * An immutable application assembled before a server accepts traffic.
 *
 * <p>The application can also be dispatched in memory by the test fixture. It owns no transport
 * resources; configuration, registry, and service declarations are immutable assembly data. A
 * fresh service lifecycle is created for each server run, and every invocation receives a fresh
 * {@link Response}, so request dispatch is safe to invoke concurrently.</p>
 */
public final class WaveApp {
    private static final Logger LOGGER = LoggerFactory.getLogger(WaveApp.class);

    private final Routes routes;
    private final List<Middleware> middleware;
    private final List<MapperRegistration<?>> exceptionMappers;
    private final Config config;
    private final Registry registry;
    private final List<Service> services;
    private final SpiCatalog providers;

    private WaveApp(
            Routes routes,
            List<Middleware> middleware,
            List<MapperRegistration<?>> exceptionMappers,
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

    /**
     * Creates a fresh, validated lifecycle for one server run of this application.
     *
     * <p>A lifecycle is one-shot, so each {@link WaveServer} creates its own instance before
     * binding a transport. The service definitions and the context's configuration and registry
     * remain immutable application assembly data.</p>
     */
    public ServiceLifecycle newServiceLifecycle() {
        var runServices = new ArrayList<>(services);
        var providerContext = new ProviderContext(config, registry);
        for (var provider : providers.serviceProviders()) {
            try {
                runServices.add(Objects.requireNonNull(
                        provider.create(providerContext),
                        () -> "Service provider " + provider.id() + " returned null"));
            } catch (SpiConfigurationException failure) {
                throw failure;
            } catch (Exception failure) {
                throw new SpiConfigurationException(
                        "Could not create lifecycle service from provider '" + provider.id() + "'", failure);
            }
        }
        return ServiceLifecycle.builder()
                .context(new ServiceContext(config, registry))
                .addAll(runServices)
                .build();
    }

    /** Dispatches one request using a new response builder. */
    public Response handle(Request request) {
        return dispatch(Objects.requireNonNull(request, "request")).response();
    }

    /**
     * Dispatches one request into its invocation-owned response.
     *
     * <p>This method is public for test adapters. Server transports must call it only outside an
     * event loop.</p>
     */
    public void handle(Request request, Response response) {
        dispatch(request, response);
    }

    /** Internal transport dispatch preserving the application result before a wire write. */
    ApplicationResult dispatch(Request request) {
        var response = Response.create();
        return dispatch(Objects.requireNonNull(request, "request"), response, ignored -> { });
    }

    /** Internal transport dispatch into an invocation-owned response. */
    ApplicationResult dispatch(Request request, Response response) {
        return dispatch(request, response, ignored -> { });
    }

    /** Internal transport dispatch that reports a low-cardinality route once it is selected. */
    ApplicationResult dispatch(Request request, Consumer<String> routeObserver) {
        var response = Response.create();
        return dispatch(Objects.requireNonNull(request, "request"), response, routeObserver);
    }

    private ApplicationResult dispatch(Request request, Response response, Consumer<String> routeObserver) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(response, "response");
        Objects.requireNonNull(routeObserver, "routeObserver");

        var entered = new ArrayList<Middleware>();
        Request activeRequest = request;
        Outcome outcome = Outcome.success();
        var routePattern = "<middleware>";
        try {
            var continueToRoute = true;
            for (var current : middleware) {
                entered.add(current);
                current.onRequest(activeRequest, response);
                if (response.isCommitted()) {
                    continueToRoute = false;
                    break;
                }
            }

            if (continueToRoute) {
                var match = routes.match(activeRequest);
                routePattern = routeLabel(match);
                routeObserver.accept(routePattern);
                if (!match.isMatched()) {
                    writeRoutingResponse(match, response);
                } else {
                    activeRequest = activeRequest.withPathParameters(match.pathParameters());
                    for (var current : entered) {
                        current.onRoute(activeRequest, match, response);
                        if (response.isCommitted()) {
                            continueToRoute = false;
                            break;
                        }
                    }
                    if (continueToRoute) {
                        match.requireHandler().handle(activeRequest, response);
                        if (response.state() == ResponseState.OPEN) {
                            throw new IllegalStateException("handler returned without committing a response");
                        }
                    }
                }
            }
        } catch (Exception failure) {
            outcome = Outcome.failure(Outcome.Kind.APPLICATION_FAILURE, failure);
            mapFailure(failure, activeRequest, response);
        } finally {
            for (var index = entered.size() - 1; index >= 0; index--) {
                try {
                    entered.get(index).onResponse(activeRequest, response, outcome);
                } catch (RuntimeException observerFailure) {
                    LOGGER.warn("Middleware onResponse hook failed", observerFailure);
                }
            }
        }
        return new ApplicationResult(response, outcome, routePattern);
    }

    private static String routeLabel(RouteMatch match) {
        return match.route().map(metadata -> metadata.pathPattern()).orElseGet(() -> switch (match.kind()) {
            case NOT_FOUND -> "<not-found>";
            case METHOD_NOT_ALLOWED -> "<method-not-allowed>";
            case AUTOMATIC_OPTIONS -> "<automatic-options>";
            case MATCHED -> throw new IllegalStateException("matched route metadata was absent");
        });
    }

    private static void writeRoutingResponse(RouteMatch match, Response response) {
        switch (match.kind()) {
            case NOT_FOUND -> response.problem(Problem.of(404, "Not Found"));
            case METHOD_NOT_ALLOWED -> response.header("Allow", match.allowHeader())
                    .problem(Problem.of(405, "Method Not Allowed"));
            case AUTOMATIC_OPTIONS -> response.header("Allow", match.allowHeader()).status(204);
            case MATCHED -> throw new IllegalArgumentException("matched routes require handler dispatch");
        }
    }

    private void mapFailure(Exception failure, Request request, Response response) {
        if (response.state() != ResponseState.OPEN) {
            LOGGER.warn("Request failed after response commitment", failure);
            return;
        }
        try {
            var mapper = findMapper(failure);
            if (mapper != null) {
                mapper.map(failure, request, response);
            } else if (failure instanceof HttpException httpException) {
                response.problem(httpException.problem());
            } else {
                response.problem(Problem.of(500, "Internal Server Error"));
            }
            if (response.state() == ResponseState.OPEN) {
                throw new IllegalStateException("exception mapper returned without committing a response");
            }
        } catch (Exception mapperFailure) {
            LOGGER.error("Exception mapper failed", mapperFailure);
            if (response.state() == ResponseState.OPEN) {
                response.problem(Problem.of(500, "Internal Server Error"));
            }
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private ExceptionMapper<Exception> findMapper(Exception failure) {
        for (var registration : exceptionMappers) {
            if (registration.exceptionType().isInstance(failure)) {
                return (ExceptionMapper) registration.mapper();
            }
        }
        return null;
    }

    /** Mutable builder that validates all application composition before returning an app. */
    public static final class Builder {
        private final List<Middleware> middleware = new ArrayList<>();
        private final List<MapperRegistration<?>> exceptionMappers = new ArrayList<>();
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
            if (!exceptionMappers.stream().map(MapperRegistration::exceptionType).noneMatch(type::equals)) {
                throw new IllegalArgumentException("An exception mapper is already registered for " + type.getName());
            }
            exceptionMappers.add(new MapperRegistration<>(type, Objects.requireNonNull(mapper, "mapper")));
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

    private record MapperRegistration<T extends Exception>(Class<T> exceptionType, ExceptionMapper<? super T> mapper) {
        private MapperRegistration {
            Objects.requireNonNull(exceptionType, "exceptionType");
            Objects.requireNonNull(mapper, "mapper");
        }
    }
}

