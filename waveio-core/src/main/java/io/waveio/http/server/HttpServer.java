package io.waveio.http.server;

import io.waveio.http.internal.ServerConfig;
import io.waveio.http.internal.netty.NettyHttpServer;
import io.waveio.http.middleware.Middleware;
import io.waveio.http.routing.Router;
import java.util.List;

/**
 * Lightweight, high-performance HTTP/1.1 server instance.
 *
 * <p>Constructed via {@link #builder()} or {@link HttpServerBuilder}. Implements {@link AutoCloseable}
 * for seamless lifecycle management inside {@code try-with-resources} blocks.
 *
 * <pre>{@code
 * try (HttpServer server = HttpServer.builder()
 *         .port(8080)
 *         .get("/ping", request -> HttpResponse.text("pong"))
 *         .build()
 *         .start()) {
 *     System.out.println("Server running on port: " + server.localPort());
 *     server.awaitTermination();
 * }
 * }</pre>
 *
 * @see HttpServerBuilder
 * @see Router
 */
public final class HttpServer implements AutoCloseable {
    private final NettyHttpServer runtime;

    HttpServer(ServerConfig config, Router router, List<Middleware> middleware,
            ExceptionHandler exceptionHandler, List<RequestObserver> observers) {
        runtime = new NettyHttpServer(config, router, middleware, exceptionHandler, observers);
    }

    /**
     * Creates a new fluent {@link HttpServerBuilder} to configure and assemble a server.
     *
     * @return a new server builder instance
     */
    public static HttpServerBuilder builder() {
        return new HttpServerBuilder();
    }

    /**
     * Starts listening and binding to the configured network socket.
     *
     * @return this running server instance
     * @throws IllegalStateException if the server is already running
     */
    public synchronized HttpServer start() {
        runtime.start();
        return this;
    }

    /**
     * Checks whether the server socket is active and accepting connections.
     *
     * @return {@code true} if running, {@code false} otherwise
     */
    public boolean isRunning() {
        return runtime.isRunning();
    }

    /**
     * Returns the bound local TCP port. Useful when configured with port 0 for ephemeral ports in tests.
     *
     * @return active local port number
     * @throws IllegalStateException if the server has not been started
     */
    public int localPort() {
        return runtime.localPort();
    }

    /**
     * Initiates a graceful shutdown, closing open connections and waiting up to {@code shutdownTimeout}.
     */
    public synchronized void stop() {
        runtime.stop();
    }

    /**
     * Blocks the calling thread until the server terminates or the thread is interrupted.
     *
     * @throws InterruptedException if interrupted while waiting
     */
    public void awaitTermination() throws InterruptedException {
        runtime.awaitTermination();
    }

    /**
     * Blocks the calling thread until the server terminates, ignoring interrupts.
     */
    public void awaitTerminationUninterruptibly() {
        runtime.awaitTerminationUninterruptibly();
    }

    /**
     * Starts the server and blocks until termination. Automatically stops the server upon exit.
     */
    public void run() {
        try (this) {
            start();
            try {
                awaitTermination();
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Closes and stops the server instance (equivalent to {@link #stop()}).
     */
    @Override
    public void close() {
        stop();
    }
}
