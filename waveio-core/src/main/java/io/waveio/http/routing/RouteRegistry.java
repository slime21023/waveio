package io.waveio.http.routing;

import io.waveio.http.HttpMethod;
import io.waveio.http.handler.AsyncHttpHandler;
import io.waveio.http.handler.HttpHandler;
import io.waveio.http.handler.StreamingHttpHandler;
import io.waveio.http.middleware.Middleware;
import java.util.function.Consumer;

/**
 * Fluent route registration interface supporting multiple execution engines and hierarchical grouping.
 *
 * <p>Supports declaring endpoints across three execution tiers:
 * <ul>
 *   <li><b>Non-blocking routes</b> ({@link #route(HttpMethod, String, HttpHandler)}, {@link #get(String, HttpHandler)}, etc.):
 *       Execute directly on Netty I/O EventLoop threads.</li>
 *   <li><b>Blocking routes</b> ({@link #blockingRoute(HttpMethod, String, HttpHandler)}, {@link #blockingGet(String, HttpHandler)}, etc.):
 *       Execute on Java 21 Virtual Threads for synchronous database/filesystem operations.</li>
 *   <li><b>Asynchronous routes</b> ({@link #asyncRoute(HttpMethod, String, AsyncHttpHandler)}, {@link #asyncGet(String, AsyncHttpHandler)}, etc.):
 *       Return {@link java.util.concurrent.CompletionStage} instances.</li>
 *   <li><b>Streaming routes</b> ({@link #streamingRoute(HttpMethod, String, StreamingHttpHandler)}, {@link #streamingPost(String, StreamingHttpHandler)}, etc.):
 *       Handle reactive inbound request body streams.</li>
 * </ul>
 *
 * <p><b>Route Patterns:</b>
 * <ul>
 *   <li>Exact path: {@code "/users"}</li>
 *   <li>Path parameter: {@code "/users/:id"} (extracted into {@link io.waveio.http.HttpRequest#pathParam(String)})</li>
 *   <li>Wildcard: {@code "/assets/*path"} (matches any suffix path)</li>
 * </ul>
 *
 * @see Router
 * @see RouteModule
 * @see RouteMetadata
 */
public interface RouteRegistry {

    /**
     * Registers a non-blocking route on the Netty EventLoop.
     *
     * @param method HTTP method
     * @param path route pattern
     * @param handler non-blocking HTTP handler
     * @return this registry
     */
    RouteRegistry route(HttpMethod method, String path, HttpHandler handler);

    /**
     * Registers a blocking route executed on Java 21 Virtual Threads.
     *
     * @param method HTTP method
     * @param path route pattern
     * @param handler blocking HTTP handler
     * @return this registry
     */
    RouteRegistry blockingRoute(HttpMethod method, String path, HttpHandler handler);

    /**
     * Registers an asynchronous route returning a {@link java.util.concurrent.CompletionStage}.
     *
     * @param method HTTP method
     * @param path route pattern
     * @param handler async HTTP handler
     * @return this registry
     */
    RouteRegistry asyncRoute(HttpMethod method, String path, AsyncHttpHandler handler);

    /**
     * Registers a streaming route consuming an inbound body {@link java.util.concurrent.Flow.Publisher}.
     *
     * @param method HTTP method
     * @param path route pattern
     * @param handler streaming HTTP handler
     * @return this registry
     */
    default RouteRegistry streamingRoute(HttpMethod method, String path,
            StreamingHttpHandler handler) {
        throw new UnsupportedOperationException(
                "This RouteRegistry implementation does not support streaming request bodies");
    }

    /**
     * Registers a non-blocking route with associated metadata.
     *
     * @param method HTTP method
     * @param path route pattern
     * @param metadata route metadata
     * @param handler non-blocking HTTP handler
     * @return this registry
     */
    default RouteRegistry route(HttpMethod method, String path, RouteMetadata metadata,
            HttpHandler handler) {
        requireEmptyMetadata(metadata);
        return route(method, path, handler);
    }

    /**
     * Registers a blocking route with associated metadata.
     *
     * @param method HTTP method
     * @param path route pattern
     * @param metadata route metadata
     * @param handler blocking HTTP handler
     * @return this registry
     */
    default RouteRegistry blockingRoute(HttpMethod method, String path, RouteMetadata metadata,
            HttpHandler handler) {
        requireEmptyMetadata(metadata);
        return blockingRoute(method, path, handler);
    }

    /**
     * Registers an asynchronous route with associated metadata.
     *
     * @param method HTTP method
     * @param path route pattern
     * @param metadata route metadata
     * @param handler async HTTP handler
     * @return this registry
     */
    default RouteRegistry asyncRoute(HttpMethod method, String path, RouteMetadata metadata,
            AsyncHttpHandler handler) {
        requireEmptyMetadata(metadata);
        return asyncRoute(method, path, handler);
    }

    /**
     * Registers a streaming route with associated metadata.
     *
     * @param method HTTP method
     * @param path route pattern
     * @param metadata route metadata
     * @param handler streaming HTTP handler
     * @return this registry
     */
    default RouteRegistry streamingRoute(HttpMethod method, String path, RouteMetadata metadata,
            StreamingHttpHandler handler) {
        requireEmptyMetadata(metadata);
        return streamingRoute(method, path, handler);
    }

    /**
     * Registers route-scoped middleware applied to all routes registered in this scope.
     *
     * @param middleware middleware interceptor
     * @return this registry
     */
    RouteRegistry use(Middleware middleware);

