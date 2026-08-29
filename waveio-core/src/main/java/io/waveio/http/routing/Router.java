package io.waveio.http.routing;

import io.waveio.http.HttpMethod;
import io.waveio.http.HttpRequest;
import io.waveio.http.handler.AsyncHttpHandler;
import io.waveio.http.handler.HttpHandler;
import io.waveio.http.handler.StreamingHttpHandler;
import io.waveio.http.internal.routing.DefaultRouteRegistry;
import io.waveio.http.internal.routing.RouteTable;
import io.waveio.http.middleware.Middleware;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Immutable, thread-safe routing engine for matching HTTP requests to handlers and middleware pipelines.
 *
 * <p><b>Routing Semantics &amp; Resolution Order:</b>
 * <ul>
 *   <li><b>Specificity Resolution:</b> Static literal paths (e.g. {@code "/users/me"}) take priority
 *       over parameterized paths (e.g. {@code "/users/:id"}), which take priority over wildcard
 *       suffixes (e.g. {@code "/assets/*path"}).</li>
 *   <li><b>Implicit HEAD Fallback:</b> When a {@code HEAD} request is received and no explicit
 *       {@code HEAD} route is declared, the router automatically resolves the matching {@code GET}
 *       route and omits the response body at transport time.</li>
 *   <li><b>Method Discovery:</b> {@link #allowedMethods(String)} provides all supported HTTP methods
 *       for a given path, automatically including implicit {@code HEAD} when {@code GET} exists.</li>
 * </ul>
 *
 * @see RouteRegistry
 * @see Builder
 */
public final class Router {
    private final RouteTable table;

    private Router(RouteTable table) {
        this.table = table;
    }

    /**
     * Creates a new mutable builder for assembling routes and middleware into an immutable router.
     *
     * @return a new {@link Builder} instance
     */
    public static Builder builder() { return new Builder(); }

    /**
     * Matches an incoming HTTP request against the routing table.
     *
     * @param request incoming HTTP request
     * @return optional containing the matched route details and extracted path parameters, or empty
     */
    public Optional<Match> match(HttpRequest request) {
        var resolved = table.resolve(request.method(), request.path());
        if (resolved.isEmpty() && request.method() == HttpMethod.HEAD) {
            resolved = table.resolve(HttpMethod.GET, request.path());
        }
        return resolved.map(route ->
                new Match(route.handler(), route.asyncHandler(), route.streamingHandler(),
                        route.blocking(), route.pathPattern(),
                        route.metadata(), route.middleware(),
                        request.withPathParameters(route.pathParameters())));
    }

    /**
     * Discovers all HTTP methods allowed for the specified request path (for 405 Method Not Allowed headers).
     *
     * @param path request path
     * @return set of allowed HTTP methods
     */
    public Set<HttpMethod> allowedMethods(String path) {
        var methods = table.allowedMethods(path);
        if (!methods.contains(HttpMethod.GET) || methods.contains(HttpMethod.HEAD)) return methods;
        var withImplicitHead = new java.util.LinkedHashSet<>(methods);
        withImplicitHead.add(HttpMethod.HEAD);
        return Set.copyOf(withImplicitHead);
    }

    /**
     * Record representing a successful routing resolution.
     *
     * @param handler non-blocking or blocking sync handler
     * @param asyncHandler async stage handler
     * @param streamingHandler streaming body handler
     * @param blocking whether execution is scheduled on virtual threads
     * @param pathPattern matched route template pattern
     * @param metadata attached route metadata
     * @param middleware scoped middleware chain for this route
     * @param request request enriched with extracted path parameters
     */
    public record Match(HttpHandler handler, AsyncHttpHandler asyncHandler,
            StreamingHttpHandler streamingHandler, boolean blocking,
            String pathPattern, RouteMetadata metadata, List<Middleware> middleware,
            HttpRequest request) {
        public Match { middleware = List.copyOf(middleware); }
        public boolean asynchronous() { return asyncHandler != null || streamingHandler != null; }
        public boolean streaming() { return streamingHandler != null; }
    }

    /**
     * Fluent builder for assembling and freezing immutable {@link Router} instances.
     */
    public static final class Builder implements RouteRegistry {
        private final DefaultRouteRegistry registry = new DefaultRouteRegistry();

        @Override public Builder route(HttpMethod method, String path, HttpHandler handler) { registry.route(method, path, handler); return this; }
        @Override public Builder blockingRoute(HttpMethod method, String path, HttpHandler handler) { registry.blockingRoute(method, path, handler); return this; }
        @Override public Builder asyncRoute(HttpMethod method, String path, AsyncHttpHandler handler) { registry.asyncRoute(method, path, handler); return this; }
        @Override public Builder streamingRoute(HttpMethod method, String path, StreamingHttpHandler handler) { registry.streamingRoute(method, path, handler); return this; }
        @Override public Builder route(HttpMethod method, String path, RouteMetadata metadata, HttpHandler handler) { registry.route(method, path, metadata, handler); return this; }
        @Override public Builder blockingRoute(HttpMethod method, String path, RouteMetadata metadata, HttpHandler handler) { registry.blockingRoute(method, path, metadata, handler); return this; }
        @Override public Builder asyncRoute(HttpMethod method, String path, RouteMetadata metadata, AsyncHttpHandler handler) { registry.asyncRoute(method, path, metadata, handler); return this; }
        @Override public Builder streamingRoute(HttpMethod method, String path, RouteMetadata metadata, StreamingHttpHandler handler) { registry.streamingRoute(method, path, metadata, handler); return this; }
        @Override public Builder use(Middleware middleware) { registry.use(middleware); return this; }
        @Override public Builder group(String prefix, Consumer<RouteRegistry> registrations) { registry.group(prefix, registrations); return this; }
        @Override public Builder install(RouteModule module) { registry.install(module); return this; }
        @Override public Builder staticFiles(String prefix, java.nio.file.Path root) { registry.staticFiles(prefix, root); return this; }
        @Override public Builder get(String path, HttpHandler handler) { registry.get(path, handler); return this; }
        @Override public Builder get(String path, RouteMetadata metadata, HttpHandler handler) { registry.get(path, metadata, handler); return this; }
        @Override public Builder head(String path, HttpHandler handler) { registry.head(path, handler); return this; }
        @Override public Builder post(String path, HttpHandler handler) { registry.post(path, handler); return this; }
        @Override public Builder put(String path, HttpHandler handler) { registry.put(path, handler); return this; }
        @Override public Builder patch(String path, HttpHandler handler) { registry.patch(path, handler); return this; }
        @Override public Builder delete(String path, HttpHandler handler) { registry.delete(path, handler); return this; }
        @Override public Builder options(String path, HttpHandler handler) { registry.options(path, handler); return this; }
        @Override public Builder blockingGet(String path, HttpHandler handler) { registry.blockingGet(path, handler); return this; }
        @Override public Builder blockingPost(String path, HttpHandler handler) { registry.blockingPost(path, handler); return this; }
        @Override public Builder blockingPut(String path, HttpHandler handler) { registry.blockingPut(path, handler); return this; }
        @Override public Builder blockingPatch(String path, HttpHandler handler) { registry.blockingPatch(path, handler); return this; }
        @Override public Builder blockingDelete(String path, HttpHandler handler) { registry.blockingDelete(path, handler); return this; }
        @Override public Builder asyncGet(String path, AsyncHttpHandler handler) { registry.asyncGet(path, handler); return this; }
        @Override public Builder asyncGet(String path, RouteMetadata metadata, AsyncHttpHandler handler) { registry.asyncGet(path, metadata, handler); return this; }
        @Override public Builder asyncHead(String path, AsyncHttpHandler handler) { registry.asyncHead(path, handler); return this; }
        @Override public Builder asyncPost(String path, AsyncHttpHandler handler) { registry.asyncPost(path, handler); return this; }
        @Override public Builder asyncPut(String path, AsyncHttpHandler handler) { registry.asyncPut(path, handler); return this; }
        @Override public Builder asyncPatch(String path, AsyncHttpHandler handler) { registry.asyncPatch(path, handler); return this; }
        @Override public Builder asyncDelete(String path, AsyncHttpHandler handler) { registry.asyncDelete(path, handler); return this; }
        @Override public Builder asyncOptions(String path, AsyncHttpHandler handler) { registry.asyncOptions(path, handler); return this; }
        @Override public Builder streamingPost(String path, StreamingHttpHandler handler) { registry.streamingPost(path, handler); return this; }
        @Override public Builder streamingPut(String path, StreamingHttpHandler handler) { registry.streamingPut(path, handler); return this; }
        @Override public Builder streamingPatch(String path, StreamingHttpHandler handler) { registry.streamingPatch(path, handler); return this; }

        /**
         * Compiles and freezes all registered routes and middleware into an immutable {@link Router}.
         *
         * @return a new {@code Router} instance
         */
        public Router build() { return new Router(registry.buildTable()); }
    }
}
