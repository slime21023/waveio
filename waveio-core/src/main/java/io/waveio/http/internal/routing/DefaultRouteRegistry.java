package io.waveio.http.internal.routing;

import io.waveio.http.HttpMethod;
import io.waveio.http.handler.AsyncHttpHandler;
import io.waveio.http.handler.HttpHandler;
import io.waveio.http.handler.StreamingHttpHandler;
import io.waveio.http.middleware.Middleware;
import io.waveio.http.routing.RouteMetadata;
import io.waveio.http.routing.RouteModule;
import io.waveio.http.routing.RouteRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

public final class DefaultRouteRegistry implements RouteRegistry {
    private final List<MutableRoute> routes = new ArrayList<>();
    private final Scope root = new Scope(routes, "", List.of(), 0, new Counter());

    @Override public DefaultRouteRegistry route(HttpMethod method, String path, HttpHandler handler) { root.route(method, path, handler); return this; }
    @Override public DefaultRouteRegistry blockingRoute(HttpMethod method, String path, HttpHandler handler) { root.blockingRoute(method, path, handler); return this; }
    @Override public DefaultRouteRegistry asyncRoute(HttpMethod method, String path, AsyncHttpHandler handler) { root.asyncRoute(method, path, handler); return this; }
    @Override public DefaultRouteRegistry streamingRoute(HttpMethod method, String path, StreamingHttpHandler handler) { root.streamingRoute(method, path, handler); return this; }
    @Override public DefaultRouteRegistry route(HttpMethod method, String path, RouteMetadata metadata, HttpHandler handler) { root.route(method, path, metadata, handler); return this; }
    @Override public DefaultRouteRegistry blockingRoute(HttpMethod method, String path, RouteMetadata metadata, HttpHandler handler) { root.blockingRoute(method, path, metadata, handler); return this; }
    @Override public DefaultRouteRegistry asyncRoute(HttpMethod method, String path, RouteMetadata metadata, AsyncHttpHandler handler) { root.asyncRoute(method, path, metadata, handler); return this; }
    @Override public DefaultRouteRegistry streamingRoute(HttpMethod method, String path, RouteMetadata metadata, StreamingHttpHandler handler) { root.streamingRoute(method, path, metadata, handler); return this; }
    @Override public DefaultRouteRegistry use(Middleware middleware) { root.use(middleware); return this; }
    @Override public DefaultRouteRegistry group(String prefix, Consumer<RouteRegistry> registrations) { root.group(prefix, registrations); return this; }

    public RouteTable buildTable() {
        return new RouteTable(routes.stream().map(MutableRoute::freeze).toList());
    }

    private static final class Scope implements RouteRegistry {
        private final List<MutableRoute> routes;
        private final String prefix;
        private final List<MiddlewareEntry> inheritedMiddleware;
        private final List<MiddlewareEntry> localMiddleware = new ArrayList<>();
        private final int depth;
        private final Counter counter;
        private final int firstRouteIndex;

        private Scope(List<MutableRoute> routes, String prefix,
                List<MiddlewareEntry> inheritedMiddleware, int depth, Counter counter) {
            this.routes = routes;
            this.prefix = prefix;
            this.inheritedMiddleware = inheritedMiddleware;
            this.depth = depth;
            this.counter = counter;
            firstRouteIndex = routes.size();
        }

        @Override public RouteRegistry route(HttpMethod method, String path, HttpHandler handler) { add(method, path, false, handler); return this; }
        @Override public RouteRegistry blockingRoute(HttpMethod method, String path, HttpHandler handler) { add(method, path, true, handler); return this; }
        @Override public RouteRegistry asyncRoute(HttpMethod method, String path, AsyncHttpHandler handler) { addAsync(method, path, handler); return this; }
        @Override public RouteRegistry streamingRoute(HttpMethod method, String path, StreamingHttpHandler handler) { addStreaming(method, path, RouteMetadata.empty(), handler); return this; }
        @Override public RouteRegistry route(HttpMethod method, String path, RouteMetadata metadata, HttpHandler handler) { add(method, path, false, metadata, handler); return this; }
        @Override public RouteRegistry blockingRoute(HttpMethod method, String path, RouteMetadata metadata, HttpHandler handler) { add(method, path, true, metadata, handler); return this; }
        @Override public RouteRegistry asyncRoute(HttpMethod method, String path, RouteMetadata metadata, AsyncHttpHandler handler) { addAsync(method, path, metadata, handler); return this; }
        @Override public RouteRegistry streamingRoute(HttpMethod method, String path, RouteMetadata metadata, StreamingHttpHandler handler) { addStreaming(method, path, metadata, handler); return this; }