    /**
     * Creates a nested route prefix group.
     *
     * @param prefix path prefix (e.g. {@code "/api/v1"})
     * @param routes registration callback
     * @return this registry
     */
    RouteRegistry group(String prefix, Consumer<RouteRegistry> routes);

    /**
     * Installs a modular route definition module.
     *
     * @param module route module instance
     * @return this registry
     */
    default RouteRegistry install(RouteModule module) { module.register(this); return this; }

    /**
     * Serves the files under {@code root} below {@code prefix}.
     *
     * <p>Registered as a blocking route because it performs file-system calls. Containment is
     * enforced by the runtime: a request can never read outside {@code root}, including through an
     * absolute-looking path segment or a symbolic link.
     *
     * @param prefix URL prefix to expose files under
     * @param root filesystem directory root
     * @return this registry
     */
    default RouteRegistry staticFiles(String prefix, java.nio.file.Path root) {
        java.util.Objects.requireNonNull(prefix, "prefix");
        String parameter = "waveioStaticPath";
        return blockingRoute(HttpMethod.GET,
                prefix.endsWith("/") ? prefix + "*" + parameter : prefix + "/*" + parameter,
                io.waveio.http.internal.files.StaticFiles.handler(root, parameter));
    }

    /** Shortcut to register a non-blocking {@code GET} route. */
    default RouteRegistry get(String path, HttpHandler handler) { return route(HttpMethod.GET, path, handler); }
    /** Shortcut to register a non-blocking {@code GET} route with metadata. */
    default RouteRegistry get(String path, RouteMetadata metadata, HttpHandler handler) { return route(HttpMethod.GET, path, metadata, handler); }
    /** Shortcut to register a non-blocking {@code HEAD} route. */
    default RouteRegistry head(String path, HttpHandler handler) { return route(HttpMethod.HEAD, path, handler); }
    default RouteRegistry post(String path, HttpHandler handler) { return route(HttpMethod.POST, path, handler); }
    default RouteRegistry post(String path, RouteMetadata metadata, HttpHandler handler) { return route(HttpMethod.POST, path, metadata, handler); }
    default RouteRegistry put(String path, HttpHandler handler) { return route(HttpMethod.PUT, path, handler); }
    default RouteRegistry patch(String path, HttpHandler handler) { return route(HttpMethod.PATCH, path, handler); }
    default RouteRegistry delete(String path, HttpHandler handler) { return route(HttpMethod.DELETE, path, handler); }
    default RouteRegistry options(String path, HttpHandler handler) { return route(HttpMethod.OPTIONS, path, handler); }
    default RouteRegistry blockingGet(String path, HttpHandler handler) { return blockingRoute(HttpMethod.GET, path, handler); }
    default RouteRegistry blockingGet(String path, RouteMetadata metadata, HttpHandler handler) { return blockingRoute(HttpMethod.GET, path, metadata, handler); }
    default RouteRegistry blockingPost(String path, HttpHandler handler) { return blockingRoute(HttpMethod.POST, path, handler); }
    default RouteRegistry blockingPut(String path, HttpHandler handler) { return blockingRoute(HttpMethod.PUT, path, handler); }
    default RouteRegistry blockingPatch(String path, HttpHandler handler) { return blockingRoute(HttpMethod.PATCH, path, handler); }
    default RouteRegistry blockingDelete(String path, HttpHandler handler) { return blockingRoute(HttpMethod.DELETE, path, handler); }
    default RouteRegistry asyncGet(String path, AsyncHttpHandler handler) { return asyncRoute(HttpMethod.GET, path, handler); }
    default RouteRegistry asyncGet(String path, RouteMetadata metadata, AsyncHttpHandler handler) { return asyncRoute(HttpMethod.GET, path, metadata, handler); }
    default RouteRegistry asyncHead(String path, AsyncHttpHandler handler) { return asyncRoute(HttpMethod.HEAD, path, handler); }
    default RouteRegistry asyncPost(String path, AsyncHttpHandler handler) { return asyncRoute(HttpMethod.POST, path, handler); }
    default RouteRegistry asyncPost(String path, RouteMetadata metadata, AsyncHttpHandler handler) { return asyncRoute(HttpMethod.POST, path, metadata, handler); }
    default RouteRegistry asyncPut(String path, AsyncHttpHandler handler) { return asyncRoute(HttpMethod.PUT, path, handler); }
    default RouteRegistry asyncPatch(String path, AsyncHttpHandler handler) { return asyncRoute(HttpMethod.PATCH, path, handler); }
    default RouteRegistry asyncDelete(String path, AsyncHttpHandler handler) { return asyncRoute(HttpMethod.DELETE, path, handler); }
    default RouteRegistry asyncOptions(String path, AsyncHttpHandler handler) { return asyncRoute(HttpMethod.OPTIONS, path, handler); }
    default RouteRegistry streamingPost(String path, StreamingHttpHandler handler) { return streamingRoute(HttpMethod.POST, path, handler); }
    default RouteRegistry streamingPost(String path, RouteMetadata metadata, StreamingHttpHandler handler) { return streamingRoute(HttpMethod.POST, path, metadata, handler); }
    default RouteRegistry streamingPut(String path, StreamingHttpHandler handler) { return streamingRoute(HttpMethod.PUT, path, handler); }
    default RouteRegistry streamingPatch(String path, StreamingHttpHandler handler) { return streamingRoute(HttpMethod.PATCH, path, handler); }

    private static void requireEmptyMetadata(RouteMetadata metadata) {
        if (!RouteMetadata.empty().equals(java.util.Objects.requireNonNull(metadata, "metadata"))) {
            throw new UnsupportedOperationException(
                    "This RouteRegistry implementation does not support route metadata");
        }
    }
}
