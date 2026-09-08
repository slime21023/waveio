package io.waveio.server;

import io.waveio.netty.PlaintextServer;
import io.waveio.netty.TransportConfig;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Objects;

/** Starts WaveIO HTTP servers through the public facade. */
public final class WaveServer {
    private WaveServer() { }
    /** Starts a server after all required configuration has been supplied. */
    public static RunningServer start(ServerSpec spec) {
        Objects.requireNonNull(spec, "spec");
        ServiceLifecycle services = ServiceLifecycle.start(spec.services());
        try {
            PlaintextServer transport = PlaintextServer.start(spec.address(), spec.handler(), spec.registry(), spec.executionConfig(),
                    new TransportConfig(spec.limits().maximumInitialLineLength(), spec.limits().maximumHeaderSize(),
                            spec.limits().maximumChunkSize(), spec.timeouts().idleTimeout()));
            return new Running(transport, services);
        } catch (RuntimeException failure) {
            services.close();
            throw failure;
        }
    }
    private static final class Running implements RunningServer {
        private final PlaintextServer transport;
        private final ServiceLifecycle services;
        Running(PlaintextServer transport, ServiceLifecycle services) { this.transport = transport; this.services = services; }
        @Override public InetSocketAddress address() { return new InetSocketAddress("127.0.0.1", transport.port()); }
        @Override public void stop(Duration grace) { try { transport.stop(Objects.requireNonNull(grace, "grace")); } finally { services.close(); } }
        @Override public void close() { try { transport.close(); } finally { services.close(); } }
    }
}
