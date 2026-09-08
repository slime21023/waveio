package io.waveio.testkit;

import io.waveio.server.RunningServer;
import io.waveio.server.ServerOptions;
import io.waveio.server.ServerProfile;
import io.waveio.server.WaveApplication;
import io.waveio.server.WaveServer;
import java.net.InetSocketAddress;
import java.util.Objects;

/** A small AutoCloseable fixture that starts the public WaveIO server facade. */
public final class EmbeddedServer implements AutoCloseable {
    private final RunningServer server;

    private EmbeddedServer(RunningServer server) {
        this.server = server;
    }

    /** Starts an application on an ephemeral loopback port with the testing profile. */
    public static EmbeddedServer start(WaveApplication application) {
        return start(new InetSocketAddress("127.0.0.1", 0), application, ServerProfile.TESTING.options());
    }

    /** Starts an application on the supplied address with fully explicit settings. */
    public static EmbeddedServer start(InetSocketAddress address, WaveApplication application, ServerOptions options) {
        Objects.requireNonNull(address, "address");
        Objects.requireNonNull(application, "application");
        Objects.requireNonNull(options, "options");
        return new EmbeddedServer(WaveServer.start(address, application, options));
    }

    /** Returns the loopback socket address selected by the public server facade. */
    public InetSocketAddress address() {
        return server.address();
    }

    /** Returns the resolved settings of this fixture. */
    public ServerOptions options() {
        return server.options();
    }

    /** Stops the fixture using its configured shutdown grace. */
    @Override
    public void close() {
        server.close();
    }
}
