package io.waveio.testkit;

import io.waveio.server.RunningServer;
import io.waveio.server.ServerSpec;
import io.waveio.server.WaveServer;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Objects;

/** A small AutoCloseable fixture that starts the public WaveIO server facade. */
public final class EmbeddedServer implements AutoCloseable {
    private final RunningServer server;
    private final Duration shutdownGrace;

    private EmbeddedServer(RunningServer server, Duration shutdownGrace) {
        this.server = server;
        this.shutdownGrace = shutdownGrace;
    }

    /** Starts a fixture from a fully explicit server specification. */
    public static EmbeddedServer start(ServerSpec spec) {
        Objects.requireNonNull(spec, "spec");
        return new EmbeddedServer(WaveServer.start(spec), spec.timeouts().shutdownGrace());
    }

    /** Returns the loopback socket address selected by the public server facade. */
    public InetSocketAddress address() {
        return server.address();
    }

    /** Stops the fixture with the shutdown grace declared by its specification. */
    @Override
    public void close() {
        server.stop(shutdownGrace);
    }
}
