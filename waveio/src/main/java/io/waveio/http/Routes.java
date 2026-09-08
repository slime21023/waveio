package io.waveio.http;

import io.waveio.task.Task;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/** Builds high-level endpoint routes and middleware into one low-level handler. */
public final class Routes {
    private Routes() { }

    /** Returns a mutable route builder. */
    public static Builder builder() {
        return new Builder();
    }

    /** Mutable application route configuration. */
    public static final class Builder {
        private final List<Middleware> middleware = new ArrayList<>();
        private final List<RouteDefinition> definitions = new ArrayList<>();

        private Builder() { }

        /** Adds middleware that wraps every endpoint in registration order. */
        public Builder use(Middleware value) {
            middleware.add(Objects.requireNonNull(value, "middleware"));
            return this;
        }

        /** Adds a GET endpoint. */
        public Builder get(String pattern, Endpoint endpoint) {
            return route(HttpMethod.GET, pattern, endpoint);
        }

        /** Adds a POST endpoint. */
        public Builder post(String pattern, Endpoint endpoint) {
            return route(HttpMethod.POST, pattern, endpoint);
        }

        /** Adds a PUT endpoint. */
        public Builder put(String pattern, Endpoint endpoint) {
            return route(HttpMethod.PUT, pattern, endpoint);
        }

        /** Adds a DELETE endpoint. */
        public Builder delete(String pattern, Endpoint endpoint) {
            return route(HttpMethod.DELETE, pattern, endpoint);
        }

        /** Adds a synchronous GET endpoint. */
        public Builder getResponse(String pattern, Function<EndpointContext, HttpResponse> endpoint) {
            Objects.requireNonNull(endpoint, "endpoint");
            return get(pattern, context -> Task.success(endpoint.apply(context)));
        }

        /** Adds an endpoint for any supported HTTP method. */
        public Builder route(HttpMethod method, String pattern, Endpoint endpoint) {
            definitions.add(new RouteDefinition(Objects.requireNonNull(method, "method"),
                    Objects.requireNonNull(pattern, "pattern"), Objects.requireNonNull(endpoint, "endpoint")));
            return this;
        }

        /** Builds a handler with the supplied application error policy. */
        public Handler build(ErrorHandler errors) {
            Objects.requireNonNull(errors, "errors");
            RouteTable.Builder table = RouteTable.builder();
            for (RouteDefinition definition : definitions) {
                table.route(definition.method(), definition.pattern(), adapt(definition.endpoint(), List.copyOf(middleware), errors));
            }
            return new Router(table.build());
        }

        private static Handler adapt(Endpoint endpoint, List<Middleware> middleware, ErrorHandler errors) {
            Endpoint pipeline = endpoint;
            for (int index = middleware.size() - 1; index >= 0; index--) {
                Middleware current = middleware.get(index);
                Endpoint next = pipeline;
                pipeline = context -> current.handle(context, () -> next.handle(context));
            }
            Endpoint finalPipeline = pipeline;
            return context -> {
                EndpointContext view = new EndpointContext() {
                    @Override public HttpRequest request() { return context.request(); }
                    @Override public Body body() { return context.body(); }
                    @Override public io.waveio.registry.Registry registry() { return context.registry(); }
                    @Override public java.util.Map<String, String> pathParameters() { return context.pathParameters(); }
                };
                Task<HttpResponse> response;
                try {
                    response = Objects.requireNonNull(finalPipeline.handle(view), "endpoint task");
                } catch (Throwable failure) {
                    response = recover(view, errors, failure);
                }
                return response.recoverWith(failure -> recover(view, errors, failure)).map(value -> {
                    context.respond(Objects.requireNonNull(value, "response"));
                    return null;
                });
            };
        }

        private static Task<HttpResponse> recover(EndpointContext context, ErrorHandler errors, Throwable failure) {
            try {
                return Objects.requireNonNull(errors.handle(context, failure), "error task");
            } catch (Throwable ignored) {
                return Task.success(HttpResponse.of(HttpStatus.INTERNAL_SERVER_ERROR));
            }
        }
    }

    private record RouteDefinition(HttpMethod method, String pattern, Endpoint endpoint) { }
}
