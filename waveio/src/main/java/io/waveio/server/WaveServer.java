package io.waveio.server;

import io.waveio.netty.PlaintextServer;
import io.waveio.netty.TransportConfig;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/** Starts WaveIO HTTP servers through the public application facade. */
public final class WaveServer {
    private WaveServer() { }

    /** Starts a development-profile server on the loopback port supplied by the caller. */
    public static RunningServer start(int port, WaveApplication application) {
        return start(new InetSocketAddress("127.0.0.1", port), application, ServerProfile.DEVELOPMENT);
    }

    /** Starts an application with one of the named, inspectable server profiles. */
    public static RunningServer start(InetSocketAddress address, WaveApplication application, ServerProfile profile) {
        Objects.requireNonNull(profile, "profile");
        return start(address, application, profile.options());
    }

    /** Starts an application with fully explicit operational settings. */
    public static RunningServer start(InetSocketAddress address, WaveApplication application, ServerOptions options) {
        Objects.requireNonNull(address, "address");
        Objects.requireNonNull(application, "application");
        Objects.requireNonNull(options, "options");

        ServiceLifecycle services = ServiceLifecycle.start(application.services());
        ObserverDispatcher observers = null;
        try {
            observers = new ObserverDispatcher(application.observers(), options.observations().parallelism(),
                    options.observations().queueCapacity());
            PlaintextServer transport = PlaintextServer.start(address, application.handler(), application.registry(),
                    options.execution(), new TransportConfig(options.limits().maximumInitialLineLength(),
                    options.limits().maximumHeaderSize(), options.limits().maximumChunkSize(), options.timeouts().idleTimeout()));
            observers.submit(new ObservationEvent("server.started", Instant.now()));
            return new Running(transport, services, observers, options);
        } catch (RuntimeException failure) {
            if (observers != null) {
                observers.close();
            }
            services.close();
            throw failure;
        }
    }

    private static final class Running implements RunningServer {
        private final PlaintextServer transport;
        private final ServiceLifecycle services;
        private final ObserverDispatcher observers;
        private final ServerOptions options;

        Running(PlaintextServer transport, ServiceLifecycle services, ObserverDispatcher observers, ServerOptions options) {
            this.transport = transport;
            this.services = services;
            this.observers = observers;
            this.options = options;
        }

        @Override
        public InetSocketAddress address() {
            return transport.address();
        }

        @Override
        public ServerOptions options() {
            return options;
        }

        @Override
        public void stop(Duration grace) {
            try {
                transport.stop(Objects.requireNonNull(grace, "grace"));
            } finally {
                observers.submit(new ObservationEvent("server.stopped", Instant.now()));
                observers.close();
                services.close();
            }
        }

        @Override
        public void close() {
            stop(options.timeouts().shutdownGrace());
        }
    }
}
