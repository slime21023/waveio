package io.wavejava.wave.testing;

import io.wavejava.wave.RunningServer;
import io.wavejava.wave.Wave;
import io.wavejava.wave.WaveApp;
import java.net.URI;
import java.util.Objects;

/**
 * A complete wave application running on an ephemeral loopback port for integration tests.
 *
 * <p>The fixture owns the listening server and must be closed, normally with
 * try-with-resources. It deliberately exposes only the public {@link RunningServer} lifecycle
 * information and a small HTTP test client; Netty transport objects never escape this API.</p>
 */
public final class EmbeddedApp implements AutoCloseable {
    private final RunningServer server;
    private final URI baseUri;
    private final TestHttpClient client;

    private EmbeddedApp(RunningServer server) {
        this.server = Objects.requireNonNull(server, "server");
        baseUri = URI.create("http://127.0.0.1:" + server.port() + "/");
        client = new TestHttpClient(baseUri);
    }

    /**
     * Starts {@code application} on an operating-system-selected loopback port.
     *
     * @param application the immutable application to expose to real HTTP clients
     * @return a fixture that owns the started server
     */
    public static EmbeddedApp start(WaveApp application) {
        Objects.requireNonNull(application, "application");
        return new EmbeddedApp(Wave.server(application).listen(0).start());
    }

    /** Returns the loopback URI ending in a slash, for example {@code http://127.0.0.1:54321/}. */
    public URI baseUri() {
        return baseUri;
    }

    /** Returns the operating-system-selected local TCP port. */
    public int port() {
        return server.port();
    }

    /** Returns whether the embedded listener is still accepting connections. */
    public boolean isRunning() {
        return server.isRunning();
    }

    /** Returns a blocking HTTP/1.1 test client whose relative paths resolve against this app. */
    public TestHttpClient client() {
        return client;
    }

    /** Resolves an origin-form path against {@link #baseUri()}. */
    public URI uri(String path) {
        return client.uri(path);
    }

    /** Stops the embedded listener and releases its resources. This operation is idempotent. */
    @Override
    public void close() {
        server.close();
    }
}