        @Override
        public RouteRegistry use(Middleware middleware) {
            Objects.requireNonNull(middleware, "middleware");
            var entry = new MiddlewareEntry(depth, counter.next(), middleware);
            localMiddleware.add(entry);
            for (int index = firstRouteIndex; index < routes.size(); index++) {
                routes.get(index).middleware.add(entry);
            }
            return this;
        }

        @Override
        public RouteRegistry group(String childPrefix, Consumer<RouteRegistry> registrations) {
            Objects.requireNonNull(registrations, "registrations");
            registrations.accept(new Scope(routes,
                    RoutePattern.join(prefix, childPrefix), allMiddleware(), depth + 1, counter));
            return this;
        }

        private void add(HttpMethod method, String path, boolean blocking, HttpHandler handler) {
            add(method, path, blocking, RouteMetadata.empty(), handler);
        }

        private void add(HttpMethod method, String path, boolean blocking,
                RouteMetadata metadata, HttpHandler handler) {
            Objects.requireNonNull(method, "method");
            Objects.requireNonNull(handler, "handler");
            Objects.requireNonNull(metadata, "metadata");
            var route = new MutableRoute(method, new RoutePattern(RoutePattern.join(prefix, path)),
                    blocking, handler, null, null, metadata, new ArrayList<>(allMiddleware()));
            addChecked(route);
        }

        private void addAsync(HttpMethod method, String path, AsyncHttpHandler handler) {
            addAsync(method, path, RouteMetadata.empty(), handler);
        }

        private void addAsync(HttpMethod method, String path, RouteMetadata metadata,
                AsyncHttpHandler handler) {
            Objects.requireNonNull(method, "method");
            Objects.requireNonNull(handler, "handler");
            Objects.requireNonNull(metadata, "metadata");
            var route = new MutableRoute(method, new RoutePattern(RoutePattern.join(prefix, path)),
                    false, null, handler, null, metadata, new ArrayList<>(allMiddleware()));
            addChecked(route);
        }

        private void addStreaming(HttpMethod method, String path, RouteMetadata metadata,
                StreamingHttpHandler handler) {
            Objects.requireNonNull(method, "method");
            Objects.requireNonNull(handler, "handler");
            Objects.requireNonNull(metadata, "metadata");
            var route = new MutableRoute(method, new RoutePattern(RoutePattern.join(prefix, path)),
                    false, null, null, handler, metadata, new ArrayList<>(allMiddleware()));
            addChecked(route);
        }

        private void addChecked(MutableRoute route) {
            if (routes.stream().anyMatch(existing -> existing.conflictsWith(route))) {
                throw new IllegalArgumentException("Duplicate route: " + route.method + " " + route.pattern.value());
            }
            routes.add(route);
        }

        private List<MiddlewareEntry> allMiddleware() {
            var result = new ArrayList<>(inheritedMiddleware);
            result.addAll(localMiddleware);
            return result;
        }
    }

    private static final class MutableRoute {
        private final HttpMethod method;
        private final RoutePattern pattern;
        private final boolean blocking;
        private final HttpHandler handler;
        private final AsyncHttpHandler asyncHandler;
        private final StreamingHttpHandler streamingHandler;
        private final RouteMetadata metadata;
        private final List<MiddlewareEntry> middleware;

        private MutableRoute(HttpMethod method, RoutePattern pattern, boolean blocking,
                HttpHandler handler, AsyncHttpHandler asyncHandler,
                StreamingHttpHandler streamingHandler, RouteMetadata metadata,
                List<MiddlewareEntry> middleware) {
            this.method = method;
            this.pattern = pattern;
            this.blocking = blocking;
            this.handler = handler;
            this.asyncHandler = asyncHandler;
            this.streamingHandler = streamingHandler;
            this.metadata = metadata;
            this.middleware = middleware;
        }

        private boolean conflictsWith(MutableRoute other) {
            return method == other.method && pattern.conflictsWith(other.pattern);
        }

        private RouteDefinition freeze() {
            var orderedMiddleware = middleware.stream()
                    .sorted(java.util.Comparator.comparingInt(MiddlewareEntry::depth)
                            .thenComparingLong(MiddlewareEntry::sequence))
                    .map(MiddlewareEntry::middleware)
                    .toList();
            return new RouteDefinition(method, pattern, blocking, handler, asyncHandler,
                    streamingHandler,
                    metadata, orderedMiddleware);
        }
    }

    private record MiddlewareEntry(int depth, long sequence, Middleware middleware) {}

    private static final class Counter {
        private long value;
        private long next() { return value++; }
    }
}
