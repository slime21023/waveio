package io.waveio.http.internal.routing;

import io.waveio.http.HttpMethod;
import io.waveio.http.handler.AsyncHttpHandler;
import io.waveio.http.handler.HttpHandler;
import io.waveio.http.handler.StreamingHttpHandler;
import io.waveio.http.middleware.Middleware;
import io.waveio.http.routing.RouteMetadata;
import java.util.List;

public record RouteDefinition(HttpMethod method, RoutePattern pattern, boolean blocking,
        HttpHandler handler, AsyncHttpHandler asyncHandler,
        StreamingHttpHandler streamingHandler, RouteMetadata metadata,
        List<Middleware> middleware) {
    public RouteDefinition(HttpMethod method, RoutePattern pattern, boolean blocking,
            HttpHandler handler, List<Middleware> middleware) {
        this(method, pattern, blocking, handler, null, null, RouteMetadata.empty(), middleware);
    }

    public RouteDefinition {
        int handlers = (handler == null ? 0 : 1) + (asyncHandler == null ? 0 : 1)
                + (streamingHandler == null ? 0 : 1);
        if (handlers != 1) {
            throw new IllegalArgumentException("Exactly one route handler must be configured");
        }
        if ((asyncHandler != null || streamingHandler != null) && blocking) {
            throw new IllegalArgumentException("Async routes cannot be marked blocking");
        }
        java.util.Objects.requireNonNull(metadata, "metadata");
        middleware = List.copyOf(middleware);
    }

    public boolean asynchronous() { return asyncHandler != null || streamingHandler != null; }
    public boolean streaming() { return streamingHandler != null; }
}
