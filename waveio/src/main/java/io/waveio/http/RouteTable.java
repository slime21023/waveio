package io.waveio.http;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Immutable method and path route table with 404/405 policy decisions. */
public final class RouteTable {
    private final List<Route> routes;
    private RouteTable(List<Route> routes) { this.routes = List.copyOf(routes); }
    /** Returns a builder. */ public static Builder builder() { return new Builder(); }
    /** Resolves a request method and raw path to a route or HTTP policy result. */ public Resolution resolve(HttpMethod method, String path) {
        Objects.requireNonNull(method, "method"); List<Candidate> matches = new ArrayList<>();
        for (Route route : routes) { Map<String, String> parameters = route.pattern().match(path); if (!parameters.isEmpty() || route.pattern().toString().equals(path)) { matches.add(new Candidate(route, parameters)); } }
        if (matches.isEmpty()) { return Resolution.notFound(); }
        for (Candidate candidate : matches) { if (candidate.route().method() == method || method == HttpMethod.HEAD && candidate.route().method() == HttpMethod.GET) { return Resolution.matched(candidate.route(), candidate.parameters()); } }
        Set<HttpMethod> allowed = EnumSet.noneOf(HttpMethod.class); for (Candidate candidate : matches) { allowed.add(candidate.route().method()); if (candidate.route().method() == HttpMethod.GET) { allowed.add(HttpMethod.HEAD); } }
        return Resolution.methodNotAllowed(allowed);
    }
    /** Builder for an immutable route table. */ public static final class Builder {
        private final List<Route> routes = new ArrayList<>(); private Builder() { }
        /** Adds one method/path handler route. */ public Builder route(HttpMethod method, String pattern, Handler handler) { routes.add(new Route(Objects.requireNonNull(method, "method"), RoutePattern.of(pattern), Objects.requireNonNull(handler, "handler"))); return this; }
        /** Builds the table. */ public RouteTable build() { return new RouteTable(routes); }
    }
    /** One immutable route definition. */ public record Route(HttpMethod method, RoutePattern pattern, Handler handler) { }
    /** A routing result, including policy response metadata when no handler matches. */ public record Resolution(Route route, Map<String, String> parameters, HttpStatus status, Set<HttpMethod> allow) {
        static Resolution matched(Route route, Map<String, String> parameters) { return new Resolution(route, Map.copyOf(parameters), null, Set.of()); }
        static Resolution notFound() { return new Resolution(null, Map.of(), HttpStatus.NOT_FOUND, Set.of()); }
        static Resolution methodNotAllowed(Set<HttpMethod> allow) { return new Resolution(null, Map.of(), HttpStatus.METHOD_NOT_ALLOWED, Set.copyOf(allow)); }
        /** Returns whether this is a handler route. */ public boolean matched() { return route != null; }
        /** Returns the Allow header value for method mismatch. */ public String allowHeader() { return allow.stream().sorted(Comparator.comparing(Enum::name)).map(Enum::name).reduce((left, right) -> left + ", " + right).orElse(""); }
    }
    private record Candidate(Route route, Map<String, String> parameters) { }
}
