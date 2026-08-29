package io.waveio.http.server;

import io.waveio.http.HttpException;
import io.waveio.http.HttpMethod;
import io.waveio.http.HttpRequest;
import io.waveio.http.HttpResponse;
import io.waveio.http.HttpStatus;
import io.waveio.http.handler.AsyncHttpHandler;
import io.waveio.http.handler.HttpHandler;
import io.waveio.http.handler.StreamingHttpHandler;
import io.waveio.http.internal.ServerConfig;
import io.waveio.http.internal.TlsConfig;
import io.waveio.http.middleware.Middleware;
import io.waveio.http.routing.RouteMetadata;
import io.waveio.http.routing.Router;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Fluent builder for configuring and instantiating an {@link HttpServer}.
 *
 * <p>Provides comprehensive knobs for socket binding, protocol buffer limits, timeouts,
 * connection admission thresholds, TLS certificates, middleware pipelines, exception mappers,
 * and observability listeners.
 *
 * <p><b>Default Configuration:</b>
 * <ul>
 *   <li>{@link #host(String)}: {@code "127.0.0.1"}</li>
 *   <li>{@link #port(int)}: {@code 8080}</li>
 *   <li>{@link #maxConnections(int)}: {@code 10,000} concurrent active connections</li>
 *   <li>{@link #maxPendingRequestsPerConnection(int)}: {@code 16} pipelined requests</li>
 *   <li>{@link #maxBodySize(int)}: {@code 1,048,576} bytes (1 MiB)</li>
 *   <li>{@link #maxInitialLineLength(int)}: {@code 4,096} bytes</li>
 *   <li>{@link #maxHeaderSize(int)}: {@code 8,192} bytes</li>
 *   <li>{@link #handlerTimeout(Duration)}: {@code 30 seconds}</li>
 *   <li>{@link #idleTimeout(Duration)}: {@code 30 seconds}</li>
 *   <li>{@link #readTimeout(Duration)}: {@code 30 seconds}</li>
 *   <li>{@link #writeTimeout(Duration)}: {@code 30 seconds}</li>
 *   <li>{@link #shutdownTimeout(Duration)}: {@code 10 seconds}</li>
 * </ul>
 *
 * @see HttpServer
 * @see Router
 */
public final class HttpServerBuilder {
    private String host = "127.0.0.1";
    private int port = 8080;
    private int maxInitialLineLength = 4_096;
    private int maxHeaderSize = 8_192;
    private int maxBodySize = 1_048_576;
    private Duration readTimeout = Duration.ofSeconds(30);
    private Duration shutdownTimeout = Duration.ofSeconds(10);
    private int maxPendingRequestsPerConnection = 16;
    private Duration handlerTimeout = Duration.ofSeconds(30);
    private int maxConnections = 10_000;
    private Duration writeTimeout = Duration.ofSeconds(30);
    private Duration idleTimeout = Duration.ofSeconds(30);
    private Path tlsCertificateChain;
    private Path tlsPrivateKey;
    private final Router.Builder routerBuilder = Router.builder();
    private Router router;
    private boolean hasInlineRoutes;
    private final List<Middleware> middleware = new ArrayList<>();
    private final List<RequestObserver> observers = new ArrayList<>();
    private ExceptionHandler exceptionHandler = HttpServerBuilder::defaultExceptionResponse;
    private io.waveio.http.body.BodyCodec bodyCodec;

    /**
     * Sets the local network interface IP address or hostname to bind.
     *
     * <p><b>Default:</b> {@code "127.0.0.1"}
     *
     * @param host non-blank hostname or IP address
     * @return this builder
     * @throws IllegalArgumentException if {@code host} is blank or null
     */
    public HttpServerBuilder host(String host) {
        if (host == null || host.isBlank()) throw new IllegalArgumentException("host must not be blank");
        this.host = host;
        return this;
    }

    /**
     * Sets the TCP port to listen on. Port {@code 0} binds to an ephemeral system-allocated port.
     *
     * <p><b>Default:</b> {@code 8080}
     *
     * @param port TCP port number between 0 and 65535
     * @return this builder
     * @throws IllegalArgumentException if {@code port} is outside [0, 65535]
     */
    public HttpServerBuilder port(int port) {
        if (port < 0 || port > 65_535) throw new IllegalArgumentException("port must be between 0 and 65535");
        this.port = port;
        return this;
    }

    /**
     * Sets the maximum allowed byte length for the HTTP initial request line (method + URI + protocol version).
     *
     * <p><b>Default:</b> {@code 4,096} bytes
     *
     * @param value positive maximum line length
     * @return this builder
     * @throws IllegalArgumentException if {@code value} is non-positive
     */
    public HttpServerBuilder maxInitialLineLength(int value) {
        this.maxInitialLineLength = positive(value, "maxInitialLineLength"); return this;
    }

    /**
     * Sets the maximum cumulative byte size of all incoming HTTP request headers.
     *
     * <p><b>Default:</b> {@code 8,192} bytes
     *
     * @param value positive maximum header size in bytes
     * @return this builder
     * @throws IllegalArgumentException if {@code value} is non-positive
     */
    public HttpServerBuilder maxHeaderSize(int value) {
        this.maxHeaderSize = positive(value, "maxHeaderSize"); return this;
    }

    /**
     * Sets the maximum payload body size in bytes. Payloads exceeding this threshold are rejected with {@code 413 Payload Too Large}.
     *
     * <p><b>Default:</b> {@code 1,048,576} bytes (1 MiB)
     *
     * @param value non-negative maximum body size in bytes (0 forbids bodies)
     * @return this builder
     * @throws IllegalArgumentException if {@code value} is negative
     */
    public HttpServerBuilder maxBodySize(int value) {
        if (value < 0) throw new IllegalArgumentException("maxBodySize must not be negative");
        this.maxBodySize = value; return this;
    }

    /**
     * Sets the socket read timeout between incoming TCP frames.
     *
     * <p><b>Default:</b> {@code Duration.ofSeconds(30)}
     *
     * @param value positive read timeout duration
     * @return this builder
     * @throws IllegalArgumentException if {@code value} is non-positive
     */
    public HttpServerBuilder readTimeout(Duration value) {
        this.readTimeout = positive(value, "readTimeout"); return this;
    }

    /**
     * Sets the maximum duration to wait for open connections and active requests to complete during server shutdown.
     *
     * <p><b>Default:</b> {@code Duration.ofSeconds(10)}
     *
     * @param value positive shutdown timeout duration
     * @return this builder
     * @throws IllegalArgumentException if {@code value} is non-positive
     */
    public HttpServerBuilder shutdownTimeout(Duration value) {
        this.shutdownTimeout = positive(value, "shutdownTimeout"); return this;
    }

    /**
     * Sets the maximum number of pipelined requests allowed in flight per TCP connection.
     * Exceeding this pauses TCP socket auto-read.
     *
     * <p><b>Default:</b> {@code 16}
     *
     * @param value positive queue capacity
     * @return this builder
     * @throws IllegalArgumentException if {@code value} is non-positive
     */
    public HttpServerBuilder maxPendingRequestsPerConnection(int value) {
        this.maxPendingRequestsPerConnection = positive(value,
                "maxPendingRequestsPerConnection");
        return this;
    }

    /**
     * Sets the maximum total execution time allowed for request handlers.
     * Handlers exceeding this deadline are terminated with {@code 504 Gateway Timeout} and virtual threads interrupted.
     *
     * <p><b>Default:</b> {@code Duration.ofSeconds(30)}
     *
     * @param value positive handler timeout duration
     * @return this builder
     * @throws IllegalArgumentException if {@code value} is non-positive
     */
    public HttpServerBuilder handlerTimeout(Duration value) {
        this.handlerTimeout = positive(value, "handlerTimeout");
        return this;
    }

    /**
     * Sets the global maximum number of concurrent active TCP connections accepted by the server.
     *
     * <p><b>Default:</b> {@code 10,000}
     *
     * @param value positive connection limit
     * @return this builder
     * @throws IllegalArgumentException if {@code value} is non-positive
     */
    public HttpServerBuilder maxConnections(int value) {
        this.maxConnections = positive(value, "maxConnections");
        return this;
    }

    /**
     * Sets the maximum duration allowed to write response bytes to the socket.
     *
     * <p><b>Default:</b> {@code Duration.ofSeconds(30)}
     *
     * @param value positive write timeout duration
     * @return this builder
     * @throws IllegalArgumentException if {@code value} is non-positive
     */
    public HttpServerBuilder writeTimeout(Duration value) {
        this.writeTimeout = positive(value, "writeTimeout");
        return this;
    }

    /**
     * Sets the connection idle timeout when no request is in progress.
     *
     * <p><b>Default:</b> {@code Duration.ofSeconds(30)}
     *
     * @param value positive idle timeout duration
     * @return this builder
     * @throws IllegalArgumentException if {@code value} is non-positive
     */
    public HttpServerBuilder idleTimeout(Duration value) {
        this.idleTimeout = positive(value, "idleTimeout");
        return this;
    }

    /**
     * Configures native TLS with PEM certificate chain and PKCS#8 private key files.
     *
     * @param certificateChain path to PEM-encoded X.509 certificate chain
     * @param privateKey path to PEM-encoded PKCS#8 private key
     * @return this builder
     */
    public HttpServerBuilder tls(Path certificateChain, Path privateKey) {
        this.tlsCertificateChain = Objects.requireNonNull(certificateChain,
                "certificateChain");
        this.tlsPrivateKey = Objects.requireNonNull(privateKey, "privateKey");
        return this;
    }

    /**
     * Supplies the codec used by {@code HttpResponse.value(...)} and {@code HttpRequest.bodyAs(...)}.
     *
     * <p>WaveIO ships no implementation: object mapping stays out of the runtime, so this is the
     * seam an application uses to plug in its own. Without one, a value body produces {@code 500}.
     *
     * @param value pluggable body codec implementation
     * @return this builder
     */
    public HttpServerBuilder bodyCodec(io.waveio.http.body.BodyCodec value) {
        this.bodyCodec = Objects.requireNonNull(value, "value");
        return this;
    }

    /**
     * Appends a global middleware interceptor executed on all incoming requests.
     *
     * @param value middleware interceptor
     * @return this builder
     */
    public HttpServerBuilder use(Middleware value) {
        middleware.add(Objects.requireNonNull(value, "value"));
        return this;
    }

    /**
     * Configures a custom exception handler mapping uncaught throwables to HTTP responses.
     *
     * @param value custom exception handler
     * @return this builder
     */
    public HttpServerBuilder exceptionHandler(ExceptionHandler value) {
        this.exceptionHandler = Objects.requireNonNull(value, "value");
        return this;
    }

    /**
     * Registers a request observation listener for metrics and logging.
     *
     * @param value observer callback
     * @return this builder
     */
    public HttpServerBuilder observe(RequestObserver value) {
        observers.add(Objects.requireNonNull(value, "value"));
        return this;
    }

    /**
     * Installs a pre-built immutable {@link Router}.
     *
     * @param value pre-configured router
     * @return this builder
     * @throws IllegalStateException if inline routes have already been registered on this builder
     */
    public HttpServerBuilder router(Router value) {
        if (hasInlineRoutes) {
            throw new IllegalStateException("Cannot install a Router after registering inline routes");
        }
        if (router != null) throw new IllegalStateException("Router is already configured");
        router = Objects.requireNonNull(value, "value");
        return this;
    }

    public HttpServerBuilder route(HttpMethod method, String path, HttpHandler handler) {
        inlineRoutes().route(method, path, handler);
        return this;
    }

    public HttpServerBuilder route(HttpMethod method, String path, RouteMetadata metadata,
            HttpHandler handler) {
        inlineRoutes().route(method, path, metadata, handler);
        return this;
    }

    public HttpServerBuilder blockingRoute(HttpMethod method, String path, HttpHandler handler) {
        inlineRoutes().blockingRoute(method, path, handler);
        return this;
    }

    public HttpServerBuilder blockingRoute(HttpMethod method, String path,
            RouteMetadata metadata, HttpHandler handler) {
        inlineRoutes().blockingRoute(method, path, metadata, handler);
        return this;
    }

    public HttpServerBuilder asyncRoute(HttpMethod method, String path, AsyncHttpHandler handler) {
        inlineRoutes().asyncRoute(method, path, handler);
        return this;
    }

    public HttpServerBuilder asyncRoute(HttpMethod method, String path, RouteMetadata metadata,
            AsyncHttpHandler handler) {
        inlineRoutes().asyncRoute(method, path, metadata, handler);
        return this;
    }

    public HttpServerBuilder streamingRoute(HttpMethod method, String path,
            StreamingHttpHandler handler) {
        inlineRoutes().streamingRoute(method, path, handler);
        return this;
    }

    public HttpServerBuilder streamingRoute(HttpMethod method, String path,
            RouteMetadata metadata, StreamingHttpHandler handler) {
        inlineRoutes().streamingRoute(method, path, metadata, handler);
        return this;
    }

    public HttpServerBuilder get(String path, HttpHandler handler) {
        return route(HttpMethod.GET, path, handler);
    }
    public HttpServerBuilder get(String path, RouteMetadata metadata, HttpHandler handler) {
        return route(HttpMethod.GET, path, metadata, handler);
    }

    public HttpServerBuilder post(String path, HttpHandler handler) {
        return route(HttpMethod.POST, path, handler);
    }

    public HttpServerBuilder head(String path, HttpHandler handler) { return route(HttpMethod.HEAD, path, handler); }
    public HttpServerBuilder put(String path, HttpHandler handler) { return route(HttpMethod.PUT, path, handler); }
    public HttpServerBuilder patch(String path, HttpHandler handler) { return route(HttpMethod.PATCH, path, handler); }
    public HttpServerBuilder delete(String path, HttpHandler handler) { return route(HttpMethod.DELETE, path, handler); }
    public HttpServerBuilder options(String path, HttpHandler handler) { return route(HttpMethod.OPTIONS, path, handler); }

    public HttpServerBuilder blockingGet(String path, HttpHandler handler) {
        return blockingRoute(HttpMethod.GET, path, handler);
    }

    public HttpServerBuilder blockingPost(String path, HttpHandler handler) {
        return blockingRoute(HttpMethod.POST, path, handler);
    }

    public HttpServerBuilder blockingPut(String path, HttpHandler handler) { return blockingRoute(HttpMethod.PUT, path, handler); }
    public HttpServerBuilder blockingPatch(String path, HttpHandler handler) { return blockingRoute(HttpMethod.PATCH, path, handler); }
    public HttpServerBuilder blockingDelete(String path, HttpHandler handler) { return blockingRoute(HttpMethod.DELETE, path, handler); }
    public HttpServerBuilder asyncGet(String path, AsyncHttpHandler handler) { return asyncRoute(HttpMethod.GET, path, handler); }
    public HttpServerBuilder asyncGet(String path, RouteMetadata metadata, AsyncHttpHandler handler) { return asyncRoute(HttpMethod.GET, path, metadata, handler); }
    public HttpServerBuilder asyncHead(String path, AsyncHttpHandler handler) { return asyncRoute(HttpMethod.HEAD, path, handler); }
    public HttpServerBuilder asyncPost(String path, AsyncHttpHandler handler) { return asyncRoute(HttpMethod.POST, path, handler); }
    public HttpServerBuilder asyncPut(String path, AsyncHttpHandler handler) { return asyncRoute(HttpMethod.PUT, path, handler); }
    public HttpServerBuilder asyncPatch(String path, AsyncHttpHandler handler) { return asyncRoute(HttpMethod.PATCH, path, handler); }
    public HttpServerBuilder asyncDelete(String path, AsyncHttpHandler handler) { return asyncRoute(HttpMethod.DELETE, path, handler); }
    public HttpServerBuilder asyncOptions(String path, AsyncHttpHandler handler) { return asyncRoute(HttpMethod.OPTIONS, path, handler); }
    public HttpServerBuilder streamingPost(String path, StreamingHttpHandler handler) { return streamingRoute(HttpMethod.POST, path, handler); }
    public HttpServerBuilder streamingPut(String path, StreamingHttpHandler handler) { return streamingRoute(HttpMethod.PUT, path, handler); }
    public HttpServerBuilder streamingPatch(String path, StreamingHttpHandler handler) { return streamingRoute(HttpMethod.PATCH, path, handler); }

    /**
     * Compiles all configuration settings and freezes routes into a new, unstarted {@link HttpServer}.
     *
     * @return a new {@link HttpServer} instance ready to be started via {@link HttpServer#start()}
     */
    public HttpServer build() {
        var tls = tlsCertificateChain == null ? null
                : new TlsConfig(tlsCertificateChain, tlsPrivateKey);
        var config = new ServerConfig(host, port, maxInitialLineLength, maxHeaderSize,
                maxBodySize, readTimeout, shutdownTimeout, maxPendingRequestsPerConnection,
                handlerTimeout, maxConnections, writeTimeout, idleTimeout, tls, bodyCodec);
        var configuredRouter = router != null ? router : routerBuilder.build();
        return new HttpServer(config, configuredRouter, List.copyOf(middleware), exceptionHandler,
                List.copyOf(observers));
    }

    private Router.Builder inlineRoutes() {
        if (router != null) {
            throw new IllegalStateException("Cannot register inline routes after installing a Router");
        }
        hasInlineRoutes = true;
        return routerBuilder;
    }

    private static int positive(int value, String name) {
        if (value <= 0) throw new IllegalArgumentException(name + " must be positive");
        return value;
    }

    private static Duration positive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    private static HttpResponse defaultExceptionResponse(Throwable failure, HttpRequest request) {
        if (failure instanceof java.util.concurrent.TimeoutException) {
            return HttpResponse.status(HttpStatus.GATEWAY_TIMEOUT)
                    .header("content-type", "text/plain; charset=utf-8")
                    .body("Gateway Timeout");
        }
        if (failure instanceof HttpException httpFailure) {
            return HttpResponse.status(httpFailure.status())
                    .header("content-type", "text/plain; charset=utf-8")
                    .body(httpFailure.getMessage());
        }
        return HttpResponse.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .header("content-type", "text/plain; charset=utf-8")
                .body("Internal Server Error");
    }
}
