package io.wavejava.wave.api.routing;

import io.wavejava.wave.api.http.HttpMethod;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.StringJoiner;

/**
 * The result of matching an HTTP method and path against immutable {@link Routes}.
 *
 * <p>A non-matched result is still useful to the application pipeline: it distinguishes a missing
 * path from a method mismatch and carries the exact methods for an {@code Allow} header.</p>
 */
public final class RouteMatch {
    /** Routing outcomes that the application pipeline can translate to HTTP behavior. */
    public enum Kind {
        /** A registered handler matched the request. */
        MATCHED,
        /** No registered path pattern matched the request path. */
        NOT_FOUND,
        /** A path matched, but no registered handler accepts the request method. */
        METHOD_NOT_ALLOWED,
        /** The path has routes and OPTIONS is handled by the router's automatic OPTIONS policy. */
        AUTOMATIC_OPTIONS
    }

    private final Kind kind;
    private final RouteMetadata route;
    private final Handler handler;
    private final Map<String, String> pathParameters;
    private final Set<HttpMethod> allowedMethods;
    private final boolean headFallback;

    private RouteMatch(
            Kind kind,
            RouteMetadata route,
            Handler handler,
            Map<String, String> pathParameters,
            Set<HttpMethod> allowedMethods,
            boolean headFallback) {
        this.kind = Objects.requireNonNull(kind, "kind");
        this.route = route;
        this.handler = handler;
        this.pathParameters = Map.copyOf(Objects.requireNonNull(pathParameters, "pathParameters"));
        this.allowedMethods = Collections.unmodifiableSet(
                new LinkedHashSet<>(Objects.requireNonNull(allowedMethods, "allowedMethods")));
        this.headFallback = headFallback;

        if ((kind == Kind.MATCHED) != (route != null && handler != null)) {
            throw new IllegalArgumentException("only a matched route may carry a handler");
        }
        if (headFallback && kind != Kind.MATCHED) {
            throw new IllegalArgumentException("only a matched route may be a HEAD fallback");
        }
    }

    static RouteMatch matched(
            RouteMetadata route,
            Handler handler,
            Map<String, String> pathParameters,
            Set<HttpMethod> allowedMethods,
            boolean headFallback) {
        return new RouteMatch(Kind.MATCHED, route, handler, pathParameters, allowedMethods, headFallback);
    }

    static RouteMatch notFound() {
        return new RouteMatch(Kind.NOT_FOUND, null, null, Map.of(), Set.of(), false);
    }

    static RouteMatch methodNotAllowed(Set<HttpMethod> allowedMethods) {
        return new RouteMatch(Kind.METHOD_NOT_ALLOWED, null, null, Map.of(), allowedMethods, false);
    }

    static RouteMatch automaticOptions(Set<HttpMethod> allowedMethods) {
        return new RouteMatch(Kind.AUTOMATIC_OPTIONS, null, null, Map.of(), allowedMethods, false);
    }

    /** Returns the routing outcome. */
    public Kind kind() {
        return kind;
    }

    /** Returns whether a registered handler matched the request. */
    public boolean isMatched() {
        return kind == Kind.MATCHED;
    }

    /** Returns whether no route pattern matched the request path. */
    public boolean isNotFound() {
        return kind == Kind.NOT_FOUND;
    }

    /** Returns whether the path matched but the method is unsupported. */
    public boolean isMethodNotAllowed() {
        return kind == Kind.METHOD_NOT_ALLOWED;
    }

    /** Returns whether this result should be rendered as an automatic OPTIONS response. */
    public boolean isAutomaticOptions() {
        return kind == Kind.AUTOMATIC_OPTIONS;
    }

    /** Returns matched route metadata when {@link #isMatched()} is true. */
    public Optional<RouteMetadata> route() {
        return Optional.ofNullable(route);
    }

    /** Returns the matched handler when {@link #isMatched()} is true. */
    public Optional<Handler> handler() {
        return Optional.ofNullable(handler);
    }

    /** Returns matched route metadata or throws when this is not a matched result. */
    public RouteMetadata requireRoute() {
        if (route == null) {
            throw new IllegalStateException("routing result does not contain a route: " + kind);
        }
        return route;
    }

    /** Returns the matched handler or throws when this is not a matched result. */
    public Handler requireHandler() {
        if (handler == null) {
            throw new IllegalStateException("routing result does not contain a handler: " + kind);
        }
        return handler;
    }

    /** Returns immutable path parameters for the matched route, or an empty map otherwise. */
    public Map<String, String> pathParameters() {
        return pathParameters;
    }

    /**
     * Returns the methods accepted at the matched path.
     *
     * <p>The set includes automatic {@code HEAD} for a GET route and automatic {@code OPTIONS}
     * whenever at least one path pattern matched. Its iteration order is suitable for an
     * {@code Allow} header.</p>
     */
    public Set<HttpMethod> allowedMethods() {
        return allowedMethods;
    }

    /** Returns the deterministic {@code Allow} header value, or an empty string for a 404 result. */
    public String allowHeader() {
        StringJoiner joiner = new StringJoiner(", ");
        for (HttpMethod method : allowedMethods) {
            joiner.add(method.name());
        }
        return joiner.toString();
    }

    /**
     * Returns whether a HEAD request was dispatched through its route's GET handler.
     *
     * <p>The transport remains responsible for suppressing the response body.</p>
     */
    public boolean isHeadFallback() {
        return headFallback;
    }

    @Override
    public String toString() {
        return "RouteMatch[kind=" + kind + ", route=" + route + ", allowedMethods=" + allowedMethods + "]";
    }
}
