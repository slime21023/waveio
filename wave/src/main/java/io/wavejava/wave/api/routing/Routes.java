package io.wavejava.wave.api.routing;

import io.wavejava.wave.api.http.HttpMethod;
import io.wavejava.wave.api.http.Request;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Immutable, compiled HTTP route table.
 *
 * <p>Routes are registered through {@link Builder} and compiled once during application startup.
 * A compiled table performs segment-based lookup with static segments preferred over parameters,
 * and parameters preferred over terminal wildcards.</p>
 */
public final class Routes {
    private final CompiledRouteTree tree;

    private Routes(CompiledRouteTree tree) {
        this.tree = Objects.requireNonNull(tree, "tree");
    }

    /** Returns a mutable builder for an immutable route table. */
    public static Builder builder() {
        return new Builder();
    }

    /** Builds routes from one registration callback. */
    public static Routes of(Consumer<? super Builder> definitions) {
        Objects.requireNonNull(definitions, "definitions");
        Builder builder = builder();
        definitions.accept(builder);
        return builder.build();
    }

    /** Matches the request method and normalized path. */
    public RouteMatch match(Request request) {
        Objects.requireNonNull(request, "request");
        return match(request.method(), request.path());
    }

    /** Matches a method and normalized origin-form path. */
    public RouteMatch match(HttpMethod method, String path) {
        return tree.match(Objects.requireNonNull(method, "method"), path);
    }

    /** Matches a method token and normalized origin-form path. */
    public RouteMatch match(String method, String path) {
        Objects.requireNonNull(method, "method");
        return match(HttpMethod.of(method), path);
    }

    /** Returns all registered routes in registration order. */
    public List<RouteMetadata> metadata() {
        return tree.metadata();
    }

    /** Builder for a compiled route table. */
    public static final class Builder {
        private final List<RouteDefinition> definitions = new ArrayList<>();

        private Builder() {
        }

        /** Registers a handler for an HTTP method and exact, parameterized, or wildcard path. */
        public Builder route(HttpMethod method, String pattern, Handler handler) {
            Objects.requireNonNull(method, "method");
            definitions.add(new RouteDefinition(
                    HttpMethod.of(method.name()),
                    RoutePattern.parse(pattern),
                    Objects.requireNonNull(handler, "handler")));
            return this;
        }

        /** Registers a handler for a standard or custom HTTP method token. */
        public Builder route(String method, String pattern, Handler handler) {
            Objects.requireNonNull(method, "method");
            return route(HttpMethod.of(method), pattern, handler);
        }

        /** Registers a GET handler. */
        public Builder get(String pattern, Handler handler) {
            return route("GET", pattern, handler);
        }

        /** Registers a HEAD handler that overrides automatic GET-to-HEAD dispatch for its pattern. */
        public Builder head(String pattern, Handler handler) {
            return route("HEAD", pattern, handler);
        }

        /** Registers a POST handler. */
        public Builder post(String pattern, Handler handler) {
            return route("POST", pattern, handler);
        }

        /** Registers a PUT handler. */
        public Builder put(String pattern, Handler handler) {
            return route("PUT", pattern, handler);
        }

        /** Registers a PATCH handler. */
        public Builder patch(String pattern, Handler handler) {
            return route("PATCH", pattern, handler);
        }

        /** Registers a DELETE handler. */
        public Builder delete(String pattern, Handler handler) {
            return route("DELETE", pattern, handler);
        }

        /** Registers an OPTIONS handler that overrides automatic OPTIONS handling for its pattern. */
        public Builder options(String pattern, Handler handler) {
            return route("OPTIONS", pattern, handler);
        }

        /**
         * Registers a nested route scope below {@code prefix}.
         *
         * <p>Routes inside the callback remain absolute (for example, {@code "/users"}); the
         * resulting registered pattern is the concatenation of the scope and child patterns.</p>
         */
        public Builder prefix(String prefix, Consumer<? super Builder> nestedDefinitions) {
            Objects.requireNonNull(nestedDefinitions, "nestedDefinitions");
            RoutePattern.parse(prefix);
            Builder nested = new Builder();
            nestedDefinitions.accept(nested);
            for (RouteDefinition definition : nested.definitions) {
                definitions.add(new RouteDefinition(
                        definition.method(),
                        RoutePattern.parse(RoutePattern.join(prefix, definition.pattern().source())),
                        definition.handler()));
            }
            return this;
        }

        /** Alias for {@link #prefix(String, Consumer)}. */
        public Builder scope(String prefix, Consumer<? super Builder> nestedDefinitions) {
            return prefix(prefix, nestedDefinitions);
        }

        /** Compiles and validates all route registrations. */
        public Routes build() {
            return new Routes(CompiledRouteTree.compile(List.copyOf(definitions)));
        }
    }

    record RouteDefinition(HttpMethod method, RoutePattern pattern, Handler handler) {
        RouteDefinition {
            Objects.requireNonNull(method, "method");
            Objects.requireNonNull(pattern, "pattern");
            Objects.requireNonNull(handler, "handler");
        }
    }
}
