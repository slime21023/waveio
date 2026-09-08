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
        PlaintextServer transport = PlaintextServer.start(spec.address(), spec.handler(), spec.registry(), spec.executionConfig(),
                new TransportConfig(spec.limits().maximumInitialLineLength(), spec.limits().maximumHeaderSize(),
                        spec.limits().maximumChunkSize(), spec.timeouts().idleTimeout()));
        return new Running(transport);
    }
    private static final class Running implements RunningServer {
        private final PlaintextServer transport;
        Running(PlaintextServer transport) { this.transport = transport; }
        @Override public InetSocketAddress address() { return new InetSocketAddress("127.0.0.1", transport.port()); }
        @Override public void stop(Duration grace) { transport.stop(Objects.requireNonNull(grace, "grace")); }
        @Override public void close() { transport.close(); }
    }
}
