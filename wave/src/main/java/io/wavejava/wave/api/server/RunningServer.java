package io.wavejava.wave.api.server;

/** A started wave server that owns its listening socket, invocation runtime, and application services. */
public interface RunningServer extends AutoCloseable {
    /** Returns the actual local TCP port, including an ephemeral port selected from {@code 0}. */
    int port();

    /** Returns whether the listening channel is still open. */
    boolean isRunning();

    /**
     * Stops accepting traffic, releases server resources, then stops application services in
     * reverse dependency order.
     */
    @Override
    void close();
}
