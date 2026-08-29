package io.waveio.http.internal.routing;

import io.waveio.http.HttpMethod;
import io.waveio.http.handler.AsyncHttpHandler;
import io.waveio.http.handler.HttpHandler;
import io.waveio.http.handler.StreamingHttpHandler;
import io.waveio.http.middleware.Middleware;
import io.waveio.http.routing.RouteMetadata;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class RouteTable {
    private final List<RouteDefinition> routes;

    public RouteTable(List<RouteDefinition> routes) {
        this.routes = routes.stream()
                .sorted(Comparator.comparingInt(route -> route.pattern().specificityCost()))
                .toList();
    }

    public Optional<ResolvedRoute> resolve(HttpMethod method, String path) {
        for (var route : routes) {
            if (route.method() != method) continue;
            Map<String, String> parameters = route.pattern().match(path);
            if (parameters != null) {
                return Optional.of(new ResolvedRoute(route.handler(), route.asyncHandler(),
                        route.streamingHandler(), route.blocking(),
                        route.pattern().value(), route.metadata(), route.middleware(), parameters));
            }
        }
        return Optional.empty();
    }

    public Set<HttpMethod> allowedMethods(String path) {
        var methods = new LinkedHashSet<HttpMethod>();
        for (var route : routes) {
            if (route.pattern().match(path) != null) methods.add(route.method());
        }
        return Set.copyOf(methods);
    }

    public record ResolvedRoute(HttpHandler handler, AsyncHttpHandler asyncHandler,
            StreamingHttpHandler streamingHandler, boolean blocking,
            String pathPattern, RouteMetadata metadata, List<Middleware> middleware,
            Map<String, String> pathParameters) {
        public ResolvedRoute {
            middleware = List.copyOf(middleware);
            pathParameters = Map.copyOf(pathParameters);
        }
        public boolean asynchronous() { return asyncHandler != null || streamingHandler != null; }
        public boolean streaming() { return streamingHandler != null; }
    }
}
