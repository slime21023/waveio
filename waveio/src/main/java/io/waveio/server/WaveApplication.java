package io.waveio.server;

import io.waveio.http.ErrorHandler;
import io.waveio.http.Handler;
import io.waveio.http.Routes;
import io.waveio.registry.Registry;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/** Immutable application assembly used by the public WaveServer facade. */
public final class WaveApplication {
    private final Handler handler;
    private final Registry registry;
    private final List<Service> services;
    private final List<Observer> observers;

    private WaveApplication(Handler handler, Registry registry, List<Service> services, List<Observer> observers) {
        this.handler = Objects.requireNonNull(handler, "handler");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.services = List.copyOf(services);
        this.observers = List.copyOf(observers);
    }

    /** Returns a mutable builder for one application assembly. */
    public static Builder builder() {
        return new Builder();
    }

    Handler handler() { return handler; }
    Registry registry() { return registry; }
    List<Service> services() { return services; }
    List<Observer> observers() { return observers; }

    /** Mutable application configuration applied before the server starts. */
    public static final class Builder {
        private final Registry.Builder registry = Registry.builder();
        private final Routes.Builder routes = Routes.builder();
        private final List<Service> services = new ArrayList<>();
        private final List<Observer> observers = new ArrayList<>();
        private ErrorHandler errors = ErrorHandler.fallback();
        private boolean errorHandlerConfigured;
        private boolean built;

        private Builder() { }

        /** Configures typed application services. */
        public Builder services(Consumer<Registry.Builder> configuration) {
            ensureMutable();
            Objects.requireNonNull(configuration, "configuration").accept(registry);
            return this;
        }

        /** Configures endpoint routes and middleware. */
        public Builder routes(Consumer<Routes.Builder> configuration) {
            ensureMutable();
            Objects.requireNonNull(configuration, "configuration").accept(routes);
            return this;
        }

        /** Registers a lifecycle service owned by the running server. */
        public Builder service(Service service) {
            ensureMutable();
            services.add(Objects.requireNonNull(service, "service"));
            return this;
        }

        /** Registers an asynchronous application observer. */
        public Builder observe(Observer observer) {
            ensureMutable();
            observers.add(Objects.requireNonNull(observer, "observer"));
            return this;
        }

        /** Sets the sole application error policy. */
        public Builder errorHandler(ErrorHandler handler) {
            ensureMutable();
            if (errorHandlerConfigured) {
                throw new IllegalStateException("an error handler has already been configured");
            }
            errors = Objects.requireNonNull(handler, "handler");
            errorHandlerConfigured = true;
            return this;
        }

        /** Installs an extension immediately in registration order. */
        public Builder install(WaveExtension extension) {
            ensureMutable();
            Objects.requireNonNull(extension, "extension").configure(this);
            return this;
        }

        /** Builds the immutable application assembly. */
        public WaveApplication build() {
            ensureMutable();
            built = true;
            return new WaveApplication(routes.build(errors), registry.build(), services, observers);
        }

        private void ensureMutable() {
            if (built) {
                throw new IllegalStateException("application builder has already built an application");
            }
        }
    }
}
